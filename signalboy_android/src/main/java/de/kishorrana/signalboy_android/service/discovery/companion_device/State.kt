package de.kishorrana.signalboy_android.service.discovery.companion_device

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.Job

internal sealed class State {
    data class Idle(val deviceResult: Result<BluetoothDevice>?) : State()

    // After association is requested, when awaiting
    // a response from the CompanionDeviceManager API.
    data class AssociationRequested(val timer: Job) : State()

    // While user is shown device chooser dialog.
    object AssociationPending : State()

    data class Connecting(
        val device: BluetoothDevice,
        val connecting: Job
    ) : State()

    data class Disconnecting(
        val device: BluetoothDevice,
        val disconnectReason: DisconnectReason,
        val timer: Job,
        val disconnecting: Job
    ) : State() {
        sealed class DisconnectReason {
            object RejectedDueToServicesFilter : DisconnectReason()
            object BadConnectionAttempt : DisconnectReason()
            data class Finished(val error: Throwable?) : DisconnectReason()
        }
    }
}

internal sealed class Event {
    object OnDiscoveryRequest : Event()

    object OnTimeout : Event()

    object OnAssociationPending : Event()
    data class OnAssociationFinished(val result: Result<BluetoothDevice>) : Event()

    data class OnConnectionEstablished(val services: List<BluetoothGattService>) : Event()
    data class OnConnectionAttemptFailed(val reason: Throwable) : Event()

    object OnDisconnectSuccess : Event()
}

internal sealed class SideEffect
