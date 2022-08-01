package de.kishorrana.signalboy_android.gatt

import de.kishorrana.signalboy_android.client.Client
import java.util.*

internal object SignalboyGattAttributes {
    val OUTPUT_SERVICE_UUID: UUID = UUID.fromString("37410000-b4d1-f445-aa29-989ea26dc614")
    val TARGET_TIMESTAMP_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("37410001-b4d1-f445-aa29-989ea26dc614")
    val TRIGGER_TIMER_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("37410002-b4d1-f445-aa29-989ea26dc614")

    val TIME_SYNC_SERVICE_UUID: UUID = UUID.fromString("92360000-7858-41a5-b0cc-942dd4189715")
    val TIME_NEEDS_SYNC_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("92360001-7858-41a5-b0cc-942dd4189715")
    val REFERENCE_TIMESTAMP_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("92360002-7858-41a5-b0cc-942dd4189715")

    val allServices = mapOf(
        DEVICE_INFORMATION_SERVICE_UUID to listOf(
            HARDWARE_REVISION_CHARACTERISTIC_UUID,
            SOFTWARE_REVISION_CHARACTERISTIC_UUID,
        ),
        OUTPUT_SERVICE_UUID to listOf(
            TARGET_TIMESTAMP_CHARACTERISTIC_UUID,
            TRIGGER_TIMER_CHARACTERISTIC_UUID,
        ),
        TIME_SYNC_SERVICE_UUID to listOf(
            TIME_NEEDS_SYNC_CHARACTERISTIC_UUID,
            REFERENCE_TIMESTAMP_CHARACTERISTIC_UUID,
        )
    )
}

internal object HardwareRevisionCharacteristic : Client.Endpoint.Characteristic() {
    override val serviceUUID = DEVICE_INFORMATION_SERVICE_UUID
    override val characteristicUUID = HARDWARE_REVISION_CHARACTERISTIC_UUID
}

internal object SoftwareRevisionCharacteristic : Client.Endpoint.Characteristic() {
    override val serviceUUID = DEVICE_INFORMATION_SERVICE_UUID
    override val characteristicUUID = SOFTWARE_REVISION_CHARACTERISTIC_UUID
}

internal object TargetTimestampCharacteristic : Client.Endpoint.Characteristic() {
    override val serviceUUID = SignalboyGattAttributes.OUTPUT_SERVICE_UUID
    override val characteristicUUID = SignalboyGattAttributes.TARGET_TIMESTAMP_CHARACTERISTIC_UUID
}

internal object TriggerTimerCharacteristic : Client.Endpoint.Characteristic() {
    override val serviceUUID = SignalboyGattAttributes.OUTPUT_SERVICE_UUID
    override val characteristicUUID = SignalboyGattAttributes.TRIGGER_TIMER_CHARACTERISTIC_UUID
}

internal object TimeNeedsSyncCharacteristic : Client.Endpoint.Characteristic() {
    override val serviceUUID = SignalboyGattAttributes.TIME_SYNC_SERVICE_UUID
    override val characteristicUUID = SignalboyGattAttributes.TIME_NEEDS_SYNC_CHARACTERISTIC_UUID
}

internal object ReferenceTimestampCharacteristic : Client.Endpoint.Characteristic() {
    override val serviceUUID = SignalboyGattAttributes.TIME_SYNC_SERVICE_UUID
    override val characteristicUUID =
        SignalboyGattAttributes.REFERENCE_TIMESTAMP_CHARACTERISTIC_UUID
}
