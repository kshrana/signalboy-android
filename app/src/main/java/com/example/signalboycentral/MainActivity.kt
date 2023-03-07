package com.example.signalboycentral

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.signalboycentral.databinding.ActivityMainBinding
import com.example.signalboycentral.serial.SerialController
import com.example.signalboycentral.testrunner.EventsSchedule
import com.example.signalboycentral.testrunner.ScheduledEventsTestRunner
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import de.kishorrana.signalboy_android.service.*
import de.kishorrana.signalboy_android.service.client.ConnectionTimeoutException
import de.kishorrana.signalboy_android.service.client.NoConnectionAttemptsLeftException
import de.kishorrana.signalboy_android.service.scanner.AlreadyScanningException
import de.kishorrana.signalboy_android.service.scanner.BluetoothLeScanFailed
import kotlinx.coroutines.*

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

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback {

    private lateinit var serialController: SerialController
    private lateinit var binding: ActivityMainBinding

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            StringBuilder().apply {
                append("Action: ${intent.action}\n")
                append("URI: ${intent.toUri(Intent.URI_INTENT_SCHEME)}\n")
                toString().also { log ->
                    Log.d(TAG, log)
                    Toast.makeText(context, log, Toast.LENGTH_LONG).show()
                }
            }

            toggleTestRunner()
        }
    }

    private val onConnectionStateUpdateListener = SignalboyService.OnConnectionStateUpdateListener {
        // Check for errors...
        (it as? ISignalboyService.State.Disconnected)?.cause?.let { err ->
            onConnectionError(err)
        }

        updateView()
    }

    private val pendingPermissionRequests = mutableListOf<Int>()

    private val deferredUserInteractionRequestResults = mutableListOf<Job>()
    private var testing: Job? = null

    private val activityManager: ActivityManager by lazy { getSystemService()!! }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serialController = SerialController(this, getSystemService()!!)
        lifecycle.addObserver(serialController)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        with(binding) {
            fab.setOnClickListener { onFabClicked() }
        }
        with(binding.contentMain) {
            imageViewBtStatus.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(ShapeAppearanceModel.PILL)
                .build()
            toggleButtonGroupDiscovery.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    onDiscoveryModeButtonChecked(checkedId)
                }
            }
            buttonResolveUserInteractionRequest.apply {
                setOnClickListener {
                    lifecycleScope.launch {
                        val userInteractionRequestResolving =
                            checkNotNull(signalboyService).let { signalboyService ->
                                async {
                                    signalboyService.resolveUserInteractionRequest(
                                        this@MainActivity,
                                        SignalboyService.injectAssociateFragment(fragmentManager)
                                    )
                                }.also {
                                    deferredUserInteractionRequestResults.add(it)
                                    updateView()
                                }.also {
                                    yield()
                                    updateView()
                                }
                            }

                        userInteractionRequestResolving.await()
                            .also { result ->
                                Log.d(
                                    TAG, "Finished Job `userInteractionRequestResolving`" +
                                            " with result: $result"
                                )
                            }
                            .also { result ->
                                Toast
                                    .makeText(
                                        this@MainActivity,
                                        if (result.getOrNull() != null) {
                                            "Finished Job `userInteractionRequestResolving` successfully."
                                        } else {
                                            "Failed Job `userInteractionRequestResolving` due to error: " +
                                                    result.exceptionOrNull()!!
                                        },
                                        if (result.isSuccess) {
                                            Toast.LENGTH_SHORT
                                        } else {
                                            Toast.LENGTH_LONG
                                        }
                                    )
                                    .show()
                            }
                        updateView()
                    }
                }
            }

            buttonSync.setOnClickListener { signalboyService!!.tryTriggerSync() }
            buttonTest.apply {
                setOnClickListener { toggleTestRunner() }
            }
        }

        // SignalboyService might have been bound before, but we still have to
        // update its listener reference.
        signalboyService?.onConnectionStateUpdateListener = onConnectionStateUpdateListener

        updateView()

        ContextCompat.registerReceiver(
            this,
            broadcastReceiver,
            IntentFilter("com.example.signalboycentral.intent.action.TEST_RUN"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    var _onceToken = false
//    override fun onStart() {
//        super.onStart()
//
//        if (!_onceToken) {
//            onFabClicked()
//            _onceToken = true
//        }
//    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        unregisterReceiver(broadcastReceiver)

        super.onDestroy()
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
            R.id.action_refresh -> true.also { updateView() }
            R.id.action_companion_devices -> true.also {
                startActivity(Intent(this, DeviceManagementActivity::class.java))
            }
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
        updateDiscoveryModeViews()
        updateResolveUserInteractionRequestWidgets()
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

        with(binding.contentMain) {
            signalboyService.let { signalboyService ->
                if (activityManager.isSignalboyServiceRunning()) {
                    checkNotNull(signalboyService) { "Assertion failure: Service is expected to be bound, when running!" }

                    when (val connectionState = signalboyService.state) {
                        is ISignalboyService.State.Disconnected -> {
                            textPrimary.text = "Disconnected"
                            textSecondary.text = "Cause: ${connectionState.cause}"
                            setImageViewDrawable(R.drawable.baseline_bluetooth_black_24dp)
                        }

                        is ISignalboyService.State.Connecting -> {
                            textPrimary.text = "Connecting"
                            textSecondary.text = ""
                            setImageViewDrawable(R.drawable.baseline_bluetooth_searching_black_24dp)
                        }

                        is ISignalboyService.State.Connected -> {
                            val (deviceInformation, isSynced) = connectionState

                            textPrimary.text = "Connected"
                            textSecondary.text =
                                "• Device Information:" +
                                        "\n\t• local-name=${deviceInformation.localName}" +
                                        "\n\t• hardware-revision=${deviceInformation.hardwareRevision}" +
                                        "\n\t• software-revision=${deviceInformation.softwareRevision}" +
                                        "\n• isSynced=$isSynced"
                            setImageViewDrawable(R.drawable.baseline_bluetooth_connected_black_24dp)
                        }
                    }
                } else {
                    check(signalboyService == null) { "Assertion failure: Service is expected to be stopped, when not bound!" }

                    textPrimary.text = "Service not started"
                    textSecondary.text = ""
                    setImageViewDrawable(R.drawable.round_power_settings_new_black_24dp)
                }
            }
        }
    }

    private fun updateDiscoveryModeViews() {
        // Stub implementation to use, while Discovery-Strategy-Selection feature is WIP.
        fun stubImplementation() {
            with(binding.contentMain) {
                toggleButtonGroupDiscovery.check(R.id.button_discovery_companion_device)

                // TODO: Replace current, to be deprecated implementation with following
                //   out-commented implementation, once dependency
                //   "com.google.android.material" is upgraded (>1.8.0 required):
//                toggleButtonGroupDiscovery.isEnabled = false
                run { // Will be deprecated:
                    listOf(
                        buttonDiscoveryAuto,
                        buttonDiscoveryScanner,
                        buttonDiscoveryCompanionDevice,
                    )
                        .forEach { it.isEnabled = false }
                }
            }
        }

        stubImplementation()
    }

    private fun onDiscoveryModeButtonChecked(@IdRes checkedId: Int) {
        when (checkedId) {
            R.id.button_discovery_auto ->
                TODO("Implement for Discovery-Strategy-Selection feature")
            R.id.button_discovery_scanner ->
                TODO("Implement for Discovery-Strategy-Selection feature")
            R.id.button_discovery_companion_device -> {
                // TODO("Implement for Discovery-Strategy-Selection feature")
            }
            else -> throw IllegalArgumentException("Unknown case: checkedId=$checkedId")
        }
    }

    private fun updateTestButton() {
        binding.contentMain.buttonTest.text = if (testing?.isActive == true)
            getString(R.string.button_test_stop) else
            getString(R.string.button_test_start)
    }

    private fun updateFab() {
        fun setFabIconDrawable(@DrawableRes resourceId: Int) {
            binding.fab.icon = ContextCompat.getDrawable(this, resourceId)
        }

        if (signalboyService == null) {
            binding.fab.text = "Bind"
            setFabIconDrawable(R.drawable.baseline_north_east_black_24dp)
        } else {
            binding.fab.text = "Unbind"
            setFabIconDrawable(R.drawable.baseline_south_west_black_24dp)
        }
    }

    private fun updateResolveUserInteractionRequestWidgets() {
        with(binding.contentMain) {
            buttonResolveUserInteractionRequest.isEnabled =
                signalboyService?.hasAnyOpenUserInteractionRequest == true
            progressIndicatorResolveUserInteractionRequest.isVisible =
                deferredUserInteractionRequestResults
                    .any { it.isActive }
        }
    }

    private fun onFabClicked() {
        if (signalboyService == null) {
            // TODO: Restore scanning functionality
//            startPermissionRequests()
            // TODO: Remove stub (once scanning functionality is restored)
            bindSignalboyService()
        } else {
            unbindSignalboyService()
            updateView()
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
        val bluetoothAdapter = SignalboyService.getDefaultAdapter(this)
        val (unmetPrerequisite) = SignalboyService.verifyPrerequisites(this, bluetoothAdapter)
        if (unmetPrerequisite != null) {
            Log.e(TAG, "Prerequisites are not met. Unmet prerequisite: $unmetPrerequisite")
            return
        }

        bindSignalboyService()
    }

    private fun startPermissionRequests() {
        pendingPermissionRequests.clear()
        pendingPermissionRequests.addAll(checkForPendingRuntimePermissions())

        // Starts the permission request flow that is followed by Signalboy
        // connecting to the peripheral.
        requestNextPermission()
    }

    private fun bindSignalboyService() = bindSignalboyService(application) {
        it.onConnectionStateUpdateListener = onConnectionStateUpdateListener
        updateView()
    }

    private fun ActivityManager.isSignalboyServiceRunning() = getRunningServices(1_000).any {
        it.service == ComponentName(this@MainActivity, SignalboyService::class.java)
    }

    private fun toggleTestRunner() {
        if (testing?.isActive == true) {
            testing?.cancel()
            updateTestButton()
        } else {
            lifecycleScope.launch {
                signalboyService?.let { signalboyService ->
                    val testing = launch {
                        try {
                            if (!serialController.isConnected) {
                                Log.i(
                                    TAG,
                                    "SerialController is disconnected. Will try to connect..."
                                )

                                try {
                                    serialController.connect()
                                    Log.i(TAG, "Success: SerialController is connected.")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to connect to Serial:", e)
                                    cancel()
                                }
                            }

//                        FixedDelayTestRunner().execute(signalboyService)
//                        RandomDelayTestRunner().execute(signalboyService)
                            ScheduledEventsTestRunner(EventsSchedule.eventTimestamps)
                                .execute(signalboyService, serialController)
//                        ScheduledEventsTestRunner(EventsSchedule.event1_000Timestamps)
//                            .execute(signalboyService)
                        } catch (exception: Exception) {
                            Log.e(TAG, "Testing run failed due to:", exception)
                        }
                    }
                        .also { this@MainActivity.testing = it }
                    updateTestButton()

                    // Wait for test run to conclude…
                    testing.join()
                    updateTestButton()
                }
            }
        }
    }

    companion object {
        private var boundSignalboyService: BoundSignalboyService? = null
        private val signalboyService get() = boundSignalboyService?.getService()

        private fun bindSignalboyService(context: Context, completion: (SignalboyService) -> Unit) {
            val config = SignalboyService.Configuration.Default
//            SignalboyService.Configuration(
//                normalizationDelay = 100L,
//                isAutoReconnectEnabled = false
//            )
            BoundSignalboyService.instantiate(context, config) {
                boundSignalboyService = it
                completion(it.getService())
            }
        }

        private fun unbindSignalboyService() {
            checkNotNull(boundSignalboyService).unbind()
            boundSignalboyService = null
        }
    }
}
