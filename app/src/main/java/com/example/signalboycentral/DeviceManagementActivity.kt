package com.example.signalboycentral

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.signalboycentral.databinding.ActivityDeviceManagementBinding

private const val TAG = "DeviceManagementActivity"

class DeviceManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceManagementBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
