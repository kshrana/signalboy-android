package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothGattService

internal class GattServicesCache {
    private val gattServicesCache = mutableMapOf<String, List<BluetoothGattService>?>()

    fun getServices(address: String): List<BluetoothGattService>? = gattServicesCache[address]

    fun setServices(services: List<BluetoothGattService>?, address: String) {
        gattServicesCache.clear()
        gattServicesCache[address] = services
    }
}
