package de.kishorrana.signalboy_android.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.kishorrana.signalboy_android.MIN_IN_MILLISECONDS
import de.kishorrana.signalboy_android.REJECT_CONNECTION_DURATION_IN_MILLIS
import de.kishorrana.signalboy_android.SCAN_PERIOD_IN_MILLIS
import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.client.endpoint.readGattCharacteristicAsync
import de.kishorrana.signalboy_android.service.client.endpoint.startNotifyAsync
import de.kishorrana.signalboy_android.service.client.endpoint.writeGattCharacteristicAsync
import de.kishorrana.signalboy_android.service.client.util.hasAllSignalboyGattAttributes
import de.kishorrana.signalboy_android.service.gatt.*
import de.kishorrana.signalboy_android.service.scanner.Scanner
import de.kishorrana.signalboy_android.service.sync.SyncManager
import de.kishorrana.signalboy_android.util.fromByteArrayLE
import de.kishorrana.signalboy_android.util.now
import de.kishorrana.signalboy_android.util.toByteArrayLE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.parcelize.Parcelize
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.coroutines.CoroutineContext
import de.kishorrana.signalboy_android.service.client.State as ClientState
import de.kishorrana.signalboy_android.service.sync.State as SyncState

private const val TAG = "SignalboyService"

class SignalboyService : LifecycleService() {
    var onConnectionStateUpdateListener: OnConnectionStateUpdateListener? = null
    val state: State
        get() = latestState.value

    private val binder = LocalBinder()
    private val scope: CoroutineScope

    init {
        val exceptionHandler = CoroutineExceptionHandler { context, err ->
            Log.e(TAG, "Catching uncaught exception - context=$context error:", err)
        }
        scope = CoroutineScope(
            Dispatchers.Default +
                    SupervisorJob(lifecycleScope.coroutineContext.job) +
                    exceptionHandler +
                    CoroutineName("SignalboyService")
        )
    }

    private val client by lazy { Client(this, parentJob = scope.coroutineContext.job) }
    private val syncManager by lazy { SyncManager() }

    private val _latestState =
        MutableStateFlow<State>(State.Disconnected())
    val latestState = _latestState.asStateFlow()
        // Also publish to listener (for compatibility).
        .also {
            scope.launch {
                it
                    // Discard until first connection-attempt has been made.
                    .dropWhile { it is State.Disconnected }
                    .collect { newValue ->
                        withContext(Dispatchers.Main) {
                            Log.v(TAG, "state=$state")
                            onConnectionStateUpdateListener?.stateUpdated(newValue)
                        }
                    }
            }
        }

    // Key: Address of Peripheral that requested connection-reject
    private val rejectRequests = mutableMapOf<String, RejectRequest>()

    // Will be `true`, if establishing a connection has succeeded.
    private var connecting: Deferred<SignalboyDeviceInformation>? = null
    private var clientStateObserving: Job? = null
    private var connectionOptionsSubscription:
            Client.NotificationSubscription.CancellationToken? = null

    private var syncManagerCoroutineContext: CoroutineContext? = null
    private var syncManagerRestarting: Job? = null

