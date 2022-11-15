package de.kishorrana.signalboy_android.service.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattService
import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

internal class FakeClient(
    private val context: Context
) : Client {
    override val state: State
        get() = latestState.value

    private val _latestState = MutableStateFlow<State>(State.Disconnected(null))
    override val latestState: StateFlow<State> = _latestState.asStateFlow()

    private var connectionRequestChannel = Channel<ConnectionRequestScenario>(Channel.RENDEZVOUS)

    override suspend fun connect(device: BluetoothDevice, retryCount: Int) = coroutineScope {
        val session = Session(
            device,
            device.connectGatt(context, false, object : BluetoothGattCallback() {})
        )
        _latestState.value = State.Connecting(0, retryCount, launch {}, session)

        when (val scenario = connectionRequestChannel.receive()) {
            is ConnectionRequestScenario.DeviceOffline -> {
                val exception = NoConnectionAttemptsLeftException()
                _latestState.value = State.Disconnected(exception)
                throw exception
            }
            is ConnectionRequestScenario.DeviceOnline -> {
                State.Connected(scenario.services, session)
                    .also { _latestState.value = it }
                    .services
            }
        }
    }

    override suspend fun disconnect() {
        _latestState.value = State.Disconnected(null)
    }

    override suspend fun readGattCharacteristic(
        service: UUID,
        characteristic: UUID
    ): ByteArray {
        error("Method not supported.")
    }

    override suspend fun writeGattCharacteristic(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        shouldWaitForResponse: Boolean
    ) {
        error("Method not supported.")
    }

    override suspend fun writeGattDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        data: ByteArray
    ) {
        error("Method not supported.")
    }

    override suspend fun startNotify(
        service: UUID,
        characteristic: UUID,
        onCharacteristicChanged: OnNotificationReceived
    ): Client.NotificationSubscription.CancellationToken {
        error("Method not supported.")
    }

    override fun stopNotify(notificationSubscription: Client.NotificationSubscription) {
        error("Method not supported.")
    }

    fun handleConnectionRequest(scenario: ConnectionRequestScenario) {
        // Expects that there is already a receiver subscribed as a receiver
        // (by method call to `connect()`)
        check(connectionRequestChannel.trySend(scenario).isSuccess) {
            "Active connection request required."
        }
    }

    sealed class ConnectionRequestScenario {
        object DeviceOffline : ConnectionRequestScenario()
        data class DeviceOnline(val services: List<BluetoothGattService>) :
            ConnectionRequestScenario()
    }
}