package de.kishorrana.signalboy_android.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.getSystemService

internal class ContextHelper {
    companion object {
        fun getBluetoothAdapter(context: Context): BluetoothAdapter =
            requireNotNull(context.getSystemService<BluetoothManager>()) {
                "Unable to obtain a BluetoothAdapter." +
                        " Tip: BLE can be required per the <uses-feature> tag" +
                        " in the AndroidManifest.xml"
            }
                .adapter
    }
}
