package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.util.Log
import de.kishorrana.signalboy.client.ClientBluetoothGattCallback.GattOperationResponse.*
import de.kishorrana.signalboy.client.Event.OnConnectionRequested
import de.kishorrana.signalboy.client.Event.OnDisconnectRequested
import de.kishorrana.signalboy.client.State.*
import de.kishorrana.signalboy.client.util.readCharacteristic
import de.kishorrana.signalboy.client.util.setCharacteristicNotification
import de.kishorrana.signalboy.client.util.writeCharacteristic
import de.kishorrana.signalboy.client.util.writeDescriptor
import de.kishorrana.signalboy.gatt.CLIENT_CONFIGURATION_DESCRIPTOR_UUID
import de.kishorrana.signalboy.gatt.GATT_STATUS_SUCCESS
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

private const val TAG = "SignalboyClient"

internal class Client(context: Context, parentJob: Job? = null) {
    interface Endpoint {
        abstract class Characteristic : Endpoint {
            abstract val serviceUUID: UUID
            abstract val characteristicUUID: UUID
        }
    }

    // Reusing the StateManager's state here.
    val state: State
        get() = stateManager.state
    val latestState: StateFlow<State>
        get() = stateManager.latestState

    private val scope = CoroutineScope(
        Dispatchers.Default + Job(parentJob) + CoroutineName("Client")
    )

    private val gattCallback = ClientBluetoothGattCallback(scope)
    private val stateManager = StateManager(context, gattCallback, scope)

    // First Pair being the key: <ServiceUUID, CharacteristicUUID>
    private val notificationSubscriptions =
        mutableListOf<Pair<Pair<UUID, UUID>, NotificationSubscription>>()

    init {
        setupStateManagerObserving()
        setupGattCallbackObserving()
    }

    fun destroy() {
        try {
            triggerDisconnect()
        } finally {
            scope.cancel("Parent Client-instance will be destroyed.")
        }
    }

    suspend fun connectAsync(device: BluetoothDevice, retryCount: Int = 3) {
        triggerConnect(device, retryCount)

        // await connect response
        return stateManager.latestState
            .takeWhile { state ->
                when (state) {
                    is Disconnected -> {
                        state.cause?.let { throw it }   // Disconnected due to connection error
                            ?: throw ConnectionAttemptCancellationException()    // Disconnected gracefully (by user-request?)
                    }
                    is Connecting -> true
                    is Connected -> false
                }
            }
            .collect()
    }

    suspend fun disconnectAsync() {
        triggerDisconnect()
        // await disconnect
        stateManager.latestState
            .takeWhile { it !is Disconnected }
            .collect()
    }

    /**
     * Read gatt characteristic
     *
     * @param characteristic
     * @throws [FailedToStartAsyncOperationException], [AsyncOperationFailedException]
     */
    suspend fun readGattCharacteristicAsync(
        service: UUID,
        characteristic: UUID
    ): ByteArray {
        readGattCharacteristic(service, characteristic)

        val response = gattCallback.asyncOperationResponseFlow
            .mapNotNull { it as? CharacteristicReadResponse }
            .first()
        response.characteristic.ensureIdentity(service, characteristic)

        if (response.status != GATT_STATUS_SUCCESS)
            throw AsyncOperationFailedException(response.status)

        return response.characteristic.value
    }

    fun readGattCharacteristic(
        service: UUID,
        characteristic: UUID
    ) {
        with(getGattClientOrThrow()) {
            readCharacteristic(service, characteristic)
        }
    }

    /**
     * Write gatt characteristic
     *
     * @param characteristic
     * @param data
     * @param shouldWaitForResponse
     * @throws [FailedToStartAsyncOperationException], [AsyncOperationFailedException]
     */
    suspend fun writeGattCharacteristicAsync(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        shouldWaitForResponse: Boolean = false
    ) {
        writeGattCharacteristic(service, characteristic, data, shouldWaitForResponse)

        val response = gattCallback.asyncOperationResponseFlow
            .mapNotNull { it as? CharacteristicWriteResponse }
            .first()
        response.characteristic.ensureIdentity(service, characteristic)

        if (response.status != GATT_STATUS_SUCCESS)
            throw AsyncOperationFailedException(response.status)
    }

