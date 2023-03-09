package de.kishorrana.signalboy_android.service.client

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import de.kishorrana.signalboy_android.service.PrerequisitesNode
import de.kishorrana.signalboy_android.service.client.Client.CharacteristicUpdate
import de.kishorrana.signalboy_android.service.client.Client.NotificationSubscription
import de.kishorrana.signalboy_android.service.client.ClientBluetoothGattCallback.GattOperationResponse.*
import de.kishorrana.signalboy_android.service.client.Event.OnConnectionRequested
import de.kishorrana.signalboy_android.service.client.Event.OnDisconnectRequested
import de.kishorrana.signalboy_android.service.client.State.Connected
import de.kishorrana.signalboy_android.service.client.State.Disconnected
import de.kishorrana.signalboy_android.service.client.util.readCharacteristic
import de.kishorrana.signalboy_android.service.client.util.setCharacteristicNotification
import de.kishorrana.signalboy_android.service.client.util.writeCharacteristic
import de.kishorrana.signalboy_android.service.client.util.writeDescriptor
import de.kishorrana.signalboy_android.service.gatt.CLIENT_CONFIGURATION_DESCRIPTOR_UUID
import de.kishorrana.signalboy_android.service.gatt.GATT_STATUS_SUCCESS
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.*

private const val TAG = "SignalboyClient"

