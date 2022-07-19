package de.kishorrana.signalboy.client

import android.bluetooth.*
import android.util.Log
import de.kishorrana.signalboy.GATT_STATUS_SUCCESS
import de.kishorrana.signalboy.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

private const val TAG = "ClientBtGattCallback"

internal class ClientBluetoothGattCallback(scope: CoroutineScope) : BluetoothGattCallback() {
    private val _connectionStateChangeCallbackChannel =
        makeCallbackChannel<ConnectionStateChangeCallback>()
    val connectionStateChangeCallbackFlow =
        _connectionStateChangeCallbackChannel.receiveAsSharedFlow(scope)

    private val _asyncOperationCallbackChannel =
        makeCallbackChannel<GattOperationCallback>()
    val asyncOperationCallbackFlow =
        _asyncOperationCallbackChannel.receiveAsSharedFlow(scope)

    override fun onConnectionStateChange(
        gatt: BluetoothGatt?,
        status: Int,
        newState: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onConnectionStateChange() - " +
                    "gatt=$gatt status=${status.toByte().toHexString()} newState=$newState"
        )

        _connectionStateChangeCallbackChannel.trySend(
            ConnectionStateChangeCallback(
                newState,
                status
            )
        )
    }

    @Suppress("NAME_SHADOWING")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        val gatt = gatt ?: throw IllegalArgumentException("`gatt` must not be null.")

        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(TAG, "onServicesDiscovered() - status=${status.toByte().toHexString()}")

        val result: Result<List<BluetoothGattService>> = if (status == GATT_STATUS_SUCCESS) {
            Result.success(gatt.services)
        } else {
            Result.failure(ReceivedBadStatusCodeException(status))
        }

        _asyncOperationCallbackChannel.trySend(
            GattOperationCallback.ServicesDiscoveredCallback(
                result
            )
        )
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(TAG, "onDescriptorWrite() - status=${status.toByte().toHexString()}")

        _asyncOperationCallbackChannel.trySend(
            GattOperationCallback.DescriptorWriteCallback(
                descriptor!!,
                status
            )
        )
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onCharacteristicRead() - characteristic=$characteristic " +
                    "status=${status.toByte().toHexString()}"
        )

        _asyncOperationCallbackChannel.trySend(
            GattOperationCallback.CharacteristicReadCallback(
                characteristic!!,
                status
            )
        )
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onCharacteristicWrite() - characteristic=$characteristic " +
                    "status=${status.toByte().toHexString()}"
        )

        _asyncOperationCallbackChannel.trySend(
            GattOperationCallback.CharacteristicWriteCallback(
                characteristic!!,
                status
            )
        )
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        Log.d(TAG, "onCharacteristicChanged() - characteristic=$characteristic")

        _asyncOperationCallbackChannel.trySend(
            GattOperationCallback.CharacteristicChangedCallback(characteristic!!)
        )
    }

    private fun <T> ReceiveChannel<T>.receiveAsSharedFlow(scope: CoroutineScope): SharedFlow<T> =
        receiveAsFlow().shareIn(scope, SharingStarted.Eagerly)

    // WORKAROUND: Linter causes issues when empty class body is removed.
    @Suppress("RemoveEmptyClassBody")
    companion object {}    // Will be extended with helper-functions in outer-context.

    //region Supporting Types
    data class ConnectionStateChangeCallback(
        val newState: Int,
        val status: Int
    )

    sealed class GattOperationCallback {
        data class ServicesDiscoveredCallback(
            val services: Result<List<BluetoothGattService>>
        ) : GattOperationCallback()

        data class CharacteristicReadCallback(
            val characteristic: BluetoothGattCharacteristic,
            val status: Int
        ) : GattOperationCallback()

        data class CharacteristicWriteCallback(
            val characteristic: BluetoothGattCharacteristic,
            val status: Int
        ) : GattOperationCallback()

        data class CharacteristicChangedCallback(
            val characteristic: BluetoothGattCharacteristic
        ) : GattOperationCallback()

        data class DescriptorWriteCallback(
            val descriptor: BluetoothGattDescriptor,
            val status: Int
        ) : GattOperationCallback()
    }
    //endregion

    class ReceivedBadStatusCodeException(val status: Int) : RuntimeException()
}

//region Factory
private fun <T> ClientBluetoothGattCallback.Companion.makeCallbackChannel(): Channel<T> =
    Channel(CONFLATED)
//endregion
