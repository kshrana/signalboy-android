package de.kishorrana.signalboy.client.endpoint

import de.kishorrana.signalboy.client.Client
import de.kishorrana.signalboy.client.Client.Endpoint
import de.kishorrana.signalboy.client.OnNotificationReceived

internal suspend fun Client.readGattCharacteristicAsync(
    characteristic: Endpoint.Characteristic
): ByteArray = readGattCharacteristicAsync(
    characteristic.serviceUUID,
    characteristic.characteristicUUID
)

internal fun Client.readGattCharacteristic(
    characteristic: Endpoint.Characteristic
) = readGattCharacteristic(
    characteristic.serviceUUID,
    characteristic.characteristicUUID
)

internal suspend fun Client.writeGattCharacteristicAsync(
    characteristic: Endpoint.Characteristic,
    data: ByteArray,
    shouldWaitForResponse: Boolean = false
) = writeGattCharacteristicAsync(
    characteristic.serviceUUID,
    characteristic.characteristicUUID,
    data,
    shouldWaitForResponse
)

internal fun Client.writeGattCharacteristic(
    characteristic: Endpoint.Characteristic,
    data: ByteArray,
    shouldWaitForResponse: Boolean = false
) = writeGattCharacteristic(
    characteristic.serviceUUID,
    characteristic.characteristicUUID,
    data,
    shouldWaitForResponse
)

internal suspend fun Client.startNotifyAsync(
    characteristic: Endpoint.Characteristic,
    onCharacteristicChanged: OnNotificationReceived
): Client.NotificationSubscription.CancellationToken = startNotifyAsync(
    characteristic.serviceUUID,
    characteristic.characteristicUUID,
    onCharacteristicChanged
)