    private var connectionSupervising: Job? = null

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var configuration: Configuration

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)

        bluetoothAdapter = getDefaultAdapter(this)
        configuration = intent.getParcelableExtra(EXTRA_CONFIGURATION) ?: Configuration.Default

        connectionSupervising?.cancel()
        if (configuration.isAutoReconnectEnabled) {
            connectionSupervising = scope.launchConnectionSupervising()
        }

        return binder
    }

    override fun onDestroy() {
        client.destroy()
        super.onDestroy()
    }

    fun tryDisconnectFromPeripheral() {
        scope.launch {
            try {
                disconnectFromPeripheralAsync()
            } catch (err: Throwable) {
                Log.v(TAG, "tryDisconnectFromPeripheral() - Discarding error:", err)
            }
        }
    }

    // Disconnect gracefully
    suspend fun disconnectFromPeripheralAsync() = disconnectFromPeripheralAsync(null)

    /**
     * Triggers sync manually (process will be performed asynchronously without blocking).
     * NOTE: **Must _not_ be called from outside of module.** Method is only public for
     * DEBUG-purposes.
     *
     * @return Returns `true` if starting the async operation was successful.
     *
     */
    fun tryTriggerSync(): Boolean {
        try {
            syncManager.debugTriggerSync()
        } catch (err: Throwable) {
            Log.e(TAG, "tryTriggerSync() - Failed to trigger sync due to error:", err)
            return false
        }
        return true
    }

    suspend fun connectToPeripheralAsync() = coroutineScope {
        Log.v(TAG, "connectToPeripheralAsync: I'm working in thread ${Thread.currentThread().name}")
        if (connecting?.isActive == true) {
            throw AlreadyConnectingException()
        }

        connecting = async {
            try {
                _latestState.value = State.Connecting

                // Try to discover Peripheral.
                //
                // Filter Peripherals that have active requests for
                // Connection-Rejection.
                val rejectRequestAddresses = rejectRequests
                    .filter { (_, rejectRequest) -> rejectRequest.isValid() }
                    .map { (address, _) -> address }

                val devices = Scanner(bluetoothAdapter).discoverPeripherals(
                    rejectRequestAddresses,
                    SignalboyGattAttributes.OUTPUT_SERVICE_UUID,
                    SCAN_PERIOD_IN_MILLIS,
                    true
                )
                Log.d(TAG, "Discovered devices (matching set filters): $devices")

                val device = devices.firstOrNull() ?: throw NoCompatiblePeripheralDiscovered(
                    "Cannot connect: No peripheral matching set filters found during scan."
                )

                // Try connecting to Peripheral.
                client.connectAsync(device).also {
                    (client.state as? ClientState.Connected)?.let(::requireSignalboyGattAttributes)
                }
                val deviceInformation = SignalboyDeviceInformation(
                    String(
                        client.readGattCharacteristicAsync(HardwareRevisionCharacteristic),
                        StandardCharsets.US_ASCII
                    ),
                    String(
                        client.readGattCharacteristicAsync(SoftwareRevisionCharacteristic),
                        StandardCharsets.US_ASCII
                    )
                )

                clientStateObserving?.cancelAndJoin()
                clientStateObserving = scope.launch {
                    try {
                        client.latestState
                            .onCompletion { err ->
                                // Observer is expected to complete when `clientStateObserving`-
                                // Job is cancelled.
                                Log.v(
                                    TAG, "clientStateObserving: Completion due to error:", err
                                )
                            }
                            .onEach { (it as? ClientState.Connected)?.let(::requireSignalboyGattAttributes) }
                            .combine(syncManager.latestState) { clientState, syncState ->
                                makeState(clientState, syncState, deviceInformation)
                            }
                            .collect { _latestState.value = it }
                    } catch (err: GattClientIsMissingAttributesException) {
                        Log.d(TAG, "clientStateObserving: caught error:", err)
                        scope.launch { disconnectFromPeripheralAsync(err) }
                    }
                }
                Log.i(TAG, "Successfully connected to peripheral.")

                startSyncManager()

                connectionOptionsSubscription?.cancel()
                connectionOptionsSubscription = setupConnectionOptionsSubscription()

                return@async deviceInformation
            } catch (err: Throwable) {
                val publicDisconnectCause = when (err) {
                    is CancellationException -> null    // Connection attempt was cancelled by user-request.
                    else -> err
                }
                scope.launch { disconnectFromPeripheralAsync(publicDisconnectCause) }
                throw err
            }
        }
    }

    /**
     * This is the synchronous variant of `sendEvent`.
     */
    fun trySendEvent() {
        scope.launch { sendEvent() }
    }

    suspend fun sendEvent() {
        val fireDateTimestamp = now() + configuration.normalizationDelay

        val isSynced = when (val state = state) {
            is State.Connected -> state.isSynced
            else -> return
        }

        if (isSynced) {
            val data = fireDateTimestamp.toUInt().toByteArrayLE()
            client.writeGattCharacteristicAsync(TargetTimestampCharacteristic, data, false)
        } else {
            // Fallback to unsynced method.
            Log.w(
                TAG, "Falling back to unsynced signaling of event as connected peripheral is " +
                        "not synced. Timing will be inaccurate."
            )
            delay(fireDateTimestamp - now())
            client.writeGattCharacteristicAsync(
                TriggerTimerCharacteristic,
                byteArrayOf(0x01),
                false
            )
        }
    }

    private suspend fun disconnectFromPeripheralAsync(disconnectCause: Throwable?) {
        try {
            connectionOptionsSubscription?.cancel()
            clientStateObserving?.cancelAndJoin()
            connecting?.cancelAndJoin()
            stopSyncManager()
        } catch (err: Throwable) {
            Log.v(
                TAG,
                "disconnectFromPeripheralAsync() - Encountered error during " +
                        "disconnect-attempt. Will still disconnect from peripheral and rethrow " +
                        "unhandled exception. Error:",
                err
            )
            throw err
        } finally {
            try {
                client.disconnectAsync()
            } catch (err: Throwable) {
                Log.v(
                    TAG,
                    "disconnectFromPeripheralAsync() - Encountered error during " +
                            "disconnect-attempt. Error:",
                    err
                )
                throw err
            } finally {
                // Publish
                _latestState.value = State.Disconnected(disconnectCause)
            }
        }
    }

    /**
     * Examines the GATT-client's services and characteristics and throws if expected
     * features are missing.
     *
     */
    private fun requireSignalboyGattAttributes(connectedState: ClientState.Connected) {
        if (!connectedState.services.hasAllSignalboyGattAttributes())
            throw GattClientIsMissingAttributesException()
    }

    //region Helper
    private fun CoroutineScope.launchSyncManager(client: Client, attempt: Int = 1) {
        Log.v(TAG, "Launching SyncManager (attempt=$attempt).")
        if (syncManagerCoroutineContext?.isActive == true) {
            throw IllegalStateException("Sync Service is already running.")
        }

        val childJob = Job(coroutineContext.job) + CoroutineName("SyncManager")
        val exceptionHandler = CoroutineExceptionHandler { _, err ->
            Log.e(TAG, "SyncManager (job=$childJob) failed with error:", err)
            // Stop service before next startup attempt.
            stopSyncManager()
            childJob.cancel()

            syncManagerRestarting?.cancel()
            syncManagerRestarting = launch {
                // Backoff strategy
                val delayMillis: Long = when (attempt) {
                    1 -> 3 * 1_000L
                    2 -> 10 * 1_000L
                    3 -> 30 * 1_000L
                    else -> 3 * MIN_IN_MILLISECONDS
                }
                Log.i(
                    TAG, "Next attempt to start SyncManager in ${delayMillis / 1_000}s " +
                            "(backoff-strategy)..."
                )
                delay(delayMillis)

                // Retry
                scope.launchSyncManager(client, attempt = attempt + 1)
            }
        }

        val context = coroutineContext + childJob + exceptionHandler
        syncManagerCoroutineContext = context
        syncManager.attach(context, client)
    }

    private fun handleConnectionOptionsCharacteristic(value: ByteArray) {
        val connectionOptionsRawValue = Int.fromByteArrayLE(value).also {
            Log.v(TAG, "handleConnectionOptionsCharacteristic() - connectionOptions=$it")
        }
        val connectionOptions = SignalboyConnectionOptions(connectionOptionsRawValue)

        if (connectionOptions.hasRejectRequest) {
            if (client.state is ClientState.Connected) {
                val address =
                    (client.state as de.kishorrana.signalboy_android.service.client.State.Connected)
                        .session.device.address
                rejectRequests[address] = RejectRequest(address, Date())

                scope.launch { disconnectFromPeripheralAsync(ConnectionRejectedException()) }
            } else {
                throw IllegalStateException(
                    "Received updated value for notification subscription, but client " +
                            "is not connected."
                )
            }
        }
    }

    /**
     * Subscribes for updates to the "ConnectionOptions"-Characteristic.
     *
     */
    private suspend fun setupConnectionOptionsSubscription():
            Client.NotificationSubscription.CancellationToken =
        client.startNotifyAsync(ConnectionOptionsCharacteristic) { (newValue) ->
            handleConnectionOptionsCharacteristic(newValue)
        }

    private fun startSyncManager() {
        scope.launchSyncManager(client)
    }

    private fun stopSyncManager() {
        syncManager.detach()
        syncManagerCoroutineContext?.cancel()
        syncManagerRestarting?.cancel()
    }

    /**
     * Launches child-job that observes the connection-state and automatically triggers actions like
     * reconnect when the connection gets dropped.
     *
     * @return The job that performs the supervision of the service's connection-state.
     */
    private fun CoroutineScope.launchConnectionSupervising(): Job =
        launch {
            suspend fun connect(reconnectAttempt: Int = 0) {
                // Backoff strategy
                val delayMillis: Long? = when (reconnectAttempt) {
                    0 -> null
                    1 -> 1 * 1_000L
                    2 -> 5 * 1_000L
                    3 -> 20 * 1_000L
                    else -> 3 * MIN_IN_MILLISECONDS
                }
                if (delayMillis != null) {
                    Log.i(
                        TAG,
                        "Will launch next connection attempt in ${delayMillis / 1_000}s (backoff-strategy)..."
                    )
                    delay(delayMillis)
                }

                try {
                    connectToPeripheralAsync()
                } catch (cancellation: CancellationException) {
                    throw cancellation  // Cancel this job by user-request.
                } catch (err: Throwable) {
                    Log.w(
                        TAG,
                        "Connection attempt failed (reconnectAttempt=$reconnectAttempt) due to error:",
                        err
                    )
                    connect(reconnectAttempt + 1)
                }
            }

            suspend fun reconnectIfNeeded(state: State) {
                // Reconnect if connection was dropped due to an error.
                val disconnectCause = (state as? State.Disconnected)?.cause
                if (disconnectCause != null) {
                    Log.w(TAG, "Connection lost due to error:", disconnectCause)
                    connect(1)
                }
            }

            connect()
            latestState
                .collect { state -> reconnectIfNeeded(state) }
        }

    /**
     * Returns `true`, if the Reject-Request is active (i.e. has not expired).
     */
    private fun RejectRequest.isValid() =
        Date().time - receivedTime.time < REJECT_CONNECTION_DURATION_IN_MILLIS
    //endregion

    //region Factory
    private fun makeState(
        connectionState: ClientState,
        syncState: SyncState,
        deviceInformation: SignalboyDeviceInformation
    ): State = when (connectionState) {
        is ClientState.Disconnected -> State.Disconnected(connectionState.cause)
        is ClientState.Connecting -> State.Connecting
        is ClientState.Connected -> State.Connected(
            deviceInformation,
            syncState is SyncState.Synced
        )
    }
    //endregion

    @Parcelize
    data class Configuration(
        /**
         * The fixed-delay that the resulting signals emitted by the Signalboy-device will be delayed.
         * In milliseconds.
         *
         * DISCUSSION: This delay is utilized to normalize the delay caused by network-latency (Bluetooth).
         * In order to produce the actual event-times, the (third-party) receiving system will have
         * to subtract the specified Normalization-Delay from the timestamps of the received
         * electronic TTL-events.
         */
        val normalizationDelay: Long,
        /**
         * If `true`, SignalboyService will automatically try to reconnect if connection
         * to the Signalboy Device has been lost.
         */
        val isAutoReconnectEnabled: Boolean
    ) : Parcelable {
        companion object {
            @JvmStatic
            val Default: Configuration by lazy {
                Configuration(
                    normalizationDelay = 100L,
                    isAutoReconnectEnabled = true
                )
            }
        }
    }

    fun interface OnConnectionStateUpdateListener {
        fun stateUpdated(state: State)
    }

    inner class LocalBinder : Binder() {
        fun getService(): SignalboyService = this@SignalboyService
    }

    sealed interface State {
        data class Disconnected(val cause: Throwable? = null) : State
        object Connecting : State
        data class Connected(
            val deviceInformation: SignalboyDeviceInformation,
            val isSynced: Boolean
        ) : State
    }

    companion object {
        const val EXTRA_CONFIGURATION = "EXTRA_CONFIGURATION"

        @JvmStatic
        fun getDefaultAdapter(context: Context): BluetoothAdapter =
            with(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager) {
                adapter ?: throw Exception(
                    "Unable to obtain a BluetoothAdapter. " +
                            "Tip: BLE can be required per the <uses-feature> tag " +
                            "in the AndroidManifest.xml"
                )
            }

        @JvmStatic
        fun verifyPrerequisites(
            context: Context,
            bluetoothAdapter: BluetoothAdapter
        ): SignalboyPrerequisitesHelper.PrerequisitesResult =
            SignalboyPrerequisitesHelper.verifyPrerequisites(context, bluetoothAdapter)
    }
}