    fun writeGattCharacteristic(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        shouldWaitForResponse: Boolean = false
    ) {
        with(getGattClientOrThrow()) {
            writeCharacteristic(service, characteristic, data, shouldWaitForResponse)
        }
    }

    suspend fun writeGattDescriptorAsync(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        data: ByteArray
    ) {
        writeGattDescriptor(service, characteristic, descriptor, data)

        val response = gattCallback.asyncOperationResponseFlow
            .mapNotNull { it as? DescriptorWriteResponse }
            .first()
        response.descriptor.ensureIdentity(service, characteristic, descriptor)

        if (response.status != GATT_STATUS_SUCCESS)
            throw AsyncOperationFailedException(response.status)
    }

    fun writeGattDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        data: ByteArray
    ) {
        with(getGattClientOrThrow()) {
            writeDescriptor(service, characteristic, descriptor, data)
        }
    }

    suspend fun startNotifyAsync(
        service: UUID,
        characteristic: UUID,
        onCharacteristicChanged: OnNotificationReceived
    ): NotificationSubscription.CancellationToken {
        writeGattClientConfigurationDescriptorAsync(service, characteristic, true)
        with(getGattClientOrThrow()) {
            setCharacteristicNotification(service, characteristic, true)
        }

        return NotificationSubscription(this, onCharacteristicChanged)
            .also { notificationSubscriptions.add(Pair(Pair(service, characteristic), it)) }
            .cancellationToken
    }

    private fun stopNotify(notificationSubscription: NotificationSubscription) {
        val elementToRemove: Pair<Pair<UUID, UUID>, NotificationSubscription>
        try {
            elementToRemove = notificationSubscriptions.first { (_, value) ->
                value == notificationSubscription
            }
        } catch (error: NoSuchElementException) {
            Log.v(
                TAG,
                "Failed to find record for notification subscription. Its corresponding GATT-Client" +
                        "may have been dropped before. - notificationSubscription=$notificationSubscription"
            )
            return
        }
        notificationSubscriptions.remove(elementToRemove)

        val (key, _) = elementToRemove
        val (serviceUUID, characteristicUUID) = key

        if (stateManager.state !is Disconnected) {
            with(getGattClientOrThrow()) {
                try {
                    writeClientConfigurationDescriptor(serviceUUID, characteristicUUID, false)
                    setCharacteristicNotification(serviceUUID, characteristicUUID, false)
                } catch (err: Throwable) {
                    Log.e(
                        TAG, "Failed to update characteristic " +
                                "Client-Configuration-Descriptor with notification disabled."
                    )
                }
            }
        } else {
            Log.v(
                TAG, "stopNotify() - Clearing record for subscription (on " +
                        "[serviceUUID=$serviceUUID, characteristicUUID=$characteristicUUID]) " +
                        "without calling operations on GATT-Client, as GATT-client has " +
                        "already been dropped (Disconnected-State)."
            )
        }
    }

    private fun cancelAllNotificationSubscriptions() {
        notificationSubscriptions
            .map { (_, value) -> value.cancellationToken }
            .forEach { it.cancel() }
    }

    //region Setup
    private fun setupStateManagerObserving() {
        scope.launch {
            stateManager.latestState.collect { state ->
                when (state) {
                    is Disconnected -> cancelAllNotificationSubscriptions()
                    else -> {}  /* no-op */
                }
            }
        }
    }

    private fun setupGattCallbackObserving() {
        scope.launch {
            launch {
                gattCallback.connectionStateChangeResponseFlow.collect { (newState, status) ->
                    stateManager.handleEvent(Event.OnGattConnectionStateChange(newState, status))
                }
            }
            launch {
                gattCallback.asyncOperationResponseFlow
                    .mapNotNull { it as? ServicesDiscoveredResponse }
                    .collect { (services) ->
                        stateManager.handleEvent(Event.OnGattServicesDiscovered(services))
                    }
            }
            launch {
                gattCallback.asyncOperationResponseFlow
                    .mapNotNull { it as? CharacteristicChangedResponse }
                    .collect { (bluetoothGattCharacteristic) ->
                        val serviceUUID = bluetoothGattCharacteristic.service.uuid
                        val characteristicUUID = bluetoothGattCharacteristic.uuid

                        with(CharacteristicUpdate(newValue = bluetoothGattCharacteristic.value)) {
                            notificationSubscriptions
                                .filter { (key, _) ->
                                    val (otherServiceUUID, otherCharacteristicUUID) = key

                                    serviceUUID == otherServiceUUID &&
                                            characteristicUUID == otherCharacteristicUUID
                                }
                                .forEach { (_, value) -> value.callback(this) }
                        }
                    }
            }
        }
    }
    //endregion

    private fun BluetoothGattCharacteristic.ensureIdentity(service: UUID, characteristic: UUID) {
        if (service != this.service.uuid || characteristic != uuid)
            throw IllegalStateException(
                "Received response for wrong characteristic. " +
                        "(expects: service=$service characteristic=$characteristic " +
                        "received: service=${this.service.uuid} characteristic=$uuid)"
            )
    }

    private fun BluetoothGattDescriptor.ensureIdentity(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID
    ) {
        if (service != this.characteristic.service.uuid || characteristic != this.characteristic.uuid || descriptor != uuid)
            throw IllegalStateException(
                "Received response for wrong descriptor. " +
                        "(expects: service=$service characteristic=$characteristic descriptor=$descriptor " +
                        "received: service=${this.characteristic.service.uuid} characteristic=${this.characteristic.uuid} descriptor=$uuid)"
            )
    }

    /**
     * Returns the current state's gatt-client, or `null` if current-state
     * does not feature a gatt-client or the gatt-client is currently not ready
     * for accepting operations.
     */
    private fun getGattClientOrThrow(): BluetoothGatt {
        val state = stateManager.state
        return (state as? Connected)?.session?.gattClient
            ?: throw OperationNotSupportedByCurrentStateException(state)
    }

    private fun triggerConnect(device: BluetoothDevice, retryCount: Int = 3) {
        stateManager.handleEvent(OnConnectionRequested(device, retryCount))
    }

    private fun triggerDisconnect() {
        stateManager.handleEvent(OnDisconnectRequested)
    }

    //region Supporting Types
    class NotificationSubscription internal constructor(
        client: Client,
        internal val callback: OnNotificationReceived
    ) {
        internal val cancellationToken = CancellationToken(client)

        inner class CancellationToken internal constructor(private val client: Client) {
            fun cancel() {
                client.stopNotify(this@NotificationSubscription)
            }
        }
    }

    data class CharacteristicUpdate(val newValue: ByteArray)
    //endregion
}

//region Helpers
private suspend fun Client.writeGattClientConfigurationDescriptorAsync(
    service: UUID,
    characteristic: UUID,
    enableNotification: Boolean
) {
    // s. https://bignerdranch.com/blog/bluetooth-low-energy-on-android-part-3/
    writeGattDescriptorAsync(
        service,
        characteristic,
        CLIENT_CONFIGURATION_DESCRIPTOR_UUID,
        getGattDescriptorNotificationValue(enableNotification)
    )
}

private fun Client.writeClientConfigurationDescriptor(
    service: UUID,
    characteristic: UUID,
    enableNotification: Boolean
) {
    // s. https://bignerdranch.com/blog/bluetooth-low-energy-on-android-part-3/
    writeGattDescriptor(
        service,
        characteristic,
        CLIENT_CONFIGURATION_DESCRIPTOR_UUID,
        getGattDescriptorNotificationValue(enableNotification)
    )
}

private fun getGattDescriptorNotificationValue(enable: Boolean) = if (enable) {
    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
} else {
    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
}
//endregion

internal typealias OnNotificationReceived = (Client.CharacteristicUpdate) -> Unit
