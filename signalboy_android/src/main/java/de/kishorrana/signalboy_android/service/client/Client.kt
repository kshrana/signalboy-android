package de.kishorrana.signalboy_android.service.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.flow.StateFlow
import java.util.*

internal typealias OnNotificationReceived = (Client.CharacteristicUpdate) -> Unit

internal interface Client {
    // Reusing StateManager's state here.
    val state: State
    val latestState: StateFlow<State>

    suspend fun connect(device: BluetoothDevice, retryCount: Int): List<BluetoothGattService>
    suspend fun disconnect()

    /**
     * Read gatt characteristic
     *
     * @param characteristic
     * @throws [FailedToStartAsyncOperationException], [AsyncOperationFailedException]
     */
    suspend fun readGattCharacteristic(
        service: UUID,
        characteristic: UUID
    ): ByteArray

    /**
     * Write gatt characteristic
     *
     * @param characteristic
     * @param data
     * @param shouldWaitForResponse
     * @throws [FailedToStartAsyncOperationException], [AsyncOperationFailedException]
     */
    suspend fun writeGattCharacteristic(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        shouldWaitForResponse: Boolean = false
    )

    suspend fun writeGattDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        data: ByteArray
    )

    suspend fun startNotify(
        service: UUID,
        characteristic: UUID,
        onCharacteristicChanged: OnNotificationReceived
    ): NotificationSubscription.CancellationToken

    fun stopNotify(notificationSubscription: NotificationSubscription)

    //region Supporting Types
    interface Endpoint {
        abstract class Characteristic : Endpoint {
            abstract val serviceUUID: UUID
            abstract val characteristicUUID: UUID
        }
    }

    class NotificationSubscription internal constructor(
        client: Client,
        internal val callback: OnNotificationReceived
    ) {
        internal val cancellationToken = CancellationToken(client)

        inner class CancellationToken internal constructor(private val client: Client) {
            fun cancel() {
                client.stopNotify(this@NotificationSubscription)
            }
        }
    }

    data class CharacteristicUpdate(val newValue: ByteArray)
    //endregion
}
