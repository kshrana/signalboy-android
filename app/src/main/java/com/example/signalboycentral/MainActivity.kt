package com.example.signalboycentral

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.signalboycentral.databinding.ActivityMainBinding
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import de.kishorrana.signalboy.AlreadyConnectingException
import de.kishorrana.signalboy.BluetoothDisabledException
import de.kishorrana.signalboy.NoCompatiblePeripheralDiscovered
import de.kishorrana.signalboy.Signalboy
import de.kishorrana.signalboy.client.ConnectionTimeoutException
import de.kishorrana.signalboy.client.NoConnectionAttemptsLeftException
import de.kishorrana.signalboy.scanner.AlreadyScanningException
import de.kishorrana.signalboy.scanner.BluetoothLeScanFailed
import de.kishorrana.signalboy.signalboyservice.SignalboyService.State

private const val TAG = "MainActivity"

const val PERMISSION_REQUEST_BLUETOOTH_CONNECT = 0
const val PERMISSION_REQUEST_LOCATION = 1

class MainActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback {

    private lateinit var binding: ActivityMainBinding

    private val onStateUpdateListener = Signalboy.OnConnectionStateUpdateListener {
        // Check for errors...
        (it as? State.Disconnected)?.cause?.let { err ->
            onConnectionError(err)
        }

        updateView()
    }

    private val pendingPermissionRequests = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener { onFabClicked() }
        binding.contentMain.buttonSync.setOnClickListener {
            Signalboy.tryTriggerSync()
        }
        binding.contentMain.imageViewBtStatus.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(ShapeAppearanceModel.PILL)
            .build()

