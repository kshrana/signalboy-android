package de.kishorrana.signalboy_android.signalboyservice

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import de.kishorrana.signalboy_android.*
import de.kishorrana.signalboy_android.client.Client
import de.kishorrana.signalboy_android.client.endpoint.readGattCharacteristicAsync
import de.kishorrana.signalboy_android.client.endpoint.startNotifyAsync
import de.kishorrana.signalboy_android.client.endpoint.writeGattCharacteristicAsync
import de.kishorrana.signalboy_android.client.util.hasAllSignalboyGattAttributes
import de.kishorrana.signalboy_android.gatt.*
import de.kishorrana.signalboy_android.scanner.Scanner
import de.kishorrana.signalboy_android.sync.SyncService
import de.kishorrana.signalboy_android.util.fromByteArrayLE
import de.kishorrana.signalboy_android.util.now
import de.kishorrana.signalboy_android.util.toByteArrayLE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.StandardCharsets
import kotlin.coroutines.CoroutineContext
import de.kishorrana.signalboy_android.client.State as ClientState
import de.kishorrana.signalboy_android.sync.State as SyncState

private const val TAG = "SignalboyService"

class SignalboyService internal constructor(
    // Reimplementing `SignalboyService` as an Android Bound Service would
    // make capturing the Context unnecessary.
    //
    // s. Android SDK documentation for a reference implementation:
    // https://developer.android.com/guide/topics/connectivity/bluetooth/connect-gatt-server#setup-bound-service
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    /**
     * The fixed-delay that the resulting signals emitted by the Signalboy-device will be delayed.
     *
     * DISCUSSION: This delay is utilized to normalize the delay caused by network-latency (Bluetooth).
     * In order to produce the actual event-times, the (third-party) receiving system will have
     * to subtract the specified Normalization-Delay from the timestamps of the received
     * electronic TTL-events.
     */
    private val normalizationDelay: Long
) {
    val state: State
        get() = latestState.value

    // Could be replaced by making use of `LifecycleScope` (s. [1])
    // when class would subclass an Android Lifecycle Class,
    // e.g. when implemented as a Bound Service.
    //
    // [1]: https://developer.android.com/topic/libraries/architecture/coroutines#lifecyclescope
    private val scope: CoroutineScope

    init {
        val exceptionHandler = CoroutineExceptionHandler { context, err ->
            Log.e(TAG, "Catching uncaught exception - context=$context error:", err)
        }
        scope = CoroutineScope(
            Dispatchers.Default +
                    SupervisorJob() +
                    exceptionHandler +
                    CoroutineName("SignalboyService")
        )
    }

    private val client by lazy { Client(context, parentJob = scope.coroutineContext.job) }
    private val syncService by lazy { SyncService() }

    private val _latestState =
        MutableStateFlow<State>(State.Disconnected())
    val latestState = _latestState.asStateFlow()

    /// Key: Address of Peripheral that requested connection-reject
    private val rejectRequests = mutableMapOf<String, RejectRequest>()

    // Will be `true`, if establishing a connection has succeeded.
    private var connecting: Deferred<SignalboyDeviceInformation>? = null
    private var clientStateObserving: Job? = null
    private var connectionOptionsSubscription:
            Client.NotificationSubscription.CancellationToken? = null

    private var syncServiceCoroutineContext: CoroutineContext? = null
    private var syncServiceRestarting: Job? = null

    fun destroy() {
        client.destroy()
        scope.cancel("Parent SignalboyService-instance will be destroyed.")
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
            syncService.debugTriggerSync()
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
                    (client.state as? ClientState.Connected)?.let(::ensureHasSignalboyGattAttributes)
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
                            .onEach { (it as? ClientState.Connected)?.let(::ensureHasSignalboyGattAttributes) }
                            .combine(syncService.latestState) { clientState, syncState ->
                                makeState(clientState, syncState, deviceInformation)
                            }
                            .collect { _latestState.value = it }
                    } catch (err: GattClientIsMissingAttributesException) {
                        Log.d(TAG, "clientStateObserving: caught error:", err)
                        scope.launch { disconnectFromPeripheralAsync(err) }
                    }
                }
                Log.i(TAG, "Successfully connected to peripheral.")

                startSyncService()

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

    suspend fun sendEvent() {
        val firedateTimestamp = now() + normalizationDelay

        val isSynced = when (val state = state) {
            is State.Connected -> state.isSynced
            else -> return
        }

        if (isSynced) {
            val data = firedateTimestamp.toUInt().toByteArrayLE()
            client.writeGattCharacteristicAsync(TargetTimestampCharacteristic, data, false)
        } else {
            // Fallback to unsynced method.
            Log.w(
                TAG, "Falling back to unsynced signaling of event as connected peripheral is " +
                        "not synced. Timing will be inaccurate."
            )
            delay(firedateTimestamp - now())
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
            stopSyncService()
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
    private fun ensureHasSignalboyGattAttributes(connectedState: ClientState.Connected) {
        if (!connectedState.services.hasAllSignalboyGattAttributes())
            throw GattClientIsMissingAttributesException()
    }

    //region Helper
    private fun CoroutineScope.launchSyncService(client: Client, attempt: Int = 1) {
        Log.v(TAG, "Launching SyncService (attempt=$attempt).")
        if (syncServiceCoroutineContext?.isActive == true) {
            throw IllegalStateException("Sync Service is already running.")
        }

        val childJob = Job(coroutineContext.job) + CoroutineName("SyncService")
        val exceptionHandler = CoroutineExceptionHandler { _, err ->
            Log.e(TAG, "SyncService (job=$childJob) failed with error:", err)
            // Stop service before next startup attempt.
            stopSyncService()
            childJob.cancel()

            syncServiceRestarting?.cancel()
            syncServiceRestarting = launch {
                // Backoff strategy
                val delayMillis: Long = when (attempt) {
                    1 -> 3 * 1_000L
                    2 -> 10 * 1_000L
                    3 -> 30 * 1_000L
                    else -> 3 * MIN_IN_MILLISECONDS
                }
                Log.i(
                    TAG, "Next attempt to start SyncService in ${delayMillis / 1_000}s " +
                            "(backoff-strategy)..."
                )
                delay(delayMillis)

                // Retry
                scope.launchSyncService(client, attempt = attempt + 1)
            }
        }

        val context = coroutineContext + childJob + exceptionHandler
        syncServiceCoroutineContext = context
        syncService.attach(context, client)
    }

    private fun handleConnectionOptionsCharacteristic(value: ByteArray) {
        val connectionOptionsRawValue = Int.fromByteArrayLE(value).also {
            Log.v(TAG, "handleConnectionOptionsCharacteristic() - connectionOptions=$it")
        }
        val connectionOptions = SignalboyConnectionOptions(connectionOptionsRawValue)

        if (connectionOptions.hasRejectRequest) {
            if (client.state is ClientState.Connected) {
                val address =
                    (client.state as de.kishorrana.signalboy_android.client.State.Connected)
                        .session.device.address
                rejectRequests[address] = RejectRequest(address, now())

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

    private fun startSyncService() {
        scope.launchSyncService(client)
    }

    private fun stopSyncService() {
        syncService.detach()
        syncServiceCoroutineContext?.cancel()
        syncServiceRestarting?.cancel()
    }

    /// Returns `true`, if the request is valid (i.e. has not expired).
    private fun RejectRequest.isValid() =
        now() - receivedTime < REJECT_CONNECTION_DURATION_IN_MILLIS
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

    sealed interface State {
        data class Disconnected(val cause: Throwable? = null) : State
        object Connecting : State
        data class Connected(
            val deviceInformation: SignalboyDeviceInformation,
            val isSynced: Boolean
        ) : State
    }
}
