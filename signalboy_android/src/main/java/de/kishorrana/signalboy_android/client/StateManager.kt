package de.kishorrana.signalboy_android.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.tinder.StateMachine
import de.kishorrana.signalboy_android.CONNECTION_ATTEMPT_TIMEOUT_IN_MILLIS
import de.kishorrana.signalboy_android.gatt.GATT_STATUS_CONNECTION_TIMEOUT
import de.kishorrana.signalboy_android.gatt.GATT_STATUS_SUCCESS
import de.kishorrana.signalboy_android.MissingRequiredRuntimePermissionException
import de.kishorrana.signalboy_android.util.toHexString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ClientStateManager"
private typealias VoidCallback = () -> Unit

internal class StateManager(
    private val context: Context,
    private val bluetoothGattCallback: BluetoothGattCallback,
    private val scope: CoroutineScope
) {
    val state: State
        get() = stateMachine.state

    val latestState: StateFlow<State>
    private val _latestState =
        MutableStateFlow<State>(State.Disconnected(null))

    init {
        latestState = _latestState.asStateFlow()
    }

    private val stateMachine = StateMachine.create<State, Event, SideEffect> {
        initialState(State.Disconnected(null))
        state<State.Disconnected> {
            on<Event.OnConnectionRequested> {
                val session = connect(it.device)
                val timeoutTimer = startConnectionAttemptTimeoutTimer()
                transitionTo(State.Connecting(0, it.retryCount, timeoutTimer, session))
            }
        }
        state<State.Connecting> {
            onExit { cancelTimeoutTimer() }
            on<Event.OnDisconnectRequested> {
                transitionTo(discardAndMakeDisconnectedState(disconnectCause = null))
            }
            on<Event.OnConnectionAttemptTimeout> {
                Log.w(TAG, "Connection attempt failed due to timeout.")

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
            on<Event.OnGattConnectionStateChange> {
                val disconnectReason = it.getDisconnectReason()
                when {
                    disconnectReason != null -> {
                        when (disconnectReason) {
                            is DisconnectReason.Graceful -> {
                                Log.w(
                                    TAG, "Connection attempt failed due to " +
                                            "(user requested) cancellation."
                                )

                                transitionTo(
                                    discardAndMakeDisconnectedState(disconnectCause = null)
                                )
                            }
                            is DisconnectReason.ConnectionError -> {
                                Log.w(
                                    TAG, "Connection attempt failed due to error:",
                                    disconnectReason.error
                                )

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

                        // GATT-client is connected, but we still need to await the GATT
                        // Service-Discovery operation to succeed...
                        dontTransition()
                    }
                    else -> dontTransition()
                }
            }
            on<Event.OnGattServicesDiscovered> {
                val services = try {
                    it.services.getOrThrow()
                } catch (err: ClientBluetoothGattCallback.ReceivedBadStatusCodeException) {
                    Log.w(
                        TAG, "Failed to retrieve services - " +
                                "statusCode=${err.status.toByte().toHexString()}"
                    )
                    return@on transitionTo(
                        discardAndMakeDisconnectedState(
                            disconnectCause = ServiceDiscoveryFailed(
                                session.gattClient
                            )
                        )
                    )
                }

                transitionTo(State.Connected(services, session))
            }
        }
        state<State.Connected> {
            on<Event.OnDisconnectRequested> {
                transitionTo(discardAndMakeDisconnectedState(disconnectCause = null))
            }
            on<Event.OnGattConnectionStateChange> {
                when (val disconnectReason = it.getDisconnectReason()) {
                    null -> dontTransition()
                    is DisconnectReason.Graceful -> {
                        transitionTo(discardAndMakeDisconnectedState(disconnectCause = null))
                    }
                    is DisconnectReason.ConnectionError -> {
                        transitionTo(discardAndMakeDisconnectedState(disconnectCause = disconnectReason.error))
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

    fun handleEvent(event: Event) {
        // Log.v(TAG, "handleEvent: I'm working in thread ${Thread.currentThread().name}")
        stateMachine.transition(event)  // StateMachine.transition() seems to be thread-safe.
    }

    private fun connect(device: BluetoothDevice): Session {
        try {
            val gatt = device.connectGatt(
                context,
                false,
                bluetoothGattCallback
            )
            return Session(device, gatt)

        } catch (err: SecurityException) {
            throw MissingRequiredRuntimePermissionException(err)
        }
    }

    private fun State.Connecting.isAnyRetryRemaining() = retryCount + 1 < maxRetryCount

    private fun State.Connecting.discardAndMakeNextConnectingState(): State.Connecting {
        closeConnection()
        val newTimeoutTimer = startConnectionAttemptTimeoutTimer()
        val newSession = connect(session.device)

        return State.Connecting(retryCount + 1, maxRetryCount, newTimeoutTimer, newSession)
    }

    private fun State.InitiatedState.discardAndMakeDisconnectedState(
        disconnectCause: Throwable?
    ): State.Disconnected {
        closeConnection()
        return State.Disconnected(disconnectCause)
    }

    /**
     * Calls `BluetoothGatt.discoverServices()` and handles exceptions that may be
     * thrown when starting to discover services.
     *
     * NOTE: This is an asynchronous operation. Once service discovery is completed,
     * the BluetoothGattCallback.onServicesDiscovered callback is triggered.
     *
     */
    private fun State.InitiatedState.discoverServices() {
        try {
            if (!session.gattClient.discoverServices())
                throw IllegalStateException(
                    "BluetoothGatt-Client failed to start to discover services."
                )

        } catch (err: SecurityException) {
            throw MissingRequiredRuntimePermissionException(err)
        }
    }

    private fun State.InitiatedState.closeConnection() {
        try {
            session.gattClient.close()
        } catch (err: SecurityException) {
            /* no-op */
        }
    }

    private fun State.Connecting.cancelTimeoutTimer() {
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
    private fun Event.OnGattConnectionStateChange.getDisconnectReason(): DisconnectReason? =
        when (newState) {
            BluetoothProfile.STATE_DISCONNECTED -> {
                when (status) {
                    GATT_STATUS_SUCCESS -> DisconnectReason.Graceful
                    GATT_STATUS_CONNECTION_TIMEOUT -> {
                        DisconnectReason.ConnectionError(ConnectionTimeoutException())
                    }
                    else -> {
                        val disconnectCause = Exception(
                            "GATT connection terminated with unknown status-code " +
                                    "(${status.toByte().toHexString()})."
                        )
                        DisconnectReason.ConnectionError(disconnectCause)
                    }
                }
            }
            else -> null
        }

    private fun Event.OnGattConnectionStateChange.isConnectionEstablished(): Boolean =
        newState >= BluetoothProfile.STATE_CONNECTED

    private fun startConnectionAttemptTimeoutTimer(): Job =
        setTimer(CONNECTION_ATTEMPT_TIMEOUT_IN_MILLIS) {
            handleEvent(Event.OnConnectionAttemptTimeout)
        }

    private fun setTimer(timeMillis: Long, onTimeout: VoidCallback): Job = scope.launch {
        try {
            withTimeout(timeMillis) {
                awaitCancellation()
            }
        } catch (err: TimeoutCancellationException) {
            onTimeout()
        }
    }
}

private sealed class DisconnectReason {
    object Graceful : DisconnectReason()
    data class ConnectionError(val error: Throwable) : DisconnectReason()
}
