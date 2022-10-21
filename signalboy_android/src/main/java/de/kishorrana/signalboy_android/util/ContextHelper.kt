package de.kishorrana.signalboy_android.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.ContextCompat

internal class ContextHelper {
    companion object {
        fun getBluetoothAdapter(context: Context): BluetoothAdapter {
            with(ContextCompat.getSystemService(context, BluetoothManager::class.java)) {
                requireNotNull(this) {
                    "Unable to obtain a BluetoothAdapter." +
                            " Tip: BLE can be required per the <uses-feature> tag" +
                            " in the AndroidManifest.xml"
                }
                return adapter
            }
        }
    }
}
