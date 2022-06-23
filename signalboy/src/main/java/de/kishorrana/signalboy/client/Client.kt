package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.tinder.StateMachine
import de.kishorrana.signalboy.CONNECTION_ATTEMPT_TIMEOUT_IN_MILLIS
import de.kishorrana.signalboy.GATT_STATUS_CONNECTION_TIMEOUT
import de.kishorrana.signalboy.GATT_STATUS_SUCCESS
import de.kishorrana.signalboy.MissingRequiredPermissionsException
import de.kishorrana.signalboy.client.Event.*
import de.kishorrana.signalboy.client.State.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "SignalboyClient"
private typealias VoidCallback = () -> Unit

internal class Client(val context: Context) {
    val state: State
        get() = stateManager.state
    val latestState: StateFlow<State>
        get() = stateManager.latestState

    private val stateManager = StateManager()
    private val gattServicesCache = GattServicesCache()

    fun destroy() {
        stateManager.destroy()
    }

    suspend fun connectAsync(device: BluetoothDevice, retryCount: Int = 3): Boolean {
        connect(device, retryCount)

        // await connect result
        return stateManager.latestState
            .mapNotNull { state ->
                when (state) {
                    is Disconnected -> {
                        state.cause?.let { throw it }   // Disconnected due to connection error
                            ?: false    // Disconnected gracefully (by user-request?)
                    }
                    is Connecting, is DiscoveringServices -> null
                    is Connected -> true
                }
            }
            .first()
    }

    suspend fun disconnectAsync() {
        disconnect().also {
            // await disconnect
            stateManager.latestState
                .takeWhile { it !is Disconnected }
                .collect()
        }
    }

    fun connect(device: BluetoothDevice, retryCount: Int = 3) {
        stateManager.handleEvent(OnConnectionRequested(device, retryCount))
    }

    fun disconnect() {
        stateManager.handleEvent(OnDisconnectRequested)
    }

