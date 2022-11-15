package de.kishorrana.signalboy_android

import android.app.Activity
import de.kishorrana.signalboy_android.service.SignalboyMediator
import de.kishorrana.signalboy_android.service.SignalboyMediator.State
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSignalboyMediator : SignalboyMediator {
    override val state: State
        get() = latestState.value

    private val _latestState =
        MutableStateFlow<State>(State.Disconnected(null))
    override val latestState: StateFlow<State> = _latestState.asStateFlow()

    var connectRequestResolver: ((Result<State.Connected>) -> Unit)? = null
        private set

    override suspend fun connectToPeripheral() {
        val deferredState = CompletableDeferred<State.Connected>()
        connectRequestResolver = makeConnectRequestResolver(deferredState) {
            connectRequestResolver = null
        }

        val connectedState = try {
            deferredState.await()
        } catch (error: Throwable) {
            _latestState.value = State.Disconnected(error)
            throw error
        }
        _latestState.value = connectedState
    }

    override suspend fun connectToPeripheral(
        context: Activity,
        userInteractionProxy: ActivityResultProxy
    ) {
        val deferredState = CompletableDeferred<State.Connected>()
        connectRequestResolver = makeConnectRequestResolver(deferredState) {
            connectRequestResolver = null
        }

        val connectedState = try {
            deferredState.await()
        } catch (error: Throwable) {
            _latestState.value = State.Disconnected(error)
            throw error
        }
        _latestState.value = connectedState
    }

    override suspend fun disconnectFromPeripheral() {
        throw RuntimeException("Stub!")
    }

    companion object {
        private fun makeConnectRequestResolver(
            deferredState: CompletableDeferred<State.Connected>,
            onFinish: () -> Unit
        ): (Result<State.Connected>) -> Unit =
            {
                try {
                    deferredState.complete(it.getOrThrow())
                } catch (throwable: Throwable) {
                    deferredState.completeExceptionally(throwable)
                } finally {
                    onFinish()
                }
            }
    }
}