        updateView()
    }

    override fun onStart() {
        super.onStart()
        Signalboy.setOnConnectionStateUpdateListener(onStateUpdateListener)
    }

    override fun onStop() {
        super.onStop()
        Signalboy.unsetOnConnectionStateUpdateListener()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_LOCATION -> {
                // Request for location permission.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission has been granted.
                    Snackbar.make(
                        binding.root,
                        "location_permission_granted",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()

                    pendingPermissionRequests.remove(requestCode)
                    requestNextPermission()
                } else {
                    // Permission request was denied.
                    Snackbar.make(binding.root, "location_permission_denied", Snackbar.LENGTH_SHORT)
                        .show()
                }
            }

            PERMISSION_REQUEST_BLUETOOTH_CONNECT -> {
                // Request for bluetooth permission.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission has been granted.
                    Snackbar.make(
                        binding.root,
                        "bluetooth_permission_granted",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()

                    pendingPermissionRequests.remove(requestCode)
                    requestNextPermission()
                } else {
                    // Permission request was denied.
                    Snackbar.make(
                        binding.root,
                        "bluetooth_permission_denied",
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
            }

            else -> throw IllegalArgumentException("$requestCode is Unknown")
        }
    }

    /**
     * Handles connection-errors received by SignalboyService.
     *
     * @param err Exception thrown by SignalboyService.
     */
    private fun onConnectionError(err: Throwable) {
        fun logErrorAndShowErrorDialog(cause: Throwable, message: String) {
            Log.e(TAG, message)
            AlertDialog.Builder(this)
                .setTitle("Connection Error: ${cause.javaClass.simpleName}")
                .setMessage(message)
                .setPositiveButton(
                    R.string.ok,
                    null
                )
                .show()
        }

        when (err) {
            // Exceptions that may be thrown *before* attempting to connect:
            is BluetoothDisabledException,
            is AlreadyScanningException,
            is AlreadyConnectingException -> {
                logErrorAndShowErrorDialog(err, "Failed to connect due to error - error=$err")
                /* no-op */
            }

            is BluetoothLeScanFailed -> {
                logErrorAndShowErrorDialog(err, "Failed to connect due to error - error=$err")
            }

            is NoCompatiblePeripheralDiscovered -> {
                logErrorAndShowErrorDialog(err, "Failed to connect due to error - error=$err")
            }

            is NoConnectionAttemptsLeftException -> {
                logErrorAndShowErrorDialog(err, "Failed to connect due to error - error=$err")
            }

            // Exceptions that may be thrown *during* connection-attempt or
            // after connection has been established:
            is ConnectionTimeoutException -> {
                logErrorAndShowErrorDialog(
                    err,
                    "Connection dropped due to connection-timeout exceeded - error=$err"
                )
            }

            // Unknown/unhandled exceptions:
            else -> {
                logErrorAndShowErrorDialog(
                    err,
                    "Connection failed due to unhandled error - error=$err"
                )
                // throw err
            }
        }
    }

    private fun updateView() {
        updateBTStatusIcon()
        updateFab()
    }

    private fun updateBTStatusIcon() {
        fun setImageViewDrawable(@DrawableRes resourceId: Int) {
            binding.contentMain.imageViewBtStatus.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    resourceId
                )
            )
        }

        when (val connectionState = Signalboy.tryGetConnectionState()) {
            is State.Disconnected -> {
                binding.contentMain.textPrimary.text = "Disconnected"
                binding.contentMain.textSecondary.text = "Cause: ${connectionState.cause}"
                setImageViewDrawable(R.drawable.baseline_bluetooth_black_24dp)
            }

            is State.Connecting -> {
                binding.contentMain.textPrimary.text = "Connecting"
                binding.contentMain.textSecondary.text = ""
                setImageViewDrawable(R.drawable.baseline_bluetooth_searching_black_24dp)
            }

            is State.Connected -> {
                val (deviceInformation, isSynced) = connectionState

                binding.contentMain.textPrimary.text = "Connected"
                binding.contentMain.textSecondary.text =
                    "• Device Information:" +
                            "\n\t• hardware-revision=${deviceInformation.hardwareRevision}" +
                            "\n\t• software-revision=${deviceInformation.softwareRevision}" +
                            "\n• isSynced=$isSynced"
                setImageViewDrawable(R.drawable.baseline_bluetooth_connected_black_24dp)
            }

            null -> {
                binding.contentMain.textPrimary.text = "Service not started"
                binding.contentMain.textSecondary.text = ""
                setImageViewDrawable(R.drawable.round_power_settings_new_black_24dp)
            }
        }
    }

    private fun updateFab() {
        fun setFabIconDrawable(@DrawableRes resourceId: Int) {
            binding.fab.icon = ContextCompat.getDrawable(this, resourceId)
        }

        when (Signalboy.isStarted) {
            false -> {
                binding.fab.text = "Start"
                setFabIconDrawable(R.drawable.round_play_arrow_black_24dp)
            }
            true -> {
                binding.fab.text = "Stop"
                setFabIconDrawable(R.drawable.round_stop_black_24dp)
            }
        }
    }

    private fun onFabClicked() {
        val deferred = {
            updateView()
        }

        if (!Signalboy.isStarted) {
            startSignalboy()
            deferred()
        } else {
            stopSignalboy(deferred)
        }
    }

    /**
     * Requests the [android.Manifest.permission.BLUETOOTH_CONNECT] permission.
     * If an additional rationale should be displayed, the user has to launch the request from
     * a SnackBar that includes additional information.
     */
    private fun requestBluetoothPermission() {
        // Request the permission. The result will be received in onRequestPermissionResult().
        fun requestPermission() = ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            PERMISSION_REQUEST_BLUETOOTH_CONNECT
        )

        // Permission has not been granted and must be requested.
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        ) {
            // Provide an additional rationale to the user if the permission was not granted
            // and the user would benefit from additional context for the use of the permission.
            // Display a SnackBar with a button to request the missing permission.
            Snackbar.make(binding.root, "bluetooth_access_required", Snackbar.LENGTH_INDEFINITE)
                .setAction("Request Permission") {
                    requestPermission()
                }
                .show()

        } else {
            Snackbar.make(binding.root, "bluetooth_permission_not_available", Snackbar.LENGTH_SHORT)
                .show()

            requestPermission()
        }
    }

    private fun requestLocationPermission() {
        // Request the permission. The result will be received in onRequestPermissionResult().
        fun requestPermission() = ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            PERMISSION_REQUEST_LOCATION
        )

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) {
            val alertDialogBuilder = AlertDialog.Builder(this)
            with(alertDialogBuilder) {
                setTitle("loc_req_title")
                setMessage("loc_req_msg")
                setPositiveButton("Request Permission") { _, _ -> requestPermission() }
            }
            alertDialogBuilder.create().show()
        } else {
            requestPermission()
        }
    }

    private fun checkForPendingPermissions(): List<Int> {
        val pendingPermissions = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            )
                pendingPermissions.add(PERMISSION_REQUEST_BLUETOOTH_CONNECT)
        } else {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            )
                pendingPermissions.add(PERMISSION_REQUEST_LOCATION)
        }

        return pendingPermissions
    }

    private fun requestNextPermission() {
        if (pendingPermissionRequests.isEmpty()) {
            onPermissionsEnsured()
            return
        }

        when (val pendingPermissionRequest = pendingPermissionRequests[0]) {
            PERMISSION_REQUEST_BLUETOOTH_CONNECT -> requestBluetoothPermission()
            PERMISSION_REQUEST_LOCATION -> requestLocationPermission()
            else -> throw IllegalArgumentException("$pendingPermissionRequest is Unknown")
        }
    }

    private fun onPermissionsEnsured() {
        val bluetoothAdapter = Signalboy.getDefaultAdapter(this)
        try {
            Signalboy.verifyPrerequisites(this, bluetoothAdapter)
        } catch (err: Throwable) {
            Log.e(TAG, "Prerequisites are not met. Error: $err")
            return
        }

        Signalboy.start(this, bluetoothAdapter)
    }

    private fun startSignalboy() {
        pendingPermissionRequests.clear()
        pendingPermissionRequests.addAll(checkForPendingPermissions())

        // Starts the permission request flow that is followed by Signalboy
        // connecting to the peripheral.
        requestNextPermission()
    }

    private fun stopSignalboy(completion: (() -> Unit)? = null) {
        Signalboy.stop(completion)
    }
}