package de.kishorrana.signalboy.signalboyservice

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import de.kishorrana.signalboy.*
import de.kishorrana.signalboy.client.Client
import de.kishorrana.signalboy.client.State as ClientState
import de.kishorrana.signalboy.client.util.hasAllSignalboyGattAttributes
import de.kishorrana.signalboy.gatt.SignalboyGattAttributes
import de.kishorrana.signalboy.scanner.Scanner
import de.kishorrana.signalboy.sync.State as SyncState
import de.kishorrana.signalboy.sync.SyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

private const val TAG = "SignalboyService"

class SignalboyService internal constructor(
    // Reimplementing `SignalboyService` as an Android Bound Service would
    // make capturing the Context unnecessary.
    //
    // s. Android SDK documentation for a reference implementation:
    // https://developer.android.com/guide/topics/connectivity/bluetooth/connect-gatt-server#setup-bound-service
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
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

    // Will be `true`, if establishing a connection has succeeded.
    private var connecting: Job? = null
    private var clientStateObserving: Job? = null

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
            syncService.triggerSync()
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

        connecting = launch {
            try {
                _latestState.value = State.Connecting

                // Try to discover Peripheral.
                val devices = Scanner(bluetoothAdapter).discoverPeripherals(
                    SignalboyGattAttributes.OUTPUT_SERVICE_UUID,
                    SCAN_PERIOD_IN_MILLIS,
                    true
                )
                Log.d(TAG, "Discovered devices (matching set filters): $devices")

                val device = devices.firstOrNull() ?: throw NoCompatiblePeripheralDiscovered(
                    "Cannot connect: No peripheral matching set filters found during scan."
                )

                // Try connecting to Peripheral.
                // But first setup observer for Client's connection-state and forward any events to
                // `self`'s `latestState`-Publisher.
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
                            // Drop initial "Disconnected"-events as StateFlow emits
                            // initial value on subscribing.
                            .dropWhile { it is ClientState.Disconnected }
                            .onEach { (it as? ClientState.Connected)?.let(::ensureHasSignalboyGattAttributes) }
                            .combine(syncService.latestState, ::makeState)
                            .collect { _latestState.value = it }
                    } catch (err: GattClientIsMissingAttributesException) {
                        Log.d(TAG, "clientStateObserving: caught error:", err)
                        disconnectFromPeripheralAsync(err)
                    }
                }

                client.connectAsync(device).also {
                    (client.state as? ClientState.Connected)?.let(::ensureHasSignalboyGattAttributes)
                }
                Log.i(TAG, "Successfully connected to peripheral.")

                startSyncService()
            } catch (err: Throwable) {
                withContext(NonCancellable) {
                    // Abort connection attempt.
                    stopSyncService()

                    clientStateObserving?.cancelAndJoin()
                    client.disconnectAsync()

                    // Publish
                    val publicDisconnectCause = when (err) {
                        is CancellationException -> null    // Connection attempt was cancelled by user-request.
                        else -> err
                    }
                    _latestState.value = State.Disconnected(publicDisconnectCause)

                    throw err
                }
            }
        }
    }

    private suspend fun disconnectFromPeripheralAsync(disconnectCause: Throwable?) {
        try {
            clientStateObserving?.cancelAndJoin()
            connecting?.cancelAndJoin()
            stopSyncService()
        } catch (cancellation: CancellationException) {
            throw cancellation
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
            client.disconnectAsync()

            // Publish
            _latestState.value = State.Disconnected(disconnectCause)
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
            Log.i(TAG, "SyncService (job=$childJob) failed with error:", err)
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

    private fun startSyncService() {
        scope.launchSyncService(client)
    }

    private fun stopSyncService() {
        syncService.detach()
        syncServiceCoroutineContext?.cancel()
        syncServiceRestarting?.cancel()
    }
    //endregion

    //region Factory
    private fun makeState(connectionState: ClientState, syncState: SyncState): State =
        when (connectionState) {
            is ClientState.Disconnected -> State.Disconnected(connectionState.cause)
            is ClientState.Connecting -> State.Connecting
            is ClientState.Connected -> State.Connected(syncState is SyncState.Synced)
        }
    //endregion

    sealed interface State {
        data class Disconnected(val cause: Throwable? = null) : State
        object Connecting : State
        data class Connected(val isSynced: Boolean) : State
    }
}
