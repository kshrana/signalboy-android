package com.example.signalboycentral

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.signalboycentral.databinding.ActivityMainBinding
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import de.kishorrana.signalboy.AlreadyConnectingException
import de.kishorrana.signalboy.BluetoothDisabledException
import de.kishorrana.signalboy.NoCompatiblePeripheralDiscovered
import de.kishorrana.signalboy.SignalboyFacade
import de.kishorrana.signalboy.client.ConnectionTimeoutException
import de.kishorrana.signalboy.client.NoConnectionAttemptsLeftException
import de.kishorrana.signalboy.scanner.AlreadyScanningException
import de.kishorrana.signalboy.scanner.BluetoothLeScanFailed
import de.kishorrana.signalboy.signalboyservice.SignalboyService.State
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

private const val PERMISSION_REQUEST_BLUETOOTH = 0
private const val PERMISSION_REQUEST_LOCATION = 1

private val bluetoothRuntimePermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private val locationRuntimePermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
)

class MainActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback {

    private lateinit var binding: ActivityMainBinding

    private var signalboyFacade: SignalboyFacade? = null
    private val signalboyFacadeConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            signalboyFacade = (service as SignalboyFacade.LocalBinder).getService().apply {
                setOnConnectionStateUpdateListener(onStateUpdateListener)
            }

            updateView()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            Log.e(TAG, "onServiceDisconnected")
            signalboyFacade = null
        }
    }

    private val onStateUpdateListener = SignalboyFacade.OnConnectionStateUpdateListener {
        // Check for errors...
        (it as? State.Disconnected)?.cause?.let { err ->
            onConnectionError(err)
        }

        updateView()
    }

    private val pendingPermissionRequests = mutableListOf<Int>()

    private var testing: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener { onFabClicked() }
        binding.contentMain.buttonSync.setOnClickListener { signalboyFacade!!.tryTriggerSync() }
        binding.contentMain.buttonTest.apply {
            setOnClickListener {
                if (testing?.isActive == true) {
                    testing?.cancel()
                    text = getString(R.string.button_test_start)
                } else {
                    signalboyFacade?.let {
                        testing = lifecycleScope.launch { TestRunner().execute(it) }
                        text = getString(R.string.button_test_stop)
                    }
                }
            }
        }
        binding.contentMain.imageViewBtStatus.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(ShapeAppearanceModel.PILL)
            .build()

        updateView()
    }

    override fun onStop() {
        super.onStop()

        stopSignalboyFacade()
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

            PERMISSION_REQUEST_BLUETOOTH -> {
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

        when (val connectionState = signalboyFacade?.state) {
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

        if (signalboyFacade == null) {
            binding.fab.text = "Start"
            setFabIconDrawable(R.drawable.round_play_arrow_black_24dp)
        } else {
            binding.fab.text = "Stop"
            setFabIconDrawable(R.drawable.round_stop_black_24dp)
        }
    }

    private fun onFabClicked() {
        if (signalboyFacade == null) {
            startPermissionRequests()
        } else {
            stopSignalboyFacade()
        }
    }

    /**
     * Requests the [android.Manifest.permission.BLUETOOTH_CONNECT] permission.
     * If an additional rationale should be displayed, the user has to launch the request from
     * a SnackBar that includes additional information.
     */
    private fun requestBluetoothPermission() {
        // Request the permission. The result will be received in onRequestPermissionResult().
        fun requestRuntimePermissions() = ActivityCompat.requestPermissions(
            this,
            bluetoothRuntimePermissions,
            PERMISSION_REQUEST_BLUETOOTH
        )

        val shouldShowRequestPermissionRationale =
            bluetoothRuntimePermissions.any { runtimePermission ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, runtimePermission)
            }

        // Permission has not been granted and must be requested.
        if (shouldShowRequestPermissionRationale) {
            // Provide an additional rationale to the user if the permission was not granted
            // and the user would benefit from additional context for the use of the permission.
            // Display a SnackBar with a button to request the missing permission.
            Snackbar.make(binding.root, "bluetooth_access_required", Snackbar.LENGTH_INDEFINITE)
                .setAction("Request Permission") {
                    requestRuntimePermissions()
                }
                .show()

        } else {
            Snackbar.make(binding.root, "bluetooth_permission_not_available", Snackbar.LENGTH_SHORT)
                .show()

            requestRuntimePermissions()
        }
    }

    private fun requestLocationPermission() {
        // Request the permission. The result will be received in onRequestPermissionResult().
        fun requestPermission() = ActivityCompat.requestPermissions(
            this,
            locationRuntimePermissions,
            PERMISSION_REQUEST_LOCATION
        )

        val shouldShowRequestPermissionRationale =
            locationRuntimePermissions.any { runtimePermission ->
                ActivityCompat.shouldShowRequestPermissionRationale(this, runtimePermission)
            }

        if (shouldShowRequestPermissionRationale) {
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

    private fun checkForPendingRuntimePermissions(): List<Int> {
        val pendingRuntimePermissions = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (bluetoothRuntimePermissions.any { permission ->
                    ActivityCompat.checkSelfPermission(
                        this,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                }) {
                pendingRuntimePermissions.add(PERMISSION_REQUEST_BLUETOOTH)
            }
        } else {
            if (locationRuntimePermissions.any { permission ->
                    ActivityCompat.checkSelfPermission(
                        this,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                }) {
                pendingRuntimePermissions.add(PERMISSION_REQUEST_LOCATION)
            }
        }

        return pendingRuntimePermissions
    }

    private fun requestNextPermission() {
        if (pendingPermissionRequests.isEmpty()) {
            onPermissionsEnsured()
            return
        }

        when (val pendingPermissionRequest = pendingPermissionRequests[0]) {
            PERMISSION_REQUEST_BLUETOOTH -> requestBluetoothPermission()
            PERMISSION_REQUEST_LOCATION -> requestLocationPermission()
            else -> throw IllegalArgumentException("$pendingPermissionRequest is Unknown")
        }
    }

    private fun onPermissionsEnsured() {
        val bluetoothAdapter = SignalboyFacade.getDefaultAdapter(this)
        try {
            SignalboyFacade.verifyPrerequisites(this, bluetoothAdapter)
        } catch (err: Throwable) {
            Log.e(TAG, "Prerequisites are not met. Error: $err")
            return
        }

        startSignalboyFacade()
    }

    private fun startPermissionRequests() {
        pendingPermissionRequests.clear()
        pendingPermissionRequests.addAll(checkForPendingRuntimePermissions())

        // Starts the permission request flow that is followed by Signalboy
        // connecting to the peripheral.
        requestNextPermission()
    }

    private fun startSignalboyFacade() {
        Intent(this, SignalboyFacade::class.java)
            .putExtra(SignalboyFacade.EXTRA_CONFIGURATION, SignalboyFacade.Configuration.Default)
            .also { intent ->
                val result =
                    bindService(intent, signalboyFacadeConnection, Context.BIND_AUTO_CREATE)
                Log.d(TAG, "result=$result")
            }
    }

    private fun stopSignalboyFacade() {
        if (signalboyFacade == null) return

        signalboyFacade = null
        unbindService(signalboyFacadeConnection)

        updateView()
    }
}