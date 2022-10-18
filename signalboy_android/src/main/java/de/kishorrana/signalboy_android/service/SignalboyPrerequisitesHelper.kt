package de.kishorrana.signalboy_android.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "SignalboyPrerequisitesHelper"

class SignalboyPrerequisitesHelper {

    data class PrerequisitesResult(val unmetPrerequisite: Prerequisite?)

    sealed class Prerequisite {
        object BluetoothEnabledPrerequisite : Prerequisite()
        data class RuntimePermissionsPrerequisite(val permission: String) : Prerequisite()
    }

    companion object {
        @JvmStatic
        internal fun verifyPrerequisites(
            context: Context,
            bluetoothAdapter: BluetoothAdapter
        ): PrerequisitesResult {
            if (!bluetoothAdapter.isEnabled) {
                return PrerequisitesResult(Prerequisite.BluetoothEnabledPrerequisite)
            }

            val requiredRuntimePermissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) {
                requiredRuntimePermissions.addAll(
                    listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            } else {
                requiredRuntimePermissions.addAll(
                    listOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                )
            }

            for (permission in requiredRuntimePermissions) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return PrerequisitesResult(
                        Prerequisite.RuntimePermissionsPrerequisite(permission)
                    )
                }
            }

            // We're good – all prerequisites met.
            return PrerequisitesResult(null).also {
                Log.d(TAG, "Successfully verified all prerequisites.")
            }
        }
    }
}
