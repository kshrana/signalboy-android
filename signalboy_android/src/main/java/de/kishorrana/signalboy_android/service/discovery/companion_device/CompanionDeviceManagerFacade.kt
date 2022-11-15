package de.kishorrana.signalboy_android.service.discovery.companion_device

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Parcelable
import android.util.Log
import androidx.core.content.getSystemService
import de.kishorrana.signalboy_android.service.BluetoothDisabledException
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy

internal class CompanionDeviceManagerFacade(
    private val bluetoothAdapter: BluetoothAdapter,
    private val originAwareCompanionDeviceManager: OriginAwareCompanionDeviceManager
) {
    val associations: List<String> get() = companionDeviceManager.associations

    private val companionDeviceManager = originAwareCompanionDeviceManager.wrappedValue

    /**
     * Request a new companion device association.
     * NOTE: All existing associations (if any) will be deleted, if association-request succeeds.
     *
     * @param associationRequestDeviceFilter The filter to use when requesting the Companion
     * Device Association with the Companion Device Manager.
     * @param userInteractionProxy The user-interface component that will communicate as a proxy during the request
     * with Android's CompanionDeviceManager and which helps in launching the device selection
     * dialog.
     * @param onAssociationPending Called when at least one device is found. Return `true` to
     * allow launch of the device selection dialog.
     * @param onFinish Association has been established, when result indicates success.
     * @param addressPredicate Allows caller to filter association-candidates. Return `true` to
     * allow device, or otherwise the device with the given address will be rejected.
     */
    fun requestNewAssociation(
        associationRequestDeviceFilter: BluetoothLeDeviceFilter?,
        userInteractionProxy: ActivityResultProxy,
        // If callback returns `true`, device chooser dialog will be shown.
        onAssociationPending: () -> Boolean,
        onFinish: (Result<BluetoothDevice>) -> Unit,
        addressPredicate: (String) -> Boolean
    ) {
        originAwareCompanionDeviceManager.ensureCanAssociate()
        if (!bluetoothAdapter.isEnabled) throw BluetoothDisabledException()

        val associationRequest: AssociationRequest = AssociationRequest.Builder()
            .run { associationRequestDeviceFilter?.let(::addDeviceFilter) ?: this }
//            .setSingleDevice(true)
            .build()

        Log.d(
            TAG,
            "requestAssociation(): will call CompanionDeviceDiscoveryStrategy.associate()..."
        )
        // TODO: Calling this API requires a uses-feature PackageManager.FEATURE_COMPANION_DEVICE_SETUP declaration in the manifest
        companionDeviceManager.associate(
            associationRequest,
            object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    Log.i(TAG, "CompanionDeviceManager.Callback -> onDeviceFound")

                    val shouldLaunchDeviceChooser = onAssociationPending()
                    if (shouldLaunchDeviceChooser) {
                        userInteractionProxy.launch(
                            chooserLauncher,
                            object : ActivityResultProxy.Callback {
                                override fun onActivityResult(resultCode: Int, data: Intent?) {
                                    when (resultCode) {
                                        Activity.RESULT_CANCELED -> {
                                            Log.w(
                                                TAG,
                                                "onActivityResult: SELECT_DEVICE_REQUEST=CANCELED"
                                            )
                                            onFinish(Result.failure(UserCancellationException()))
                                        }
                                        Activity.RESULT_OK -> {
                                            // The user chose a Bluetooth device (from a list of
                                            // devices filtered by our filters).
                                            Log.d(TAG, "onActivityResult: SELECT_DEVICE_REQUEST=OK")

                                            val device = when (val payload =
                                                data?.getParcelableExtra<Parcelable>(
                                                    CompanionDeviceManager.EXTRA_DEVICE
                                                )) {
                                                is BluetoothDevice -> payload
                                                is ScanResult -> payload.device
                                                else -> error("Unexpected payload structure.")
                                            }
                                            val address = device.address

                                            if (addressPredicate(address)) {
                                                clearAssociations(address)
                                                onFinish(Result.success(device))
                                            } else {
                                                onFinish(
                                                    Result.failure(
                                                        AssociationFailureDueToRejectionException()
                                                    )
                                                )
                                            }

                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    Log.e(
                        TAG,
                        "CompanionDeviceDiscoveryStrategy.Callback.onFailure – error:\n$error"
                    )
                    onFinish(Result.failure(AssociationFailureException(error)))
                }
            }, null
        )
    }

    private fun clearAssociations(vararg excluding: String) = associations
        .filterNot(excluding::contains)
        .forEach { companionDeviceManager.disassociate(it) }


    companion object {
        private const val TAG = "CompanionDeviceManagerFacade"
    }
}

// TODO: Move region's content to own file.
//region OriginAwareCompanionDeviceManager
internal open class OriginAwareCompanionDeviceManager private constructor(
    private val origin: Context,
    open val wrappedValue: CompanionDeviceManager
) {
    open fun ensureCanAssociate() {
        check(origin is Activity) {
            "Context is required to be an instance of `Activity`." +
                    " Older Android implementation cast the context" +
                    " (which was used to acquire the CompanionDeviceManager)" +
                    " to `Activity` when calling [CompanionDeviceManager.associate()]."
        }
    }

    companion object {
        fun instantiate(context: Context) = with(context) {
            OriginAwareCompanionDeviceManager(this, getSystemService()!!)
        }
    }
}
//endregion
