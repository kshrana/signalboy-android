package de.kishorrana.signalboy_android.service.discovery.companion_device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.companion.BluetoothLeDeviceFilter
import android.content.pm.PackageManager
import android.util.Log
import com.tinder.StateMachine
import de.kishorrana.signalboy_android.service.BluetoothDisabledException
import de.kishorrana.signalboy_android.service.PrerequisitesNode
import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import de.kishorrana.signalboy_android.service.discovery.DeviceDiscoveryStrategy
import de.kishorrana.signalboy_android.service.discovery.UserInteractionRequiredException
import de.kishorrana.signalboy_android.service.discovery.companion_device.State.Disconnecting.DisconnectReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "CompanionDeviceDiscoveryStrategy"
private const val DISCOVERY_ATTEMPT_TIMEOUT_MS = 10_000L
private const val DISCONNECTING_ATTEMPT_TIMEOUT_MS = 5_000L

private typealias TransitionTo = StateMachine.Graph.State.TransitionTo<State, SideEffect>

internal class CompanionDeviceDiscoveryStrategy(
    private val bluetoothAdapter: BluetoothAdapter,
    private val companionDeviceManagerFacade: CompanionDeviceManagerFacade,
    private val associationRequestDeviceFilter: BluetoothLeDeviceFilter? = null,
    private val addressPredicate: (String) -> Boolean = { true },
    private val servicesPredicate: (List<BluetoothGattService>) -> Boolean = { true }
) : DeviceDiscoveryStrategy {
    override suspend fun discover(client: Client, userInteractionProxy: ActivityResultProxy?) =
        coroutineScope {
            val stateManager = StateManager(this, client, userInteractionProxy)
                .apply { requestDiscovery() }

            val device = stateManager.latestState
                .filterIsInstance<State.Idle>()
                .map { requireNotNull(it.deviceResult).getOrThrow() }
                .first()

            Log.i(
                TAG, "discover() - Returns with Client connected to" +
                        " discovered device (MAC: ${device.address})."
            )
            return@coroutineScope device
        }

    private inner class StateManager(
        private val parentScope: CoroutineScope,
        private val client: Client,
        private val activityResultProxy: ActivityResultProxy?
    ) {
        private val _latestState = MutableStateFlow<State>(State.Idle(null))
        val latestState = _latestState.asStateFlow()

        private val stateMachine = StateMachine.create<State, Event, SideEffect> {
            initialState(State.Idle(null))
            state<State.Idle> {
                on<Event.OnDiscoveryRequest> {
                    require(client.state is de.kishorrana.signalboy_android.service.client.State.Disconnected)

                    // TODO: Might implement our own bookkeeping when establishing associations
                    //   to ensure, that we're only dealing with expected associations here.
                    val address = companionDeviceManagerFacade.associations
                        .firstOrNull { address ->
                            addressPredicate(address).also { predicate ->
                                if (!predicate) {
                                    Log.d(
                                        TAG, "Ignoring association for device" +
                                                " (address=$address) due to rejection by filter."
                                    )
                                }
                            }
                        }
                    if (address != null) {
                        Log.d(
                            TAG,
                            "Found previous Companion-Device association. (address=$address)"
                        )
                        // We'll try to connect first to check that Companion Device is online
                        // and supported.
                        val bluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
                        connectGattAndTransition(bluetoothDevice) { toState ->
                            toState?.let { transitionTo(it) } ?: dontTransition()
                        }
                    } else {
                        Log.d(
                            TAG,
                            "No previous Companion-Device association found" +
                                    " (matching our filters)."
                        )
                        requestAssociationAndTransition { toState ->
                            toState?.let { transitionTo(it) } ?: dontTransition()
                        }
                    }
                }
            }
            state<State.AssociationRequested> {
                onExit { timer.cancel() }
                on<Event.OnDiscoveryRequest> { throw IllegalStateException(state = this) }
                on<Event.OnAssociationPending> {
                    transitionTo(State.AssociationPending)
                }
                on<Event.OnTimeout> {
                    transitionTo(State.Idle(Result.failure(AssociationDiscoveryTimeoutException())))
                }
            }
            state<State.AssociationPending> {
                on<Event.OnDiscoveryRequest> { throw IllegalStateException(state = this) }
                on<Event.OnAssociationFinished> { (result) ->
                    val device = try {
                        result.getOrThrow()
                    } catch (exception: Exception) {
                        return@on transitionTo(State.Idle(Result.failure(exception)))
                    }

                    connectGattAndTransition(device) { toState ->
                        toState?.let { transitionTo(it) } ?: dontTransition()
                    }
                }
            }
            state<State.Connecting> {
                onExit { connecting.cancel() }
                on<Event.OnDiscoveryRequest> { throw IllegalStateException(state = this) }
                on<Event.OnConnectionAttemptFailed> { (reason) ->
                    when (reason) {
                        // Fail early
                        is BluetoothDisabledException -> transitionTo(
                            State.Idle(Result.failure(reason))
                        )
                        else -> requestAssociationAndTransition { toState ->
                            toState?.let { transitionTo(it) } ?: dontTransition()
                        }
                    }
                }
                on<Event.OnConnectionEstablished> { (services) ->
                    if (servicesPredicate(services)) {
                        // Do **not** disconnect. Client is expected to be connected
                        // to discovered device.
                        transitionTo(
                            State.Idle(Result.success(device))
                        )
                    } else {
                        // Candidate has been rejected.
                        Log.w(
                            TAG, "Discarding connected device due to " +
                                    "filter (`servicesPredicate`). (services=$services)"
                        )
                        disconnectAndTransition(DisconnectReason.RejectedDueToServicesFilter) { toState ->
                            toState?.let { transitionTo(it) } ?: dontTransition()
                        }
                    }
                }
                on<Event.OnTimeout> {
                    disconnectAndTransition(DisconnectReason.BadConnectionAttempt) { toState ->
                        toState?.let { transitionTo(it) } ?: dontTransition()
                    }
                }
            }
            state<State.Disconnecting> {
                onExit {
                    timer.cancel()
                    disconnecting.cancel()
                }
                on<Event.OnDisconnectSuccess> {
                    handleDisconnectAndTransition { toState ->
                        toState?.let { transitionTo(it) } ?: dontTransition()
                    }
                }
                on<Event.OnTimeout> {
                    Log.w(
                        TAG, "${this.javaClass.simpleName} – on<Event.OnTimeout>: " +
                                "Timeout occurred during disconnect attempt."
                    )
                    handleDisconnectAndTransition { toState ->
                        toState?.let { transitionTo(it) } ?: dontTransition()
                    }
                }
            }
            onTransition { transition ->
                Log.v(TAG, "onTransition: transition=$transition")
                when (transition) {
                    is StateMachine.Transition.Valid -> {
                        if (transition.sideEffect != null) {
                            throw NotImplementedError() // No side-effects implemented yet
                        }

                        if (transition.fromState != transition.toState) {
                            _latestState.value = transition.toState
                        }
                    }
                    is StateMachine.Transition.Invalid -> {} /* no-op */
                }
            }
        }

        fun requestDiscovery() = handleEvent(Event.OnDiscoveryRequest)

        private fun handleEvent(event: Event) {
            stateMachine.transition(event)  // StateMachine.transition() seems to be thread-safe.
        }

        private fun requestAssociationOrThrow() {
            if (activityResultProxy == null) throw UserInteractionRequiredException()

            // May also throw.
            companionDeviceManagerFacade.requestNewAssociation(
                associationRequestDeviceFilter = associationRequestDeviceFilter,
                userInteractionProxy = activityResultProxy,
                onAssociationPending = {
                    stateMachine.transition(Event.OnAssociationPending).let {
                        // Disallow device selection dialog if state can't handle the
                        // event.
                        it is StateMachine.Transition.Valid
                    }
                },
                onFinish = { handleEvent(Event.OnAssociationFinished(it)) },
                addressPredicate = addressPredicate
            )
        }

        private fun connectGattAndTransition(
            device: BluetoothDevice,
            transitionTo: (State?) -> TransitionTo
        ) = transitionTo(
            State.Connecting(
                device,
                parentScope.launch {
                    val services = try {
                        client.connect(
                            device,
                            retryCount = 1
                        )
                    } catch (exception: Exception) {
                        handleEvent(Event.OnConnectionAttemptFailed(exception))
                        return@launch
                    }
                    handleEvent(Event.OnConnectionEstablished(services))
                }
            )
        )

        private fun requestAssociationAndTransition(transitionTo: (State?) -> TransitionTo) =
            run {
                try {
                    requestAssociationOrThrow()
                } catch (exception: Exception) {
                    return@run transitionTo(State.Idle(Result.failure(exception)))
                }

                transitionTo(
                    State.AssociationRequested(
                        parentScope.launchTimer(DISCOVERY_ATTEMPT_TIMEOUT_MS)
                    )
                )
            }

        private fun State.Connecting.disconnectAndTransition(
            disconnectReason: DisconnectReason,
            transitionTo: (State?) -> TransitionTo
        ) = transitionTo(
            State.Disconnecting(
                device = device,
                timer = parentScope.launchTimer(DISCONNECTING_ATTEMPT_TIMEOUT_MS),
                disconnectReason = disconnectReason,
                disconnecting = parentScope.launch {
                    client.disconnect()
                    handleEvent(Event.OnDisconnectSuccess)
                }
            )
        )

        private fun State.Disconnecting.handleDisconnectAndTransition(
            transitionTo: (State?) -> TransitionTo
        ) = when (disconnectReason) {
            is DisconnectReason.RejectedDueToServicesFilter,
            is DisconnectReason.BadConnectionAttempt ->
                requestAssociationAndTransition(transitionTo)
            is DisconnectReason.Finished ->
                transitionTo(
                    State.Idle(
                        disconnectReason.error?.let { Result.failure(it) }
                            ?: Result.success(device))
                )
        }

        private fun CoroutineScope.launchTimer(timeMillis: Long) = launch {
            try {
                withTimeout(timeMillis) {
                    awaitCancellation()
                }
            } catch (exception: TimeoutCancellationException) {
                // Timer did timeout.
                handleEvent(Event.OnTimeout)
            }
        }
    }

    companion object {
        internal fun asPrerequisitesNode(
            packageManager: PackageManager
        ) = PrerequisitesNode { visitor ->
            CompanionDeviceManagerFacade.asPrerequisitesNode(packageManager).accept(visitor)
        }
    }
}
