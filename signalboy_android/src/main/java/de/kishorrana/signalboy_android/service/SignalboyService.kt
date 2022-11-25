package de.kishorrana.signalboy_android.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.FragmentManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.kishorrana.signalboy_android.MIN_IN_MILLISECONDS
import de.kishorrana.signalboy_android.REJECT_CONNECTION_DURATION_IN_MILLIS
import de.kishorrana.signalboy_android.service.ISignalboyService.State
import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.client.DefaultClient
import de.kishorrana.signalboy_android.service.client.endpoint.readGattCharacteristicAsync
import de.kishorrana.signalboy_android.service.client.endpoint.startNotifyAsync
import de.kishorrana.signalboy_android.service.client.endpoint.writeGattCharacteristicAsync
import de.kishorrana.signalboy_android.service.client.util.hasAllSignalboyGattAttributes
import de.kishorrana.signalboy_android.service.connection_supervising.ConnectionSupervisingManager
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import de.kishorrana.signalboy_android.service.discovery.DeviceDiscoveryManager
import de.kishorrana.signalboy_android.service.discovery.companion_device.CompanionDeviceManagerFacade
import de.kishorrana.signalboy_android.service.discovery.companion_device.OriginAwareCompanionDeviceManager
import de.kishorrana.signalboy_android.service.discovery.companion_device.ui.AssociateFragment
import de.kishorrana.signalboy_android.service.gatt.*
import de.kishorrana.signalboy_android.service.sync.SyncManager
import de.kishorrana.signalboy_android.util.ContextHelper
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

class SignalboyService : LifecycleService(), ISignalboyService {
    var onConnectionStateUpdateListener: OnConnectionStateUpdateListener? = null
    override val state: State
        get() = latestState.value

    val hasUserInteractionRequest get() = connectionSupervisingManager.hasAnyOpenUserInteractionRequest

    private val _latestState = MutableStateFlow<State>(State.Disconnected(null))
    override val latestState: StateFlow<State> by lazy {
        _latestState.asStateFlow()
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
    }

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

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var configuration: Configuration

    private val client by lazy {
        DefaultClient(
            this,
            getDefaultAdapter(this),
            parentJob = scope.coroutineContext.job
        )
    }
    private val syncManager by lazy { SyncManager() }
    private val connectionSupervisingManager by lazy {
        ConnectionSupervisingManager(this)
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

    override suspend fun connectToPeripheral() = connectToPeripheral(
        ConnectionStrategy.DefaultConnectionStrategy(
            bluetoothAdapter,
            makeCompanionDeviceManagerFacade(this)
        )
    )

    override suspend fun connectToPeripheral(
        context: Activity,
        userInteractionProxy: ActivityResultProxy
    ) = this.connectToPeripheral(
        ConnectionStrategy.UserInteractionConnectionStrategy(
            bluetoothAdapter,
            makeCompanionDeviceManagerFacade(context),
            userInteractionProxy
        )
    )

    // Disconnects gracefully
    override suspend fun disconnectFromPeripheral() = disconnectFromPeripheralAsync(null)

    fun tryDisconnectFromPeripheral() {
        scope.launch {
            try {
                disconnectFromPeripheral()
            } catch (exception: Exception) {
                Log.v(TAG, "tryDisconnectFromPeripheral() - Caught exception:", exception)
            }
        }
    }

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
        } catch (exception: Exception) {
            Log.e(TAG, "tryTriggerSync() - Failed to trigger sync due to exception:", exception)
            return false
        }
        return true
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

    /**
     * Resolve user interaction request
     *
     * @param activity Activity used for resolving Companion Device Manager with a Context of
       type `Activity` (as detailed by [OriginAwareCompanionDeviceManager.ensureCanAssociate]).
     * @param userInteractionProxy
     * @return
     */
    suspend fun resolveUserInteractionRequest(
        activity: Activity,
        userInteractionProxy: ActivityResultProxy
    ): Result<Unit> = connectionSupervisingManager.resolveUserInteractionRequest(
        activity,
        userInteractionProxy
    )

