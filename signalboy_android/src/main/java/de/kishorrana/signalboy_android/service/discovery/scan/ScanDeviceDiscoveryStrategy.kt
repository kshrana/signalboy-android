package de.kishorrana.signalboy_android.service.discovery.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.kishorrana.signalboy_android.service.PrerequisitesNode

// TODO: Implement scanning functionality.
class ScanDeviceDiscoveryStrategy {

    companion object {
        internal fun asPrerequisitesNode(
            context: Context,
            bluetoothAdapter: BluetoothAdapter
        ) = PrerequisitesNode { visitor ->
            if (!bluetoothAdapter.isEnabled) {
                visitor.addBluetoothEnabledUnmet()
            }

            val runtimePermissions = if (Build.VERSION.SDK_INT >= 31) {
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                listOf(
                    Manifest.permission.BLUETOOTH,
//                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            }

            runtimePermissions
                .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .takeIf { it.isNotEmpty() }
                ?.let { visitor.addUnmetRuntimePermissions(it) }
        }
    }
}