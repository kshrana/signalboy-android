package de.kishorrana.signalboy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import de.kishorrana.signalboy.client.Client
import de.kishorrana.signalboy.client.NoConnectionAttemptsLeftException
import de.kishorrana.signalboy.client.State
import de.kishorrana.signalboy.scanner.Scanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "SignalboyService"

class SignalboyService(
    // Reimplementing `SignalboyService` as an Android Bound Service would
    // make capturing the Context unnecessary.
    //
    // s. Android SDK documentation for a reference implementation:
    // https://developer.android.com/guide/topics/connectivity/bluetooth/connect-gatt-server#setup-bound-service
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter = getDefaultAdapter()
) {
    companion object {
        @JvmStatic
        fun getDefaultAdapter(): BluetoothAdapter =
            BluetoothAdapter.getDefaultAdapter() ?: throw Exception(
                "Unable to obtain a BluetoothAdapter. " +
                        "Tip: BLE can be required per the <uses-feature> tag " +
                        "in the AndroidManifest.xml"
            )
    }

    fun interface OnConnectionStateUpdateListener {
        fun stateUpdated(state: ConnectionState)
    }

    val connectionState: ConnectionState
        get() = latestConnectionState.value

    // Could be replaced by making use of `LifecycleScope` (s. [1])
    // when class would subclass an Android Lifecycle Class,
    // e.g. when implemented as a Bound Service.
    //
    // [1]: https://developer.android.com/topic/libraries/architecture/coroutines#lifecyclescope
    private val scope = MainScope()
    private val client by lazy { Client(context) }

    private val latestConnectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
            // Notify `OnConnectionStateUpdateListener` on any updates to `latestConnectionState`.
            .also { latestConnectionState ->
                scope.launch {
                    latestConnectionState.collect { newValue ->
                        onConnectionStateUpdateListener?.stateUpdated(newValue)
                    }
                }
            }
    private var connecting: Job? = null
    private var clientStateObserving: Job? = null

    private var onConnectionStateUpdateListener: OnConnectionStateUpdateListener? = null

    fun destroy() {
        client.destroy()
        scope.cancel("Parent SignalboyService-instance will be destroyed.")
    }

    fun setOnConnectionStateUpdateListener(listener: OnConnectionStateUpdateListener) {
        onConnectionStateUpdateListener = listener
    }

    fun unsetOnConnectionStateUpdateListener() {
        onConnectionStateUpdateListener = null
    }

    fun verifyPrerequisites() {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            requiredPermissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing required permission: $permission")
                throw MissingRequiredPermissionsException(permission)
            }
        }

        if (!bluetoothAdapter.isEnabled) throw BluetoothDisabledException()

        Log.d(TAG, "Successfully verified all prerequisites.")
    }

    /**
     * Make sure to set an Update-Listener beforehand (via [setOnConnectionStateUpdateListener]).
     *
     */
    fun tryConnectToPeripheral() = scope.launch {
        tryOrPublishFailedConnectionState { connectToPeripheralAsync() }
    }

    fun tryDisconnectFromPeripheral() = scope.launch {
        tryOrPublishFailedConnectionState { disconnectFromPeripheralAsync() }
    }

    /**
     * Make sure to set an Update-Listener beforehand (via [setOnConnectionStateUpdateListener]).
     *
     */
    fun connectToPeripheral() = scope.launch {
        tryOrPublishFailedConnectionState(true) { connectToPeripheralAsync() }
    }

    fun disconnectFromPeripheral() = scope.launch {
        tryOrPublishFailedConnectionState(true) { disconnectFromPeripheralAsync() }
    }

    private suspend fun connectToPeripheralAsync() = coroutineScope {
        Log.v(TAG, "connectToPeripheralAsync: I'm working in thread ${Thread.currentThread().name}")
        if (connecting?.isActive == true) {
            throw AlreadyConnectingException()
        }

        connecting = launch {
            latestConnectionState.value = ConnectionState.Connecting

            // Try to discover Peripheral.
            val device: BluetoothDevice
            try {
                val devices = Scanner(bluetoothAdapter).discoverPeripherals(
                    OutputService_UUID,
                    SCAN_PERIOD_IN_MILLIS,
                    true
                )
                Log.d(TAG, "Discovered devices (which match set filters): $devices")

                device = devices
                    .firstOrNull() ?: throw NoCompatiblePeripheralDiscovered(
                    "Cannot connect: No peripheral matching set filters found during scan."
                )
            } catch (err: Throwable) {
                throw err
            }

            // Try connecting to Peripheral.
            // But first setup observer for Client's connection-state and forward any events to
            // `self`'s `latestConnectionState`-Publisher.
            clientStateObserving = scope.launch {
                client.latestState
                    .onCompletion { err ->
                        // Observer is expected to complete when `clientStateObserving`-
                        // Job is cancelled.
                        Log.v(
                            TAG, "clientStateObserving: Completion due to error:", err
                        )
                    }
                    // Drop initial "Disconnected"-events as StateFlow emits
                    // initial value on subscribing.
                    .dropWhile { it is State.Disconnected }
                    .map(::convertFromClientState)
                    .collect { latestConnectionState.value = it }
            }

            val isSuccess: Boolean
            try {
                isSuccess = client.connectAsync(device)
            } catch (err: Throwable) {
                // Cancel client connection-state observing.
                clientStateObserving?.cancelAndJoin()
                throw err
            }

            if (isSuccess)
                Log.i(TAG, "Successfully connected to peripheral.")
            else
                Log.i(TAG, "Failed to connect to peripheral.")
        }
    }

    private suspend fun disconnectFromPeripheralAsync() {
        client.disconnectAsync().also {
            // Give clientStateObserving-Job time to process that the peripheral was
            // disconnected.
            yield()
        }
        clientStateObserving?.cancelAndJoin()
    }

    // Helper functions

    private fun convertFromClientState(state: State): ConnectionState = when (state) {
        is State.Disconnected -> ConnectionState.Disconnected(state.cause)
        is State.Connecting -> ConnectionState.Connecting
        is State.DiscoveringServices -> ConnectionState.Connecting
        is State.Connected -> ConnectionState.Connected
    }

    private fun publishFailedConnectionState(err: Throwable) {
        latestConnectionState.value = ConnectionState.Disconnected(err)
    }

    private fun publishFailedConnectionStateAndRethrow(err: Throwable): Nothing {
        publishFailedConnectionState(err)
        throw err
    }

    private suspend fun <R> tryOrPublishFailedConnectionState(
        shouldRethrow: Boolean = false,
        block: suspend () -> R
    ) {
        val errorHandler: (err: Throwable) -> Any =
            if (shouldRethrow) ::publishFailedConnectionStateAndRethrow else ::publishFailedConnectionState

        try {
            block()
        } catch (err: MissingRequiredPermissionsException) {
            errorHandler(err)
        } catch (err: NoCompatiblePeripheralDiscovered) {
            errorHandler(err)
        } catch (err: NoConnectionAttemptsLeftException) {
            errorHandler(err)
        } catch (err: Throwable) {
            Log.d(
                TAG, "tryOrPublishFailedConnectionState: Will rethrow unknown exception " +
                        "without further handling. Error:", err
            )
            throw err
        }
    }

    sealed interface ConnectionState {
        data class Disconnected(val cause: Throwable? = null) : ConnectionState
        object Connecting : ConnectionState
        object Connected : ConnectionState
    }
}
