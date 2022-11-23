package de.kishorrana.signalboy_android.service

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import de.kishorrana.signalboy_android.service.client.DefaultClient
import de.kishorrana.signalboy_android.service.discovery.companion_device.CompanionDeviceDiscoveryStrategy
import de.kishorrana.signalboy_android.service.discovery.scan.ScanDeviceDiscoveryStrategy

class SignalboyPrerequisitesHelper {
    sealed class DeviceDiscoveryStrategy {
        object ScanDeviceDiscoveryStrategy : DeviceDiscoveryStrategy()
        object CompanionDeviceDiscoveryStrategy : DeviceDiscoveryStrategy()
    }

    sealed class Prerequisite {
        object BluetoothEnabledPrerequisite : Prerequisite()
        data class RuntimePermissionsPrerequisite(val runtimePermissions: List<String>) :
            Prerequisite()
        data class UsesFeatureDeclarationsPrerequisite(val usesFeatures: List<String>) :
            Prerequisite()
    }

    data class PrerequisitesResult(val unmetPrerequisites: List<Prerequisite>)

    private class DefaultVisitor : PrerequisitesVisitor {
        private val unmetPrerequisites = mutableListOf<Prerequisite>()
        private val unmetRuntimePermissions = mutableListOf<String>()
        private val unmetUsesFeatures = mutableListOf<String>()

        override fun addBluetoothEnabledUnmet() {
            Prerequisite.BluetoothEnabledPrerequisite.let {
                unmetPrerequisites.addUnique(it)
            }
        }

        override fun addUnmetRuntimePermissions(runtimePermissions: List<String>) =
            runtimePermissions.forEach { unmetRuntimePermissions.addUnique(it) }

        override fun addUnmetUsesFeatureDeclarations(usesFeatures: List<String>) =
            usesFeatures.forEach { unmetUsesFeatures.addUnique(it) }

        fun verifyPrerequisites(nodes: List<PrerequisitesNode>): PrerequisitesResult {
            reset()
            nodes.forEach { it.accept(this) }
            return PrerequisitesResult(compilePrerequisitesResult())
        }

        private fun compilePrerequisitesResult(): List<Prerequisite> =
            unmetPrerequisites.toList() + listOfNotNull(
                unmetRuntimePermissions
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Prerequisite.RuntimePermissionsPrerequisite(it) },
                unmetUsesFeatures
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Prerequisite.UsesFeatureDeclarationsPrerequisite(it) },
            )

        private fun <E> MutableList<E>.addUnique(element: E): Boolean {
            if (!contains(element)) {
                add(element)
                return true
            }
            return false
        }

        private fun reset() {
            unmetPrerequisites.clear()
            unmetRuntimePermissions.clear()
            unmetUsesFeatures.clear()
        }
    }

    companion object {
        private const val TAG = "SignalboyPrerequisitesHelper"

        @JvmStatic
        internal fun verifyPrerequisites(
            context: Context,
            bluetoothAdapter: BluetoothAdapter,
            deviceDiscoveryStrategies: List<DeviceDiscoveryStrategy>
        ): PrerequisitesResult {
            return verifyPrerequisites(
                context,
                context.packageManager,
                bluetoothAdapter,
                deviceDiscoveryStrategies
            )
        }

        private fun verifyPrerequisites(
            context: Context,
            packageManager: PackageManager,
            bluetoothAdapter: BluetoothAdapter,
            deviceDiscoveryStrategies: List<DeviceDiscoveryStrategy>
        ): PrerequisitesResult {
            val nodes: List<PrerequisitesNode> = listOf(
                DefaultClient.asPrerequisitesNode(context, bluetoothAdapter)
            ) + deviceDiscoveryStrategies.map {
                when (it) {
                    is DeviceDiscoveryStrategy.ScanDeviceDiscoveryStrategy ->
                        ScanDeviceDiscoveryStrategy.asPrerequisitesNode(context, bluetoothAdapter)
                    is DeviceDiscoveryStrategy.CompanionDeviceDiscoveryStrategy ->
                        CompanionDeviceDiscoveryStrategy.asPrerequisitesNode(packageManager)
                }
            }

            return DefaultVisitor().verifyPrerequisites(nodes)
        }
    }
}

internal fun interface PrerequisitesNode {
    fun accept(visitor: PrerequisitesVisitor)
}

internal interface PrerequisitesVisitor {
    fun addBluetoothEnabledUnmet()
    fun addUnmetRuntimePermissions(runtimePermissions: List<String>)
    fun addUnmetUsesFeatureDeclarations(usesFeatures: List<String>)
}

