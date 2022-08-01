package de.kishorrana.signalboy_android.client

import android.bluetooth.*
import android.util.Log
import de.kishorrana.signalboy_android.gatt.GATT_STATUS_SUCCESS
import de.kishorrana.signalboy_android.util.toHexString
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
    private val _connectionStateChangeResponseChannel =
        makeResponseChannel<ConnectionStateChangeResponse>()
    val connectionStateChangeResponseFlow =
        _connectionStateChangeResponseChannel.receiveAsSharedFlow(scope)

    private val _asyncOperationResponseChannel =
        makeResponseChannel<GattOperationResponse>()
    val asyncOperationResponseFlow =
        _asyncOperationResponseChannel.receiveAsSharedFlow(scope)

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

        _connectionStateChangeResponseChannel.trySend(
            ConnectionStateChangeResponse(
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

        _asyncOperationResponseChannel.trySend(
            GattOperationResponse.ServicesDiscoveredResponse(
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

        _asyncOperationResponseChannel.trySend(
            GattOperationResponse.DescriptorWriteResponse(descriptor!!, status)
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

        _asyncOperationResponseChannel.trySend(
            GattOperationResponse.CharacteristicReadResponse(
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

        _asyncOperationResponseChannel.trySend(
            GattOperationResponse.CharacteristicWriteResponse(characteristic!!, status)
        )
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        Log.d(TAG, "onCharacteristicChanged() - characteristic=$characteristic")

        _asyncOperationResponseChannel.trySend(
            GattOperationResponse.CharacteristicChangedResponse(characteristic!!)
        )
    }

    private fun <T> ReceiveChannel<T>.receiveAsSharedFlow(scope: CoroutineScope): SharedFlow<T> =
        receiveAsFlow().shareIn(scope, SharingStarted.Eagerly)

    // WORKAROUND: Linter causes issues when empty class body is removed.
    @Suppress("RemoveEmptyClassBody")
    companion object {}    // Will be extended with helper-functions in outer-context.

    //region Supporting Types
    data class ConnectionStateChangeResponse(
        val newState: Int,
        val status: Int
    )

    sealed class GattOperationResponse {
        data class ServicesDiscoveredResponse(
            val services: Result<List<BluetoothGattService>>
        ) : GattOperationResponse()

        data class CharacteristicReadResponse(
            val characteristic: BluetoothGattCharacteristic,
            val status: Int
        ) : GattOperationResponse()

        data class CharacteristicWriteResponse(
            val characteristic: BluetoothGattCharacteristic,
            val status: Int
        ) : GattOperationResponse()

        data class CharacteristicChangedResponse(
            val characteristic: BluetoothGattCharacteristic
        ) : GattOperationResponse()

        data class DescriptorWriteResponse(
            val descriptor: BluetoothGattDescriptor,
            val status: Int
        ) : GattOperationResponse()
    }
    //endregion

    class ReceivedBadStatusCodeException(val status: Int) : RuntimeException()
}

//region Factory
private fun <T> ClientBluetoothGattCallback.Companion.makeResponseChannel(): Channel<T> =
    Channel(CONFLATED)
//endregion
