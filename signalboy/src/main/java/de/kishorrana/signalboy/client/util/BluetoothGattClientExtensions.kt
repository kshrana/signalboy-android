package de.kishorrana.signalboy.client.util

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import de.kishorrana.signalboy.MissingRequiredRuntimePermissionException
import de.kishorrana.signalboy.client.CharacteristicNotFoundException
import de.kishorrana.signalboy.client.DescriptorNotFoundException
import de.kishorrana.signalboy.client.FailedToStartAsyncOperationException
import de.kishorrana.signalboy.client.ServiceNotFoundException
import java.util.*

internal fun BluetoothGatt.readCharacteristic(
    service: UUID,
    characteristic: UUID
) {
    getCharacteristic(service, characteristic)
        .let(::readCharacteristicOrThrow)
}

internal fun BluetoothGatt.writeCharacteristic(
    service: UUID,
    characteristic: UUID,
    data: ByteArray,
    shouldWaitForResponse: Boolean
) {
    getCharacteristic(service, characteristic)
        .apply { setValueForCharacteristic(data, shouldWaitForResponse) }
        .let(::writeCharacteristicOrThrow)
}

internal fun BluetoothGatt.writeDescriptor(
    service: UUID,
    characteristic: UUID,
    descriptor: UUID,
    data: ByteArray
) {
    getDescriptor(service, characteristic, descriptor)
        .apply { value = data }
        .let(::writeDescriptorOrThrow)
}

internal fun BluetoothGatt.setCharacteristicNotification(
    service: UUID,
    characteristic: UUID,
    enable: Boolean
) {
    getCharacteristic(service, characteristic)
        .let { setCharacteristicNotificationOrThrow(it, enable) }
}

//region Helpers
private fun BluetoothGatt.getCharacteristic(
    service: UUID,
    characteristic: UUID,
): BluetoothGattCharacteristic {
    val gattService = try {
        getService(service)
    } catch (npe: NullPointerException) {
        throw ServiceNotFoundException()
    }

    return with(gattService) {
        try {
            getCharacteristic(characteristic)
        } catch (npe: NullPointerException) {
            throw CharacteristicNotFoundException()
        }
    }
}

private fun BluetoothGattCharacteristic.setValueForCharacteristic(
    data: ByteArray,
    shouldWaitForResponse: Boolean
) {
    writeType = if (shouldWaitForResponse) {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    } else {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }
    value = data
}

private fun BluetoothGatt.readCharacteristicOrThrow(
    characteristic: BluetoothGattCharacteristic
) {
    val isStartAsyncOperationSuccess = try {
        readCharacteristic(characteristic)
    } catch (err: SecurityException) {
        throw MissingRequiredRuntimePermissionException(err)
    }

    if (!isStartAsyncOperationSuccess)
        throw FailedToStartAsyncOperationException()
}

private fun BluetoothGatt.writeCharacteristicOrThrow(
    characteristic: BluetoothGattCharacteristic
) {
    val isStartAsyncOperationSuccess = try {
        writeCharacteristic(characteristic)
    } catch (err: SecurityException) {
        throw MissingRequiredRuntimePermissionException(err)
    }

    if (!isStartAsyncOperationSuccess)
        throw FailedToStartAsyncOperationException()
}

private fun BluetoothGatt.getDescriptor(
    service: UUID,
    characteristic: UUID,
    descriptor: UUID
): BluetoothGattDescriptor {
    return with(getCharacteristic(service, characteristic)) {
        try {
            getDescriptor(descriptor)
        } catch (npe: NullPointerException) {
            throw DescriptorNotFoundException()
        }
    }
}

private fun BluetoothGatt.writeDescriptorOrThrow(
    descriptor: BluetoothGattDescriptor
) {
    val isStartAsyncOperationSuccess = try {
        writeDescriptor(descriptor)
    } catch (err: SecurityException) {
        throw MissingRequiredRuntimePermissionException(err)
    }

    if (!isStartAsyncOperationSuccess)
        throw FailedToStartAsyncOperationException()
}

private fun BluetoothGatt.setCharacteristicNotificationOrThrow(
    characteristic: BluetoothGattCharacteristic,
    enable: Boolean
) {
    val isSuccess = try {
        setCharacteristicNotification(characteristic, enable)
    } catch (err: SecurityException) {
        throw MissingRequiredRuntimePermissionException(err)
    }

    if (!isSuccess)
        throw FailedToStartAsyncOperationException()
}
//endregion
