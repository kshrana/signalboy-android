package de.kishorrana.signalboy.client

sealed interface ClientState {
    data class Disconnected(val cause: Throwable? = null) : ClientState
    object Connecting : ClientState
    object Connected : ClientState
}

internal sealed interface Event {
    data class OnConnectionClosed(val disconnectCause: Throwable?) : Event
    data class OnBluetoothGattConnectionStateChange(val status: Int, val newState: Int) : Event
    data class OnBluetoothGattServicesDiscovered(val status: Int) : Event
}

internal sealed interface SideEffect {
    object ResetBluetoothState : SideEffect
    object TriggerServiceDiscovery : SideEffect
    object CacheDiscoveredServices : SideEffect
}

// Helper

internal data class Transition(val stateUpdate: ClientState, val sideEffect: SideEffect? = null)
