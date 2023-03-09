package de.kishorrana.signalboy_android.service.client

import android.bluetooth.*
import android.util.Log
import de.kishorrana.signalboy_android.service.gatt.GATT_STATUS_SUCCESS
import de.kishorrana.signalboy_android.util.toHexString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*

private const val TAG = "ClientBtGattCallback"

internal class ClientBluetoothGattCallback(scope: CoroutineScope) : BluetoothGattCallback() {
    private val _connectionStateChangeResponseBus =
        makeResponseBus<ConnectionStateChangeResponse>()
    val connectionStateChangeResponses = _connectionStateChangeResponseBus.asSharedFlow()

    private val _asyncOperationResponseBus = makeResponseBus<GattOperationResponse>()
    val asyncOperationResponses = _asyncOperationResponseBus.asSharedFlow()

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onConnectionStateChange() - " +
                    "gatt=$gatt status=${status.toByte().toHexString()} newState=$newState"
        )

        val response = ConnectionStateChangeResponse(newState, status)
        if (!_connectionStateChangeResponseBus.tryEmit(response)) {
            Log.w(
                TAG, "onConnectionStateChange - _connectionStateChangeResponseFlow buffer" +
                        " overflow! Outbound response will be dropped."
            )
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(TAG, "onServicesDiscovered() - status=${status.toByte().toHexString()}")

        val result: Result<List<BluetoothGattService>> = if (status == GATT_STATUS_SUCCESS) {
            Result.success(gatt.services)
        } else {
            Result.failure(ReceivedBadStatusCodeException(status))
        }

        val response = GattOperationResponse.ServicesDiscoveredResponse(result)
        if (!_asyncOperationResponseBus.tryEmit(response)) {
            Log.w(
                TAG, "onServicesDiscovered - _asyncOperationResponseFlow buffer" +
                        " overflow! Outbound response will be dropped."
            )
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onDescriptorWrite() - descriptor=${descriptor.uuid} " +
                    "status=${status.toByte().toHexString()}"
        )

        val response = GattOperationResponse.DescriptorWriteResponse(descriptor, status)
        if (!_asyncOperationResponseBus.tryEmit(response)) {
            Log.w(
                TAG, "onDescriptorWrite - _asyncOperationResponseFlow buffer" +
                        " overflow! Outbound response will be dropped."
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onCharacteristicRead() - characteristic=${characteristic.uuid} " +
                    "status=${status.toByte().toHexString()}"
        )

        val response = GattOperationResponse.CharacteristicReadResponse(characteristic, status)
        if (!_asyncOperationResponseBus.tryEmit(response)) {
            Log.w(
                TAG, "onCharacteristicRead - _asyncOperationResponseFlow buffer" +
                        " overflow! Outbound response will be dropped."
            )
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        val loggingHandler: (tag: String?, msg: String) -> Int =
            if (status == GATT_STATUS_SUCCESS) Log::d else Log::w
        loggingHandler(
            TAG, "onCharacteristicWrite() - characteristic=${characteristic.uuid} " +
                    "status=${status.toByte().toHexString()}"
        )

        val response = GattOperationResponse.CharacteristicWriteResponse(characteristic, status)
        if (!_asyncOperationResponseBus.tryEmit(response)) {
            Log.w(
                TAG, "onCharacteristicWrite - _asyncOperationResponseFlow buffer" +
                        " overflow! Outbound response will be dropped."
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        Log.d(TAG, "onCharacteristicChanged() - characteristic=${characteristic.uuid}")

        val response = GattOperationResponse.CharacteristicChangedResponse(characteristic)
        if (!_asyncOperationResponseBus.tryEmit(response)) {
            Log.w(
                TAG, "onCharacteristicChanged - _asyncOperationResponseFlow buffer" +
                        " overflow! Outbound response will be dropped."
            )
        }
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
private fun <T> ClientBluetoothGattCallback.Companion.makeResponseBus() = MutableSharedFlow<T>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
//endregion
