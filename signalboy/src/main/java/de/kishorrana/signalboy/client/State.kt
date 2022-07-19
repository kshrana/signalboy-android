package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.Job

internal sealed class State {
    data class Disconnected(val cause: Throwable?) : State()

    data class Connecting(
        val retryCount: Int,
        val maxRetryCount: Int,
        val timeoutTimer: Job,
        override val session: Session
    ) : State(), InitiatedState

    data class Connected(
        val services: List<BluetoothGattService>,
        override val session: Session
    ) : State(), InitiatedState

    interface InitiatedState {
        val session: Session
    }
}

internal sealed class Event {
    data class OnGattConnectionStateChange(val newState: Int, val status: Int) : Event()

    data class OnConnectionRequested(val device: BluetoothDevice, val retryCount: Int) : Event()

    object OnConnectionAttemptTimeout : Event()

    object OnDisconnectRequested : Event()

    data class OnGattServicesDiscovered(val services: Result<List<BluetoothGattService>>) : Event()
}

internal sealed class SideEffect
