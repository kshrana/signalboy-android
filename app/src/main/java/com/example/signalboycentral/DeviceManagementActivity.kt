package com.example.signalboycentral

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        with(binding) {
            buttonRefresh.setOnClickListener { onRefreshButtonClick() }
            buttonAssociate.setOnClickListener { onAssociateButtonClick() }
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
                Log.i(TAG, "onAssociateButtonClick(): success! – device.device=${device.address}")
//                device.createBond()
            }
        }
    }

    private fun updateView() {
        binding.buttonClearAssociations.isEnabled =
            companionDeviceController.associations.isNotEmpty()
        updateTextView()
    }

    private fun updateTextView() {
        val associationsDescription =
            companionDeviceController.associations.map { "  • $it" }.joinToString("\n") { it }
        binding.textView.text = "Associations:\n$associationsDescription"
    }

    private fun onClearAssociationsButtonClick() = companionDeviceController.clearAssociations()
        .also { updateView() }
}