    private suspend fun connectToPeripheral(strategy: ConnectionStrategy) = coroutineScope {
        Log.v(
            TAG,
            "_connectToPeripheral(strategy=$strategy) – I'm working in thread: " +
                    Thread.currentThread().name
        )
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
                val getRejectRequestAddresses = {
                    rejectRequests
                        .filter { (_, rejectRequest) -> rejectRequest.isValid() }
                        .map { (address, _) -> address }
                }

                // TODO: Restore scanner-functionality
//                val devices = Scanner(bluetoothAdapter).discoverPeripherals(
//                    rejectRequestAddresses,
//                    SignalboyGattAttributes.OUTPUT_SERVICE_UUID,
//                    SCAN_PERIOD_IN_MILLIS,
//                    true
//                )
//                Log.d(TAG, "Discovered devices (matching set filters): $devices")
//
//                val device = devices.firstOrNull() ?: throw NoCompatiblePeripheralDiscovered(
//                    "Cannot connect: No peripheral matching set filters found during scan."
//                )

                // DeviceDiscoveryManager will try to discover the Signalboy device:
                // As a side-effect (on success) the client passed for discovery will also
                // already be connected to the discovered device.
                val device = with(strategy.makeDeviceDiscoveryManager()) {
                    val discoveryFilter = DeviceDiscoveryManager.DiscoveryFilter.Builder()
                        .setAdvertisedServiceUuid(SignalboyGattAttributes.OUTPUT_SERVICE_UUID)
                        .setAddressBlacklistGetter(getRejectRequestAddresses)
                        .setGattSignature(SignalboyGattAttributes.allServices)
                        .build()
                    discoverAndConnect(client, discoveryFilter)
                }

                // Client is connected.
                val deviceInformation = SignalboyDeviceInformation(
                    device.getNameOrDefault(""),
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
                client.disconnect()
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
                    (client.state as ClientState.Connected)
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
    private fun CoroutineScope.launchConnectionSupervising(): Job = launch {
        connectionSupervisingManager.superviseConnection()
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.getNameOrDefault(defaultValue: String) = runCatching { name }
        .getOrDefault(defaultValue)

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

    private fun makeCompanionDeviceManagerFacade(context: Context) = CompanionDeviceManagerFacade(
        context.packageManager,
        bluetoothAdapter,
        OriginAwareCompanionDeviceManager.instantiate(context)
    )
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

    private sealed class ConnectionStrategy {
        abstract fun makeDeviceDiscoveryManager(): DeviceDiscoveryManager

        /**
         * A connection strategy, that tries to discover and connect to the Signalboy-Peripheral
         * without requiring user-interaction (headless-mode).
         */
        data class DefaultConnectionStrategy(
            val bluetoothAdapter: BluetoothAdapter,
            val companionDeviceManagerFacade: CompanionDeviceManagerFacade
        ) : ConnectionStrategy() {
            override fun makeDeviceDiscoveryManager() = DeviceDiscoveryManager(
                bluetoothAdapter,
                companionDeviceManagerFacade,
                null
            )
        }

        /**
         * A connection strategy, that may trigger user-interaction (e.g. device selection dialog,
         * or other activities) when required.
         */
        data class UserInteractionConnectionStrategy(
            val bluetoothAdapter: BluetoothAdapter,
            val companionDeviceManagerFacade: CompanionDeviceManagerFacade,
            val activityResultProxy: ActivityResultProxy
        ) : ConnectionStrategy() {
            override fun makeDeviceDiscoveryManager() = DeviceDiscoveryManager(
                bluetoothAdapter,
                companionDeviceManagerFacade,
                activityResultProxy
            )
        }
    }

    companion object {
        private const val TAG = "SignalboyService"
        const val EXTRA_CONFIGURATION = "EXTRA_CONFIGURATION"
        const val TAG_FRAGMENT_ASSOCIATE = "FRAGMENT_ASSOCIATE"

        private val applicableDeviceDiscoveryStrategies
            // TODO: Implement flow, that decides whether to return with CompanionDevice- or Scanner-
            //   DiscoveryStrategy, or even both.
            get() = listOf(
//                SignalboyPrerequisitesHelper.DeviceDiscoveryStrategy.ScanDeviceDiscoveryStrategy,
                SignalboyPrerequisitesHelper.DeviceDiscoveryStrategy.CompanionDeviceDiscoveryStrategy,
            )

        @JvmStatic
        fun getDefaultAdapter(context: Context): BluetoothAdapter =
            ContextHelper.getBluetoothAdapter(context)

        @JvmStatic
        fun verifyPrerequisites(
            context: Context,
            bluetoothAdapter: BluetoothAdapter
        ): SignalboyPrerequisitesHelper.PrerequisitesResult =
            SignalboyPrerequisitesHelper.verifyPrerequisites(
                context,
                bluetoothAdapter,
                applicableDeviceDiscoveryStrategies
            )

        @JvmStatic
        fun injectAssociateFragment(fragmentManager: FragmentManager): AssociateFragment {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "Must be run on Main-thread."
            }

            val fragment = AssociateFragment()
            fragmentManager
                .beginTransaction()
                .add(fragment, TAG_FRAGMENT_ASSOCIATE)
                .commit()
            return fragment
        }
    }
}