    private inner class StateManager {
        val state: State
            get() = stateMachine.state

        val latestState: StateFlow<State>
        private val _latestState =
            MutableStateFlow<State>(Disconnected(null))

        private val scope = CoroutineScope(Dispatchers.Default)

        init {
            latestState = _latestState.asStateFlow()
        }

        private val stateMachine =
            StateMachine.create<State, Event, SideEffect> {
                initialState(Disconnected(null))
                state<Disconnected> {
                    on<OnConnectionRequested> {
                        val session = connect(it.device)
                        val timeoutTimer = startConnectionAttemptTimeoutTimer()
                        transitionTo(Connecting(0, it.retryCount, timeoutTimer, session))
                    }
                }
                state<Connecting> {
                    onExit { cancelTimeoutTimer() }
                    on<OnConnectionAttemptTimeout> {
                        if (isAnyRetryRemaining()) {
                            transitionTo(discardAndMakeNextConnectingState())
                        } else {
                            transitionTo(
                                discardAndMakeDisconnectedState(
                                    disconnectCause = NoConnectionAttemptsLeftException()
                                )
                            )
                        }
                    }
                    on<OnGattConnectionStateChange> {
                        val disconnectReason = it.getDisconnectReason()
                        when {
                            disconnectReason != null -> {
                                when (disconnectReason) {
                                    is DisconnectReason.Graceful -> {
                                        transitionTo(
                                            discardAndMakeDisconnectedState(disconnectCause = null)
                                        )
                                    }
                                    is DisconnectReason.ConnectionError -> {
                                        if (isAnyRetryRemaining()) {
                                            transitionTo(discardAndMakeNextConnectingState())
                                        } else {
                                            transitionTo(
                                                discardAndMakeDisconnectedState(
                                                    disconnectCause = NoConnectionAttemptsLeftException()
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            it.isConnectionEstablished() -> {
                                val cachedServices =
                                    gattServicesCache.getServices(session.device.address)
                                when {
                                    cachedServices != null -> {
                                        transitionTo(Connected(cachedServices, session))
                                    }
                                    else -> {
                                        try {
                                            discoverServices()
                                        } catch (err: Throwable) {
                                            Log.e(
                                                TAG,
                                                "Failed to start service discovery. Will disconnect..." +
                                                        "Error:",
                                                err
                                            )
                                            return@on transitionTo(
                                                discardAndMakeDisconnectedState(disconnectCause = err)
                                            )
                                        }
                                        transitionTo(DiscoveringServices(session))
                                    }
                                }
                            }
                            else -> dontTransition()
                        }
                    }
                    on<OnDisconnectRequested> {
                        transitionTo(discardAndMakeDisconnectedState(disconnectCause = null))
                    }
                }
                state<DiscoveringServices> {
                    on<OnGattServicesDiscovered> {
                        val (services) = it

                        gattServicesCache.setServices(services, session.device.address)
                        transitionTo(Connected(services, session))
                    }
                    on<OnDisconnectRequested> {
                        transitionTo(discardAndMakeDisconnectedState(disconnectCause = null))
                    }
                    on<OnGattConnectionStateChange> {
                        when (val disconnectReason = it.getDisconnectReason()) {
                            null -> dontTransition()
                            is DisconnectReason.Graceful -> {
                                transitionTo(
                                    discardAndMakeDisconnectedState(disconnectCause = null)
                                )
                            }
                            is DisconnectReason.ConnectionError -> {
                                transitionTo(
                                    discardAndMakeDisconnectedState(disconnectCause = disconnectReason.error)
                                )
                            }
                        }
                    }
                }
                state<Connected> {
                    on<OnDisconnectRequested> {
                        transitionTo(discardAndMakeDisconnectedState(disconnectCause = null))
                    }
                    on<OnGattConnectionStateChange> {
                        when (val disconnectReason = it.getDisconnectReason()) {
                            null -> dontTransition()
                            is DisconnectReason.Graceful -> {
                                transitionTo(Disconnected(null)).also { closeConnection() }
                            }
                            is DisconnectReason.ConnectionError -> {
                                transitionTo(Disconnected(disconnectReason.error)).also { closeConnection() }
                            }
                        }
                    }
                }
                onTransition {
                    val validTransition =
                        it as? StateMachine.Transition.Valid ?: return@onTransition

                    if (validTransition.sideEffect != null) {
                        throw NotImplementedError() // No side-effects implemented yet
                    }

                    if (validTransition.fromState != validTransition.toState) {
                        _latestState.value = state
                        Log.d(TAG, "onTransition - state=$state")
                    }
                }
            }

        fun destroy() {
            scope.cancel("Parent Client-instance will be destroyed.")
        }

        fun handleEvent(event: Event) {
            // Log.v(TAG, "handleEvent: I'm working in thread ${Thread.currentThread().name}")
            stateMachine.transition(event)  // StateMachine.transition() seems to be thread-safe
        }

        private fun connect(device: BluetoothDevice): Session {
            try {
                val gatt = device.connectGatt(
                    context,
                    false,
                    ClientBluetoothGattCallback()
                )
                return Session(device, gatt)

            } catch (err: SecurityException) {
                throw MissingRequiredPermissionsException(err)
            }
        }

        private fun Connecting.isAnyRetryRemaining() = retryCount + 1 < maxRetryCount

        private fun Connecting.discardAndMakeNextConnectingState(): Connecting {
            closeConnection()
            val newTimeoutTimer = startConnectionAttemptTimeoutTimer()
            val newSession = connect(session.device)

            return Connecting(retryCount + 1, maxRetryCount, newTimeoutTimer, newSession)
        }

        private fun InitiatedState.discardAndMakeDisconnectedState(
            disconnectCause: Throwable?
        ): Disconnected {
            closeConnection()
            return Disconnected(disconnectCause)
        }

        /**
         * Calls `BluetoothGatt.discoverServices()` and handles exceptions that may be
         * thrown when starting to discover services.
         *
         * NOTE: This is an asynchronous operation. Once service discovery is completed,
         * the BluetoothGattCallback.onServicesDiscovered callback is triggered.
         *
         */
        private fun InitiatedState.discoverServices() {
            try {
                if (!session.bluetoothGatt.discoverServices())
                    throw IllegalStateException(
                        "BluetoothGatt-Client failed to start to discover services."
                    )

            } catch (err: SecurityException) {
                throw MissingRequiredPermissionsException(err)
            }
        }

        private fun InitiatedState.closeConnection() {
            try {
                session.bluetoothGatt.close()
            } catch (err: SecurityException) {
                /* no-op */
            }
        }

        private fun Connecting.cancelTimeoutTimer() {
            timeoutTimer.cancel()
        }

        /**
         * Parses and returns Disconnect-Reason if gatt-connection is terminated
         * ([BluetoothProfile.STATE_DISCONNECTED]) or null when the gatt-connection is active (either:
         * [BluetoothProfile.STATE_CONNECTING], [BluetoothProfile.STATE_CONNECTED] or
         * [BluetoothProfile.STATE_DISCONNECTED]).
         *
         * @return Disconnect-Reason if gatt-connection is terminated or null when the
         * gatt-connection is active.
         */
        private fun OnGattConnectionStateChange.getDisconnectReason(): DisconnectReason? =
            when (newState) {
                BluetoothProfile.STATE_DISCONNECTED -> {
                    when (status) {
                        GATT_STATUS_SUCCESS -> DisconnectReason.Graceful
                        GATT_STATUS_CONNECTION_TIMEOUT -> {
                            DisconnectReason.ConnectionError(ConnectionTimeoutException())
                        }
                        else -> {
                            val statusCodeString = "0x%02x".format(status)
                            val disconnectCause = Exception(
                                "GATT connection terminated with unknown status-code " +
                                        "($statusCodeString)."
                            )
                            DisconnectReason.ConnectionError(disconnectCause)
                        }
                    }
                }
                else -> null
            }

        private fun OnGattConnectionStateChange.isConnectionEstablished(): Boolean =
            newState >= BluetoothProfile.STATE_CONNECTED

        private fun startConnectionAttemptTimeoutTimer(): Job =
            setTimer(CONNECTION_ATTEMPT_TIMEOUT_IN_MILLIS) {
                handleEvent(OnConnectionAttemptTimeout)
            }

        private fun setTimer(timeMillis: Long, onTimeout: VoidCallback): Job = scope.launch {
            try {
                withTimeout(timeMillis) {
                    delay(Long.MAX_VALUE)   // wait for a long time...
                }
            } catch (err: CancellationException) {
                onTimeout()
            }
        }

        private inner class ClientBluetoothGattCallback : BluetoothGattCallback() {
            @Suppress("NAME_SHADOWING")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt?,
                status: Int,
                newState: Int
            ) {
                val loggingHandler: (tag: String?, msg: String) -> Int =
                    if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
                loggingHandler(
                    TAG, "onConnectionStateChange() - " +
                            "gatt=$gatt status=$status newState=$newState"
                )

                handleEvent(OnGattConnectionStateChange(newState, status))
            }

            @Suppress("NAME_SHADOWING")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                val gatt = gatt ?: throw IllegalArgumentException("`gatt` must not be null.")

                val loggingHandler: (tag: String?, msg: String) -> Int =
                    if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
                loggingHandler(TAG, "onServicesDiscovered - status=$status")

                if (status != GATT_STATUS_SUCCESS) {
                    val statusCodeString = "0x%02x".format(status)
                    Log.w(TAG, "Failed to retrieve services - statusCode=$statusCodeString")
                    return
                }

                handleEvent(OnGattServicesDiscovered(gatt.services))
            }
        }
    }
}

private sealed class DisconnectReason {
    object Graceful : DisconnectReason()
    data class ConnectionError(val error: Throwable) : DisconnectReason()
}
