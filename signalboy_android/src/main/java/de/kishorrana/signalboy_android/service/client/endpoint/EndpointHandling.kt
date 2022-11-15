package de.kishorrana.signalboy_android.service.client.endpoint

import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.client.Client.Endpoint
import de.kishorrana.signalboy_android.service.client.OnNotificationReceived

internal suspend fun Client.readGattCharacteristicAsync(
    characteristic: Endpoint.Characteristic
): ByteArray = readGattCharacteristic(
    characteristic.serviceUUID,
    characteristic.characteristicUUID
)

internal suspend fun Client.writeGattCharacteristicAsync(
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
): Client.NotificationSubscription.CancellationToken = startNotify(
    characteristic.serviceUUID,
    characteristic.characteristicUUID,
    onCharacteristicChanged
)
