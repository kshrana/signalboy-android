package de.kishorrana.signalboy.client

import android.bluetooth.*
import android.content.Context
import android.util.Log
import de.kishorrana.signalboy.BLUETOOTH_STATUS_CONNECTION_TIMEOUT
import de.kishorrana.signalboy.CONNECT_TIMEOUT_IN_MILLIS
import de.kishorrana.signalboy.MissingRequiredPermissionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout

private const val TAG = "SignalboyClient"

internal class Client(
    private val context: Context
) {
    private val _latestClientState =
        MutableStateFlow<ClientState>(ClientState.Disconnected())
    val latestConnectionState = _latestClientState.asStateFlow()

    private var bluetoothAddress: String? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val bluetoothGattCallback = ClientBluetoothGattCallback()

    // Cache Services received for the last successfully connected GATT-Client.
    private val gattServicesCache = GattServicesCache()

    fun getSupportedGattServices(): List<BluetoothGattService>? = bluetoothGatt?.services

    suspend fun connect(device: BluetoothDevice, attempts: Int = 1) {
        for (attempt in 1..attempts) {
            Log.d(TAG, "Connecting with Peripheral. (attempt: $attempt)")
            try {
                connect(device)
            } catch (err: ConnectionAttemptTimeoutException) {
                continue
            }

            // Connection established successfully.
            return
        }

        // Failed to establish connection.
        throw NoConnectionAttemptsLeftException()
    }

    suspend fun disconnect() {
        try {
            bluetoothGatt?.let { bluetoothGatt ->
                bluetoothGatt.disconnect()

                // Await disconnect...
                withTimeout(3_000L) {
                    latestConnectionState
                        .takeWhile { state -> state !is ClientState.Disconnected }
                        // ^ finish this flow when state changed to `STATE_DISCONNECTED`
                        .collect()
                }
            }
        } catch (err: SecurityException) {
            throw MissingRequiredPermissionsException(err)
        } catch (err: TimeoutCancellationException) {
            Log.e(TAG, "Disconnect operation timeout exceeded. Will still reset.")
            resetBluetoothState()

            return
        }

        closeConnection(null)
        Log.i(TAG, "Disconnected successfully.")
    }

    private suspend fun connect(device: BluetoothDevice) {
        // Start connecting.
        bluetoothAddress =
            device.address ?: throw IllegalArgumentException("`device.address` must not be null.")
        try {
            bluetoothGatt = device.connectGatt(context, false, bluetoothGattCallback)
        } catch (err: SecurityException) {
            closeConnection(null)
            throw MissingRequiredPermissionsException(err)
        }

        try {
            withTimeout(CONNECT_TIMEOUT_IN_MILLIS) {
                latestConnectionState
                    .takeWhile { state -> state !is ClientState.Connected }
                    // ^ finish this flow when state changed to `STATE_CONNECTED`
                    .collect()
            }
        } catch (err: CancellationException) {
            closeConnection(null)
            throw ConnectionAttemptTimeoutException()
        }

        Log.i(TAG, "Connection established successfully.")
    }

    /**
     * Calls `BluetoothGatt.discoverServices()` and handles exceptions that may be
     * thrown when starting to discover services.
     *
     * NOTE: This is an asynchronous operation. Once service discovery is completed,
     * the BluetoothGattCallback.onServicesDiscovered callback is triggered.
     *
     */
    private fun discoverServices() {
        val bluetoothGatt =
            bluetoothGatt ?: throw IllegalArgumentException("`bluetoothGatt` must not be null.")

        try {
            if (!bluetoothGatt.discoverServices())
                throw IllegalStateException(
                    "BluetoothGatt-Client failed to start to discover services."
                )

        } catch (err: SecurityException) {
            Log.e(
                TAG, "Failed to start service discovery due to missing permissions. Will drop " +
                        "the connection…", err
            )
            closeConnection(MissingRequiredPermissionsException(err))
        } catch (err: Throwable) {
            Log.e(TAG, "Failed to start service discovery. Will drop the connection…", err)
            closeConnection(err)
        }
    }

    private fun closeConnection(disconnectCause: Throwable? = null) {
        bluetoothGatt?.let {
            try {
                it.close()
            } catch (err: SecurityException) {
                /* no-op */
            } finally {
                resetBluetoothState()
            }
        }

        transition(Event.OnConnectionClosed(disconnectCause))
    }

    private fun cacheDiscoveredServices() {
        val bluetoothAddress =
            bluetoothAddress ?: throw IllegalStateException("`bluetoothAddress` must not be null.")
        gattServicesCache.setServices(getSupportedGattServices(), bluetoothAddress)
    }

    private fun resetBluetoothState() {
        bluetoothGatt = null
        bluetoothAddress = null
    }

    private fun transition(event: Event) {
        // Context
        val state = _latestClientState.value

        Log.d(TAG, "transition - context(state)=$state event=$event")

        // Determine transition
        val transition: Transition? = when (event) {
            is Event.OnConnectionClosed -> {
                Transition(
                    ClientState.Disconnected(event.disconnectCause),
                    SideEffect.ResetBluetoothState
                )
            }
            is Event.OnBluetoothGattConnectionStateChange -> {
                val (status, newState) = event

                when (newState) {
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val cause: Throwable? = when (status) {
                            BluetoothGatt.GATT_SUCCESS -> null
                            BLUETOOTH_STATUS_CONNECTION_TIMEOUT -> ConnectionTimeoutException()
                            else -> {
                                val statusCodeString = "%02x".format(status)
                                Exception(
                                    "Received unexpected status-code ($statusCodeString) for " +
                                            "connection-state($newState)"
                                )
                            }
                        }
                        Transition(ClientState.Disconnected(cause), SideEffect.ResetBluetoothState)
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        val bluetoothAddress = bluetoothAddress
                            ?: throw IllegalStateException("`bluetoothAddress` must not be null.")
                        if (gattServicesCache.getServices(bluetoothAddress) != null)
                            Transition(ClientState.Connected).also {
                                Log.d(
                                    TAG, "Reusing previously discovered Services for device " +
                                            "(at address=$bluetoothAddress)."
                                )
                            }
                        else
                            Transition(ClientState.Connecting, SideEffect.TriggerServiceDiscovery)
                    }
                    BluetoothProfile.STATE_CONNECTING -> Transition(ClientState.Connecting)
                    BluetoothProfile.STATE_DISCONNECTING -> null
                    else -> throw IllegalArgumentException("Unknown case: newState=$newState")
                }
            }
            is Event.OnBluetoothGattServicesDiscovered -> {
                val (status) = event

                when (state) {
                    is ClientState.Connecting,
                    is ClientState.Connected -> {
                        if (status == BluetoothGatt.GATT_SUCCESS)
                            Transition(ClientState.Connected, SideEffect.CacheDiscoveredServices)
                        else
                            null.also {
                                Log.e(
                                    TAG, "Service Discovery failed with status-code ($status). " +
                                            "No handling implemented."
                                )
                            }
                    }

                    else -> null.also {
                        Log.w(
                            TAG,
                            "Unexpected case: Received \"OnBluetoothGattServicesDiscovered\"" +
                                    "in unexpected state: $state"
                        )
                    }
                }
            }
        }

        // Execute transition
        Log.d(TAG, "Executing transition: $transition")
        if (transition != null) {
            val (newState, sideEffect) = transition

            _latestClientState.value = newState
            if (sideEffect != null) {
                when (sideEffect) {
                    is SideEffect.ResetBluetoothState -> resetBluetoothState()
                    is SideEffect.TriggerServiceDiscovery -> discoverServices()
                    is SideEffect.CacheDiscoveredServices -> cacheDiscoveredServices()
                }
            }
        }
    }

    private inner class ClientBluetoothGattCallback : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            // Make sure it's our GATT-client.
            if (gatt != bluetoothGatt) return
            val loggingHandler: (tag: String?, msg: String) -> Int =
                if (status == BluetoothGatt.GATT_SUCCESS) Log::d else Log::w
            loggingHandler(
                TAG, "onConnectionStateChange() - " +
                        "gatt=$gatt status=$status newState=$newState"
            )

            transition(Event.OnBluetoothGattConnectionStateChange(status, newState))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            // Make sure it's our GATT-client.
            if (gatt != bluetoothGatt) return

            val loggingHandler: (tag: String?, msg: String) -> Int =
                if (status == BluetoothGatt.GATT_SUCCESS) Log::d else Log::w
            loggingHandler(TAG, "onServicesDiscovered - received: $status")

            transition(Event.OnBluetoothGattServicesDiscovered(status))
        }
    }
}
