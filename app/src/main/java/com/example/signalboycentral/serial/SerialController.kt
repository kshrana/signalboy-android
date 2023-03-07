package com.example.signalboycentral.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.signalboycentral.BuildConfig
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import java.io.IOException

private const val INTENT_ACTION_GRANT_USB: String = BuildConfig.APPLICATION_ID + ".GRANT_USB"
private const val WRITE_WAIT_MILLIS = 2000
private const val READ_WAIT_MILLIS = 2000

private const val TAG = "SerialController"

class SerialController(
    private val context: Context,
    private val usbManager: UsbManager
) : DefaultLifecycleObserver, SerialInputOutputManager.Listener {
    private enum class UsbPermission {
        Unknown, Requested, Granted, Denied
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (INTENT_ACTION_GRANT_USB == intent.action) {
                usbPermissionState.value = if (intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                ) UsbPermission.Granted else UsbPermission.Denied
            }
        }
    }

    private var usbPermissionState = MutableStateFlow(UsbPermission.Unknown)

    private var usbIoManager: SerialInputOutputManager? = null
    private var usbSerialPort: UsbSerialPort? = null
    var isConnected = false
        private set

    override fun onStart(owner: LifecycleOwner) {
        ContextCompat.registerReceiver(
            context,
            broadcastReceiver,
            IntentFilter(INTENT_ACTION_GRANT_USB),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        context.unregisterReceiver(broadcastReceiver)
    }

    suspend fun connect() {
        if (isConnected) {
            Log.d(TAG, "connect(): Already connected.")
            return
        }

        // Find all available drivers from attached devices.
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            throw IllegalStateException("No driver found for attached devices.")
        }

        // Open a connection to the first available driver.
        val driver = availableDrivers[0]
        if (
            !usbManager.hasPermission(driver.device)
        ) {
            acquirePermission(driver.device)
        }

        try {
            val usbConnection: UsbDeviceConnection? = usbManager.openDevice(driver.device)
            checkNotNull(usbConnection) { "UsbManager failed to open connection to device." }

            usbSerialPort = driver.ports[0] // Most devices have just one port (port 0)
                .apply {
                    open(usbConnection)
                    setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                }
            usbIoManager = SerialInputOutputManager(usbSerialPort, this)
                .apply { start() }
            isConnected = true
        } catch (exception: Exception) {
            disconnect()
            throw exception
        }
    }

    fun disconnect() {
        isConnected = false

        // TODO: Stop UsbIoManager

        try {
            usbSerialPort?.close()
        } catch (ignored: IOException) {
        }
        usbSerialPort = null
    }

    /*
     * Serial
     */
    override fun onNewData(data: ByteArray) {
        Log.w(TAG, "onNewData: Not implemented.")
//        mainLooper.post(Runnable { receive(data) })
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "onRunError", e)
        Log.w(TAG, "Error handling not implemented.")
//        mainLooper.post(Runnable {
//            status("connection lost: " + e.message)
//            disconnect()
//        })
    }

    /*
     * Private
     */

    private suspend fun acquirePermission(usbDevice: UsbDevice) {
        usbPermissionState.value = UsbPermission.Requested

//        val flags = PendingIntent.FLAG_IMMUTABLE
        val usbPermissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(INTENT_ACTION_GRANT_USB),
            0
        )
        usbManager.requestPermission(usbDevice, usbPermissionIntent)

        // Wait for permission request result…
        val result = usbPermissionState
//            .onEach { Log.d(TAG, "state=$it") }
            .filter { listOf(UsbPermission.Denied, UsbPermission.Granted).contains(it) }
            .first()
        check(result == UsbPermission.Granted) {
            "User has denied permission required to access USB-device (usbDevice=${usbDevice})."
        }
    }

    fun println(string: String) {
        if (!isConnected) {
            throw IllegalStateException("Not connected.")
        }

        Log.d(TAG, "Will write line to Serial: \"$string\"")
        try {
            val str = string + '\n'
            val data = str.toByteArray()

            usbSerialPort!!.write(data, WRITE_WAIT_MILLIS)
        } catch (e: java.lang.Exception) {
            onRunError(e)
        }
    }

}