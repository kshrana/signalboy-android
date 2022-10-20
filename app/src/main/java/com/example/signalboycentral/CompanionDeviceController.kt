package com.example.signalboycentral

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.util.Log
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.channels.Channel
import java.util.regex.Pattern

private const val TAG = "CompanionDeviceController"

class CompanionDeviceController(
    private val context: Context,
    activityResultRegistry: ActivityResultRegistry
) {
    private val semaphore = Channel<Result<BluetoothDevice>>(1)

    private val deviceManager: CompanionDeviceManager by lazy {
        context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    }

    private val getResultForAssociationRequest =
        activityResultRegistry.register(
            "SELECT_DEVICE",
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->
            Log.d(TAG, "onActivityResult: resultCode=${activityResult.resultCode}")

            when (activityResult.resultCode) {
                Activity.RESULT_CANCELED -> {
                    Log.w(TAG, "onActivityResult: SELECT_DEVICE_REQUEST=CANCELED")

                    semaphore.trySend(Result.failure(UserCancellationException()))
                }
                Activity.RESULT_OK -> {
                    Log.i(TAG, "onActivityResult: SELECT_DEVICE_REQUEST=OK")
                    // The user chose to pair the app with a Bluetooth device.
                    // TODO: sdk >= 33: migrate to `CompanionDeviceManager.EXTRA_ASSOCIATION`
                    val item: ScanResult? =
                        activityResult.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
                    item?.device?.let { device ->
                        semaphore.trySend(Result.success(device))
                    }
                }
            }
        }

    val associations: List<String> get() = deviceManager.associations

    fun destroy() {
        getResultForAssociationRequest.unregister()
    }

    suspend fun associate(): BluetoothDevice {
        val deviceFilter: BluetoothLeDeviceFilter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("echo"))
            .setScanFilter(
                ScanFilter.Builder()
                    // Setting service-uuids doesn't work with Companion Device Manager
                    // when searching Bluetooth Low Energy devices. Neither devices will be found
                    // nor the callback is getting called.
//                    .setDeviceName("echo")
//                    .setServiceUuid(ParcelUuid(UUID(0xEC00, -1L)))
//                    .setServiceUuid(ParcelUuid(UUID.fromString("0000ec00-0000-1000-8000-00805f9b34fb")))
                    .build()
            )
            .build()

        val associationRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        Log.d(TAG, "getDevice(): will call CompanionDeviceManager.associate()...")
        // This will trigger the system to scan for a Companion Device
        // as narrowed down by our filters.
        deviceManager.associate(
            associationRequest,
            object : CompanionDeviceManager.Callback() {

                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.i(TAG, "CompanionDeviceManager.Callback.onDeviceFound")

                    val intentSenderRequest = IntentSenderRequest.Builder(intentSender).build()
                    // This will trigger the system-supplied popup, that allows the
                    // user to choose or grant permission to allow our app to associate
                    // with a device.
                    getResultForAssociationRequest.launch(intentSenderRequest)
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(TAG, "CompanionDeviceManager.Callback.onFailure – error:\n$error")

                    semaphore.trySend(Result.failure(AssociationFailureException(error)))
                }
            }, null
        )

        // TODO: Implement timeout cancellation
        return semaphore.receive().getOrThrow()
    }

    fun clearAssociations() = deviceManager.associations
        .forEach(deviceManager::disassociate)

    /**
     * User has denied association with a Companion Device in the chooser popup
     * (created and managed by the Companion Device Manager API) after the system had found a compatible
     * device via the Companion Device Manager API.
     */
    class UserCancellationException : IllegalStateException()

    /**
     * The Companion Device Manager API failed to find a device satisfying our filters.
     */
    data class AssociationFailureException(val error: CharSequence?) : IllegalStateException()
}
