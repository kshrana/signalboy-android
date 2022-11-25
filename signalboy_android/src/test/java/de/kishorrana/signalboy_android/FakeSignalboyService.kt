package de.kishorrana.signalboy_android

import android.app.Activity
import de.kishorrana.signalboy_android.service.ISignalboyService
import de.kishorrana.signalboy_android.service.ISignalboyService.State
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSignalboyService : ISignalboyService {
    override val state: State
        get() = latestState.value

    private val _latestState =
        MutableStateFlow<State>(State.Disconnected(null))
    override val latestState: StateFlow<State> = _latestState.asStateFlow()

    var lastConnectRequest: ConnectRequest? = null
        private set

    override suspend fun connectToPeripheral() = simulateConnectToPeripheral(false)

    override suspend fun connectToPeripheral(
        context: Activity,
        userInteractionProxy: ActivityResultProxy
    ) = simulateConnectToPeripheral(true)

    override suspend fun disconnectFromPeripheral() {
        throw RuntimeException("Stub!")
    }

    private suspend fun simulateConnectToPeripheral(
        hasReceivedAllDependenciesForUserInteraction: Boolean
    ) {
        lastConnectRequest?.let {
            check(it.isResolved) { "A previous Connect-Request is still pending." }
        }
        val lastConnectRequest = ConnectRequest(hasReceivedAllDependenciesForUserInteraction)
            .also { lastConnectRequest = it }

        val connectedState = try {
            lastConnectRequest.awaitResolving()
        } catch (error: Throwable) {
            _latestState.value = State.Disconnected(error)
            throw error
        }
        _latestState.value = connectedState
    }

    class ConnectRequest(
        val hasReceivedAllDependenciesForUserInteraction: Boolean
    ) {
        val isResolved get() = deferredConnectedState.isCompleted
        private val deferredConnectedState = CompletableDeferred<State.Connected>()

        suspend fun awaitResolving() = deferredConnectedState.await()

        fun resolve(result: Result<State.Connected>) {
            val connectedState = try {
                result.getOrThrow()
            } catch (exception: Throwable) {
                deferredConnectedState.completeExceptionally(exception)
                return
            }

            check(deferredConnectedState.complete(connectedState)) {
                "ConnectRequest was resolved before" +
                        " (deferredConnectedState=${deferredConnectedState})."
            }
        }
    }
}
