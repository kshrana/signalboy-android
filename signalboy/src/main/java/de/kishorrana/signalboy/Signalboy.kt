package de.kishorrana.signalboy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

private const val TAG = "Signalboy"

class Signalboy {
    companion object {
        private val scope: CoroutineScope

        init {
            val exceptionHandler = CoroutineExceptionHandler { context, err ->
                Log.e(TAG, "Catching uncaught exception - context=$context error:", err)
            }
            scope = CoroutineScope(
                Dispatchers.Default +
                        SupervisorJob() +
                        exceptionHandler +
                        CoroutineName("Signalboy")
            )
        }

        private var service: SignalboyService? = null
        private var serviceStateObserving: Job? = null

        private var onConnectionStateUpdateListener: OnConnectionStateUpdateListener? = null

        @JvmStatic
        fun getDefaultAdapter(context: Context): BluetoothAdapter =
            with(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager) {
                adapter ?: throw Exception(
                    "Unable to obtain a BluetoothAdapter. " +
                            "Tip: BLE can be required per the <uses-feature> tag " +
                            "in the AndroidManifest.xml"
                )
            }

        @JvmStatic
        fun verifyPrerequisites(context: Context, bluetoothAdapter: BluetoothAdapter) {
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
        @JvmStatic
        fun start(
            context: Context,
            bluetoothAdapter: BluetoothAdapter
        ) {
            service = makeSignalboyService(context, bluetoothAdapter)
                .apply {
                    tryConnectToPeripheral()

                    serviceStateObserving?.cancel()
                    serviceStateObserving = scope.launch {
                        latestConnectionState.collect { newValue ->
                            withContext(Dispatchers.Main) {
                                Log.d(TAG, "connectionState=$connectionState")
                                onConnectionStateUpdateListener?.stateUpdated(newValue)
                            }
                        }
                    }
                }
        }

        @JvmStatic
        fun stop() {
            service?.tryDisconnectFromPeripheral()
        }

        /**
         * Try get connection state
         *
         * @return Returns `null` when stopped, or Signalboy Service when started.
         */
        @JvmStatic
        fun tryGetConnectionState(): SignalboyService.ConnectionState? = service?.connectionState

        @JvmStatic
        fun tryTriggerSync(): Boolean = service?.tryTriggerSync() ?: false

        @JvmStatic
        fun setOnConnectionStateUpdateListener(listener: OnConnectionStateUpdateListener) {
            onConnectionStateUpdateListener = listener
        }

        @JvmStatic
        fun unsetOnConnectionStateUpdateListener() {
            onConnectionStateUpdateListener = null
        }

        //region Factory
        private fun makeSignalboyService(context: Context, bluetoothAdapter: BluetoothAdapter):
                SignalboyService = SignalboyService(context, bluetoothAdapter)
        //endregion
    }

    fun interface OnConnectionStateUpdateListener {
        fun stateUpdated(state: SignalboyService.ConnectionState)
    }
}