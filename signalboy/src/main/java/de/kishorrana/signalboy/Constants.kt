package de.kishorrana.signalboy

import android.os.ParcelUuid

// From Bluetooth Specification – Core (v1.5)
// s. "BLUETOOTH CORE SPECIFICATION Version 5.3 | Vol 1, Part F"
const val BLUETOOTH_STATUS_CONNECTION_TIMEOUT: Int = 0x8

// Stops scanning after 10 seconds.
const val SCAN_PERIOD_IN_MILLIS: Long = 10_000L
const val CONNECT_TIMEOUT_IN_MILLIS: Long = 3_000L

internal val OutputService_UUID: ParcelUuid = ParcelUuid.fromString("37410000-b4d1-f445-aa29-989ea26dc614")
internal val TargetTimestampCharacteristic_UUID: ParcelUuid = ParcelUuid.fromString("37410001-b4d1-f445-aa29-989ea26dc614")
internal val TriggerTimerCharacteristic_UUID: ParcelUuid = ParcelUuid.fromString("37410002-b4d1-f445-aa29-989ea26dc614")

internal val TimeSyncService_UUID: ParcelUuid = ParcelUuid.fromString("92360000-7858-41a5-b0cc-942dd4189715")
internal val TimeNeedsSyncCharacteristic_UUID: ParcelUuid = ParcelUuid.fromString("92360001-7858-41a5-b0cc-942dd4189715")
internal val ReferenceTimestampCharacteristic_UUID: ParcelUuid = ParcelUuid.fromString("92360002-7858-41a5-b0cc-942dd4189715")
