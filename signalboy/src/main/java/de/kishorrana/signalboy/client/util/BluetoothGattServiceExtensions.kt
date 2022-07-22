package de.kishorrana.signalboy.client.util

import android.bluetooth.BluetoothGattService
import de.kishorrana.signalboy.GattClientIsMissingAttributesException
import de.kishorrana.signalboy.gatt.*
import de.kishorrana.signalboy.gatt.SignalboyGattAttributes

internal fun Collection<BluetoothGattService>.hasAllSignalboyGattAttributes(): Boolean {
    val requiredServices =
        SignalboyGattAttributes.allServices.mapValues { it.value.toMutableSet() }
            .toMutableMap()

    forEach { service ->
        service.characteristics.forEach { characteristic ->
            requiredServices[service.uuid]?.remove(characteristic.uuid)
        }
        if (requiredServices[service.uuid]?.isEmpty() == true) {
            requiredServices.remove(service.uuid)
        }
    }

    return requiredServices.isEmpty()
}
