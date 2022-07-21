package de.kishorrana.signalboy

import java.util.*

// From Bluetooth Specification – Core (v1.5)
// s. "BLUETOOTH CORE SPECIFICATION Version 5.3 | Vol 1, Part F"
const val GATT_STATUS_SUCCESS: Int = 0x00
const val GATT_STATUS_CONNECTION_TIMEOUT: Int = 0x08

val CLIENT_CONFIGURATION_DESCRIPTOR_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Stops scanning after 10 seconds.
const val SCAN_PERIOD_IN_MILLIS: Long = 10_000L
const val CONNECTION_ATTEMPT_TIMEOUT_IN_MILLIS: Long = 3_000L

// Training interval is expected to match the BLE-connection's connection interval.
const val TRAINING_INTERVAL_IN_MILLIS = 20L
const val TRAINING_MESSAGES_COUNT = 3

const val MIN_IN_MILLISECONDS: Long = 1000 * 60
