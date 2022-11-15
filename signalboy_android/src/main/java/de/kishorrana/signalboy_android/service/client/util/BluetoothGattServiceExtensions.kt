package de.kishorrana.signalboy_android.service.client.util

import android.bluetooth.BluetoothGattService
import de.kishorrana.signalboy_android.service.gatt.SignalboyGattAttributes
import java.util.*

internal fun Collection<BluetoothGattService>.isSuperset(of: Map<UUID, List<UUID>>): Boolean {
    val requiredServices = of
        .mapValues { it.value.toMutableSet() }
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

internal fun Collection<BluetoothGattService>.hasAllSignalboyGattAttributes() =
    isSuperset(SignalboyGattAttributes.allServices)
