package de.kishorrana.signalboy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.kishorrana.signalboy.signalboyservice.SignalboyService
import de.kishorrana.signalboy.signalboyservice.SignalboyService.State
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.dropWhile
import kotlinx.parcelize.Parcelize

private const val TAG = "SignalboyFacade"

class SignalboyFacade : LifecycleService() {
    val state: State
        get() = service.state

    private val binder = LocalBinder()

    private val scope: CoroutineScope

    init {
        val exceptionHandler = CoroutineExceptionHandler { context, err ->
            Log.e(TAG, "Catching uncaught exception - context=$context error:", err)
        }
        scope = CoroutineScope(
            Dispatchers.Default +
                    SupervisorJob(lifecycleScope.coroutineContext.job) +
                    exceptionHandler +
                    CoroutineName("SignalboyFacade")
        )
    }

    private lateinit var service: SignalboyService
    private var serviceStateObserving: Job? = null
    private var connectionSupervising: Job? = null

    private var onConnectionStateUpdateListener: OnConnectionStateUpdateListener? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)

        val configuration: Configuration =
            intent.getParcelableExtra("configuration") ?: Configuration.Default

        service = makeSignalboyService(this, getDefaultAdapter(this), configuration)
            .apply {
                serviceStateObserving?.cancel()
                serviceStateObserving = scope.launch {
                    latestState
                        // Discard until first connection-attempt has been made.
                        .dropWhile { it is State.Disconnected }
                        .collect { newValue ->
                            withContext(Dispatchers.Main) {
                                Log.v(TAG, "state=$state")
                                onConnectionStateUpdateListener?.stateUpdated(newValue)
                            }
                        }
                }

                connectionSupervising?.cancel()
                connectionSupervising = scope.launchConnectionSupervising(this)
            }

        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connectionSupervising?.cancel()
        serviceStateObserving?.cancel()
        service.destroy()

        return super.onUnbind(intent)
    }

    fun tryTriggerSync(): Boolean = service.tryTriggerSync()

    fun setOnConnectionStateUpdateListener(listener: OnConnectionStateUpdateListener) {
        onConnectionStateUpdateListener = listener
    }

    fun unsetOnConnectionStateUpdateListener() {
        onConnectionStateUpdateListener = null
    }

    fun sendEvent() {
        scope.launch { service.sendEvent() }
    }

    //region Helper
    /**
     * Launches child-job that observes the connection-state of the specified SignalboyService-
     * instance and triggers actions like reconnect when the connection is dropped.
     *
     * @param service
     * @return The job that performs the supervision of the service's connection-state.
     */
    private fun CoroutineScope.launchConnectionSupervising(service: SignalboyService): Job =
        launch {
            suspend fun connect(reconnectAttempt: Int = 0) {
                // Backoff strategy
                val delayMillis: Long? = when (reconnectAttempt) {
                    0 -> null
                    1 -> 1 * 1_000L
                    2 -> 5 * 1_000L
                    3 -> 20 * 1_000L
                    else -> 3 * MIN_IN_MILLISECONDS
                }
                if (delayMillis != null) {
                    Log.i(
                        TAG,
                        "Will launch next connection attempt in ${delayMillis / 1_000}s (backoff-strategy)..."
                    )
                    delay(delayMillis)
                }

                try {
                    service.connectToPeripheralAsync()
                } catch (cancellation: CancellationException) {
                    throw cancellation  // Cancel this job by user-request.
                } catch (err: Throwable) {
                    Log.w(
                        TAG,
                        "Connection attempt failed (reconnectAttempt=$reconnectAttempt) due to error:",
                        err
                    )
                    connect(reconnectAttempt + 1)
                }
            }

            suspend fun reconnectIfNeeded(state: State) {
                // Reconnect if connection was dropped due to an error.
                val disconnectCause = (state as? State.Disconnected)?.cause
                if (disconnectCause != null) {
                    Log.w(TAG, "Connection lost due to error:", disconnectCause)
                    connect(1)
                }
            }

            connect()
            service.latestState
                .collect { state -> reconnectIfNeeded(state) }
        }
    //endregion

    //region Factory
    private fun makeSignalboyService(
        context: Context,
        bluetoothAdapter: BluetoothAdapter,
        configuration: Configuration,
    ): SignalboyService {
        val (normalizationDelay) = configuration

        return SignalboyService(context, bluetoothAdapter, normalizationDelay)
    }
    //endregion

    companion object {
        const val EXTRA_CONFIGURATION = "EXTRA_CONFIGURATION"

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
            val requiredRuntimePermissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) {
                requiredRuntimePermissions.addAll(
                    listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            } else {
                requiredRuntimePermissions.addAll(
                    listOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                )
            }

            for (permission in requiredRuntimePermissions) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    throw MissingRequiredRuntimePermissionException(permission)
                }
            }

            if (!bluetoothAdapter.isEnabled) throw BluetoothDisabledException()

            Log.d(TAG, "Successfully verified all prerequisites.")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SignalboyFacade = this@SignalboyFacade
    }

    @Parcelize
    data class Configuration(
        /**
         * The fixed-delay that the resulting signals emitted by the Signalboy-device will be delayed.
         *
         * DISCUSSION: This delay is utilized to normalize the delay caused by network-latency (Bluetooth).
         * In order to produce the actual event-times, the (third-party) receiving system will have
         * to subtract the specified Normalization-Delay from the timestamps of the received
         * electronic TTL-events.
         */
        val normalizationDelay: Long
    ) : Parcelable {
        companion object {
            @JvmStatic
            val Default: Configuration by lazy { Configuration(normalizationDelay = 300L) }
        }
    }

    fun interface OnConnectionStateUpdateListener {
        fun stateUpdated(state: State)
    }
}