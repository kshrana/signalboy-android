package com.example.signalboycentral

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.signalboycentral.databinding.ActivityDeviceManagementBinding
import kotlinx.coroutines.launch

private const val TAG = "DeviceManagementActivity"

// This Activity will only be used for development and debugging.
@SuppressLint("MissingPermission", "SetTextI18n")
class DeviceManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceManagementBinding
    private val companionDeviceController: CompanionDeviceController by lazy {
        CompanionDeviceController(this, activityResultRegistry)
    }
    private val bluetoothGattCallback by lazy { BluetoothGattCallbackImplementation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        with(binding) {
            buttonRefresh.setOnClickListener { onRefreshButtonClick() }
            buttonAssociate.setOnClickListener { onAssociateButtonClick() }
            buttonConnect.setOnClickListener { onConnectButtonClick() }
            buttonClearAssociations.setOnClickListener { onClearAssociationsButtonClick() }
        }

        updateView()
    }

    override fun onDestroy() {
        super.onDestroy()

        companionDeviceController.destroy()
    }

    override fun onSupportNavigateUp(): Boolean = true.also {
        onBackPressed()
    }

    private fun onRefreshButtonClick() {
        updateView()
    }

    private fun onAssociateButtonClick() {
        lifecycleScope.launch {
            val device: BluetoothDevice? = try {
                companionDeviceController.associate()
            } catch (exception: CompanionDeviceController.AssociationFailureException) {
                val (error) = exception
                null.also {
                    Toast
                        .makeText(this@DeviceManagementActivity, error, Toast.LENGTH_LONG)
                        .show()
                }
            } catch (exception: CompanionDeviceController.UserCancellationException) {
                null.also {
                    Toast
                        .makeText(
                            this@DeviceManagementActivity,
                            "Association request has been canceled by the user.",
                            Toast.LENGTH_LONG
                        )
                        .show()
                }
            }
            updateView()

            if (device != null) {
                Log.i(TAG, "onAssociateButtonClick(): success! – device.address=${device.address}")
                val gatt = device.connectGatt(
                    this@DeviceManagementActivity,
                    false,
                    bluetoothGattCallback
                )
//                device.createBond()
            }
        }
    }

    private fun onConnectButtonClick() {
        connectToAnyAssociatedDevice()
    }

    private fun updateView() {
        binding.buttonConnect.isEnabled = companionDeviceController.associations.isNotEmpty()
        binding.buttonClearAssociations.isEnabled =
            companionDeviceController.associations.isNotEmpty()
        updateTextView()
    }

    private fun updateTextView() {
        val associationsDescription =
            companionDeviceController.associations.map { "  • $it" }.joinToString("\n") { it }
        binding.textView.text = "Associations:\n$associationsDescription"
    }

    private fun connectToAnyAssociatedDevice() {
        val associations = companionDeviceController.associations
        if (associations.isEmpty()) throw IllegalStateException()

        // Connect to first device, that is retrievable.
        for (association in associations) {
            val isAddressFound = connect(association, bluetoothGattCallback)
            if (isAddressFound) break
        }
    }

    // TODO: Extract helper-method
    private fun connect(address: String, callback: BluetoothGattCallback): Boolean {
        val bluetoothGatt: BluetoothGatt?

        BluetoothAdapter.getDefaultAdapter()?.let { adapter ->
            try {
                val device =
                    adapter.getRemoteDevice(address)
                // connect to the GATT server on the device
                bluetoothGatt = device.connectGatt(this, false, callback)
                return true
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Device not found with provided address. Unable to connect.")
                return false
            }
        } ?: run {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return false
        }
    }

    private fun onClearAssociationsButtonClick() =
        companionDeviceController.clearAssociations()
            .also { updateView() }

    private inner class BluetoothGattCallbackImplementation : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status newState:$newState")
            requireNotNull(gatt)

            if (newState == STATE_CONNECTED) {
                runOnUiThread {
                    Toast
                        .makeText(
                            this@DeviceManagementActivity,
                            "Device connected (${gatt.device.address}).",
                            Toast.LENGTH_LONG
                        )
                        .show()
                }

                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.i(TAG, "onServicesDiscovered: ${(gatt?.services ?: emptyList()).map { it.uuid }}")

            if (status == GATT_SUCCESS) {
                gatt?.readCharacteristic(gatt.services.firstOrNull()?.characteristics?.firstOrNull())
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.i(TAG, "onCharacteristicRead: characteristic.value=${characteristic!!.value}")
        }
    }
}
