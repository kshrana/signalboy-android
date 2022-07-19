package de.kishorrana.signalboy.signalboyservice

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import de.kishorrana.signalboy.*
import de.kishorrana.signalboy.client.Client
import de.kishorrana.signalboy.client.State
import de.kishorrana.signalboy.client.util.hasAllSignalboyGattAttributes
import de.kishorrana.signalboy.gatt.SignalboyGattAttributes
import de.kishorrana.signalboy.scanner.Scanner
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
    val connectionState: ConnectionState
        get() = latestConnectionState.value

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

    private val _latestConnectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val latestConnectionState = _latestConnectionState.asStateFlow()

    // Will be `true`, if establishing a connection has succeeded.
    private var connecting: Deferred<Boolean>? = null
    private var clientStateObserving: Job? = null

    private var syncServiceCoroutineContext: CoroutineContext? = null
    private var syncServiceRestarting: Job? = null

    fun destroy() {
        client.destroy()
        scope.cancel("Parent SignalboyService-instance will be destroyed.")
    }

    fun tryConnectToPeripheral() {
        scope.launch {
            tryOrPublishFailedConnectionState { connectToPeripheralAsync() }
        }
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

    private suspend fun connectToPeripheralAsync() = coroutineScope {
        Log.v(TAG, "connectToPeripheralAsync: I'm working in thread ${Thread.currentThread().name}")
        if (connecting?.isActive == true) {
            throw AlreadyConnectingException()
        }

        val connecting = async {
            _latestConnectionState.value = ConnectionState.Connecting

            // Try to discover Peripheral.
            val device: BluetoothDevice
            try {
                val devices = Scanner(bluetoothAdapter).discoverPeripherals(
                    SignalboyGattAttributes.OUTPUT_SERVICE_UUID,
                    SCAN_PERIOD_IN_MILLIS,
                    true
                )
                Log.d(TAG, "Discovered devices (which match set filters): $devices")

                device = devices
                    .firstOrNull() ?: throw NoCompatiblePeripheralDiscovered(
                    "Cannot connect: No peripheral matching set filters found during scan."
                )
            } catch (err: Throwable) {
                throw err
            }

            // Try connecting to Peripheral.
            // But first setup observer for Client's connection-state and forward any events to
            // `self`'s `latestConnectionState`-Publisher.
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
                        .dropWhile { it is State.Disconnected }
                        .onEach { (it as? State.Connected)?.let(::ensureHasSignalboyGattAttributes) }
                        .map(::convertFromClientState)
                        .collect { _latestConnectionState.value = it }
                } catch (err: GattClientIsMissingAttributesException) {
                    Log.d(TAG, "clientStateObserving: caught error:", err)
                    disconnectFromPeripheralAsync(err)
                    // TODO: Or should rather start another attempt to connect automatically?
                }
            }

            try {
                client.connectAsync(device).also {
                    (client.state as? State.Connected)?.let(::ensureHasSignalboyGattAttributes)
                }
            } catch (err: Throwable) {
                disconnectFromPeripheralAsync(err)
                throw err
            }
                .also {
                    if (it)
                        Log.i(TAG, "Successfully connected to peripheral.")
                    else
                        Log.i(TAG, "Failed to connect to peripheral.")
                }
        }
            .also { connecting = it }

        if (!connecting.await())    // Failed to establish connection.
            return@coroutineScope

        startSyncService()
    }

    private suspend fun disconnectFromPeripheralAsync(disconnectCause: Throwable?) {
        try {
            stopSyncService()
            clientStateObserving?.cancelAndJoin()
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
            withContext(NonCancellable) {
                client.disconnectAsync()
                // Publish
                _latestConnectionState.value = ConnectionState.Disconnected(disconnectCause)
            }
        }
    }

    /**
     * Examines the GATT-client's services and characteristics and throws if expected
     * features are missing.
     *
     */
    private fun ensureHasSignalboyGattAttributes(connectedState: State.Connected) {
        if (!connectedState.services.hasAllSignalboyGattAttributes())
            throw GattClientIsMissingAttributesException()
    }

    //region Helper
    private suspend fun <R> tryOrPublishFailedConnectionState(
        block: suspend () -> R
    ) {
        try {
            block()
        } catch (cancellationError: CancellationException) {
            // Connection attempt was cancelled gracefully.
            _latestConnectionState.value = ConnectionState.Disconnected(null)
        } catch (err: Throwable) {
            _latestConnectionState.value = ConnectionState.Disconnected(err)
        }
    }

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
                    1 -> 5 * 1_000
                    2 -> 30 * 1_000
                    else -> 3 * MILLISECONDS_IN_MIN
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
    private fun convertFromClientState(state: State): ConnectionState = when (state) {
        is State.Disconnected -> ConnectionState.Disconnected(state.cause)
        is State.Connecting -> ConnectionState.Connecting
        is State.Connected -> ConnectionState.Connected
    }
    //endregion

    sealed interface ConnectionState {
        data class Disconnected(val cause: Throwable? = null) : ConnectionState
        object Connecting : ConnectionState
        object Connected : ConnectionState
    }
}
