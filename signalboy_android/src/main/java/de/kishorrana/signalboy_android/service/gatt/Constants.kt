package de.kishorrana.signalboy_android.service.gatt

import java.util.*

// From Bluetooth Specification – Core (v1.5)
// s. "BLUETOOTH CORE SPECIFICATION Version 5.3 | Vol 1, Part F"
const val GATT_STATUS_SUCCESS: Int = 0x00
const val GATT_STATUS_CONNECTION_TIMEOUT: Int = 0x08

val DEVICE_INFORMATION_SERVICE_UUID: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
val HARDWARE_REVISION_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a27-0000-1000-8000-00805F9B34FB")
val SOFTWARE_REVISION_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a28-0000-1000-8000-00805F9B34FB")

val CLIENT_CONFIGURATION_DESCRIPTOR_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