internal class DefaultClient(
    context: Context,
    bluetoothAdapter: BluetoothAdapter,
    parentJob: Job? = null,
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : Client {
    // Reusing StateManager's state here.
    override val state: State
        get() = stateManager.state
    override val latestState: StateFlow<State>
        get() = stateManager.latestState

    private val scope = CoroutineScope(
        defaultDispatcher + Job(parentJob) + CoroutineName("Client")
    )

    private val gattCallback = ClientBluetoothGattCallback(scope)
    private val stateManager = StateManager(context, bluetoothAdapter, gattCallback, scope)

    /**
     * Channel is empty while no async-operation for BluetoothGatt is active.
     *
     * Background: [BluetoothGatt] will fail to execute its offered async-operations
     * (like [BluetoothGatt.readCharacteristic], [BluetoothGatt.writeCharacteristic], etc.)
     * when attempting to start such operation, while
     */
    private val semaphore = Channel<Unit>(1)

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
            semaphore.close()
        }
    }

    override suspend fun connect(
        device: BluetoothDevice,
        retryCount: Int
    ): List<BluetoothGattService> {
        triggerConnect(device, retryCount)

        // await connect response
        return stateManager.latestState
            .onEach { state ->
                when (state) {
                    is Disconnected -> {
                        state.cause?.let { throw it }   // Disconnected due to connection error
                            ?: throw ConnectionAttemptCancellationException()    // Disconnected gracefully (by user-request?)
                    }
                    else -> { /* no-op */
                    }
                }
            }
            .filterIsInstance<Connected>()
            .map { it.services }
            .first()
    }

    override suspend fun disconnect() {
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
    override suspend fun readGattCharacteristic(
        service: UUID,
        characteristic: UUID
    ): ByteArray = executeGattOperation {
        readCharacteristic(service, characteristic)
        val response = gattCallback.asyncOperationResponses
            .mapNotNull { it as? CharacteristicReadResponse }
            .first()

        response.characteristic.ensureIdentity(service, characteristic)

        if (response.status != GATT_STATUS_SUCCESS)
            throw AsyncOperationFailedException(response.status)

        return@executeGattOperation response.characteristic.value
    }

    /**
     * Write gatt characteristic
     *
     * @param characteristic
     * @param data
     * @param shouldWaitForResponse
     * @throws [FailedToStartAsyncOperationException], [AsyncOperationFailedException]
     */
    override suspend fun writeGattCharacteristic(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        shouldWaitForResponse: Boolean
    ) = executeGattOperation {
        writeCharacteristic(service, characteristic, data, shouldWaitForResponse)
        val response = gattCallback.asyncOperationResponses
            .mapNotNull { it as? CharacteristicWriteResponse }
            .first()
        response.characteristic.ensureIdentity(service, characteristic)

        if (response.status != GATT_STATUS_SUCCESS)
            throw AsyncOperationFailedException(response.status)
    }

    override suspend fun writeGattDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        data: ByteArray
    ) = executeGattOperation {
        writeDescriptor(service, characteristic, descriptor, data)

        val response = gattCallback.asyncOperationResponses
            .mapNotNull { it as? DescriptorWriteResponse }
            .first()
        response.descriptor.ensureIdentity(service, characteristic, descriptor)

        if (response.status != GATT_STATUS_SUCCESS)
            throw AsyncOperationFailedException(response.status)
    }

    override suspend fun startNotify(
        service: UUID,
        characteristic: UUID,
        onCharacteristicChanged: OnNotificationReceived
    ): NotificationSubscription.CancellationToken {
        writeGattClientConfigurationDescriptorAsync(service, characteristic, true)
        executeGattOperation {
            setCharacteristicNotification(service, characteristic, true)
        }

        return NotificationSubscription(this, onCharacteristicChanged)
            .also { notificationSubscriptions.add(Pair(Pair(service, characteristic), it)) }
            .cancellationToken
    }

    override fun stopNotify(notificationSubscription: NotificationSubscription) {
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
            scope.launch {
                try {
                    writeGattClientConfigurationDescriptorAsync(
                        serviceUUID,
                        characteristicUUID,
                        false
                    )
                    executeGattOperation {
                        setCharacteristicNotification(serviceUUID, characteristicUUID, false)
                    }
                } catch (err: Throwable) {
                    Log.e(
                        TAG, "Failed to update characteristic " +
                                "Client-Configuration-Descriptor with notification disabled, " +
                                "due to error", err
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
                gattCallback.connectionStateChangeResponses.collect { (newState, status) ->
                    stateManager.handleEvent(Event.OnGattConnectionStateChange(newState, status))
                }
            }
            launch {
                gattCallback.asyncOperationResponses
                    .mapNotNull { it as? ServicesDiscoveredResponse }
                    .collect { (services) ->
                        stateManager.handleEvent(Event.OnGattServicesDiscovered(services))
                    }
            }
            launch {
                gattCallback.asyncOperationResponses
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
     * Executes the specified block, or throws if current-state
     * does not feature a gatt-client or the gatt-client is currently not ready
     * for accepting operations.
     */
    private suspend fun <T> executeGattOperation(block: suspend BluetoothGatt.() -> T): T {
//        Log.d(
//            TAG, "executeGattOperation($i) - schedule " +
//                    "(operationQueue.isEmpty=${semaphore.isEmpty})"
//        )

        val result = try {
            // May suspend until BluetoothGatt is ready to execute the next operation.
            semaphore.send(Unit)

            withTimeout(3_000L) {
//                Log.d(TAG, "executeGattOperation($i) - execute")
                with(getGattClientOrThrow()) {
                    block()
                }
            }
        } catch (err: TimeoutCancellationException) {
            throw IllegalStateException("Execution of BluetoothGatt-operation timed out.")
        } finally {
            semaphore.tryReceive()
        }

        return result
    }

    /**
     * Returns the current state's gatt-client, or throws if current-state
     * does not feature a gatt-client or the gatt-client is currently not ready
     * for accepting operations.
     */
    private fun getGattClientOrThrow(): BluetoothGatt {
        val state = stateManager.state
        return (state as? Connected)?.session?.gattClient
            ?: throw OperationNotSupportedByCurrentStateException(state)
    }

    private fun triggerConnect(device: BluetoothDevice, retryCount: Int) {
        stateManager.handleEvent(OnConnectionRequested(device, retryCount))
    }

    private fun triggerDisconnect() {
        stateManager.handleEvent(OnDisconnectRequested)
    }

    companion object {
        internal fun asPrerequisitesNode(
            context: Context,
            bluetoothAdapter: BluetoothAdapter
        ) = PrerequisitesNode { visitor ->
            if (!bluetoothAdapter.isEnabled) {
                visitor.addBluetoothEnabledUnmet()
            }

            val runtimePermissions = if (Build.VERSION.SDK_INT >= 31) {
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                listOf(
                    Manifest.permission.BLUETOOTH,
                )
            }

            runtimePermissions
                .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .takeIf { it.isNotEmpty() }
                ?.let { visitor.addUnmetRuntimePermissions(it) }
        }
    }
}

//region Helpers
private suspend fun Client.writeGattClientConfigurationDescriptorAsync(
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
