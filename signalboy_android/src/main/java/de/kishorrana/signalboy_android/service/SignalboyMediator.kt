package de.kishorrana.signalboy_android.service

import android.app.Activity
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import de.kishorrana.signalboy_android.service.gatt.SignalboyDeviceInformation
import kotlinx.coroutines.flow.StateFlow

interface SignalboyMediator {
    val state: State
    val latestState: StateFlow<State>

    suspend fun connectToPeripheral()
    suspend fun connectToPeripheral(context: Activity, userInteractionProxy: ActivityResultProxy)

    // Disconnects gracefully
    suspend fun disconnectFromPeripheral()

    sealed interface State {
        data class Disconnected(val cause: Throwable?) : State
        object Connecting : State
        data class Connected(
            val deviceInformation: SignalboyDeviceInformation,
            val isSynced: Boolean
        ) : State
    }
}