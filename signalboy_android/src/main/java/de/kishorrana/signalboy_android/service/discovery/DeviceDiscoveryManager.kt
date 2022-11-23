package de.kishorrana.signalboy_android.service.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanFilter
import android.companion.BluetoothLeDeviceFilter
import android.os.ParcelUuid
import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.client.util.isSuperset
import de.kishorrana.signalboy_android.service.discovery.companion_device.CompanionDeviceDiscoveryStrategy
import de.kishorrana.signalboy_android.service.discovery.companion_device.CompanionDeviceManagerFacade
import java.util.*
import java.util.regex.Pattern
import java.util.regex.Pattern.CASE_INSENSITIVE

// Instantiate using the classes' static factory-methods.
internal class DeviceDiscoveryManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val companionDeviceManagerFacade: CompanionDeviceManagerFacade,
    // User-Interaction is enabled, when passed userInteractionProxy is not `null`.
    private val userInteractionProxy: ActivityResultProxy?
) {
    suspend fun discoverAndConnect(client: Client, filter: DiscoveryFilter): BluetoothDevice =
        with(getStrategy(filter)) {
        discover(client, userInteractionProxy)
    }

    private fun getStrategy(filter: DiscoveryFilter): DeviceDiscoveryStrategy {
        // TODO: Implement flow, that decides whether to return with CompanionDevice- or Scanner-
        //   DiscoveryStrategy.
        return makeCompanionDeviceDiscoveryStrategy(filter)
    }

    class DiscoveryFilter private constructor(
        val advertisedServiceUUID: UUID?,
        val gattSignature: Map<UUID, List<UUID>>?,
        val addressBlacklistGetter: (() -> List<String>)?
    ) {
        class Builder {
            private var requiredAdvertisedServiceUUID: UUID? = null
            private var requiredGattSignature: Map<UUID, List<UUID>>? = null
            private var addressBlacklist: (() -> List<String>)? = null

            fun setAdvertisedServiceUuid(serviceUUID: UUID) = apply {
                requiredAdvertisedServiceUUID = serviceUUID
            }

            fun setGattSignature(signature: Map<UUID, List<UUID>>) = apply {
                requiredGattSignature = signature
            }

            fun setAddressBlacklistGetter(getter: () -> List<String>) = apply {
                this.addressBlacklist = getter
            }

            fun build() = DiscoveryFilter(
                advertisedServiceUUID = requiredAdvertisedServiceUUID,
                gattSignature = requiredGattSignature,
                addressBlacklistGetter = addressBlacklist
            )
        }
    }

    private fun makeCompanionDeviceDiscoveryStrategy(filter: DiscoveryFilter?):
            CompanionDeviceDiscoveryStrategy {
        fun getIsWorkaroundNecessary(): Boolean =
            // TODO: Find out when workaround is necessary and replace stub here.
            true

        fun makeAssociationRequestDeviceFilter(shouldApplyWorkaround: Boolean = false) =
            if (shouldApplyWorkaround) {
                // HACK: On some (older) Android versions the Companion Device Manager seemingly
                // fails to find **any** (Bluetooth-LE)-device when any filter is set for the
                // advertised service id.
                // As a workaround, we will fallback to specifying an name pattern as the device-
                // filter.
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(Pattern.compile(".*Signalboy.*", CASE_INSENSITIVE))
                    .setScanFilter(
                        ScanFilter.Builder()
                            // Setting service-uuids doesn't work with Companion Device Manager
                            // when searching Bluetooth Low Energy devices. Neither devices will be found
                            // nor the callback is getting called.
//                            .setServiceUuid(ParcelUuid(UUID.fromString("0000ec00-0000-1000-8000-00805f9b34fb")))
                            .build()
                    )
                    .build()
            } else {
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(
                        ScanFilter.Builder()
//                            .setDeviceName("echo")
//                            .setServiceUuid(ParcelUuid(UUID(0xEC00, -1L)))
//                            .setServiceUuid(ParcelUuid(UUID.fromString("0000ec00-0000-1000-8000-00805f9b34fb")))
                            // Apply advertisedServiceUUID (if any)
                            .run {
                                filter?.advertisedServiceUUID?.let {
                                    setServiceUuid(ParcelUuid(it))
                                } ?: this
                            }
                            .build()
                    )
                    .build()
            }

        val associationRequestDeviceFilter = makeAssociationRequestDeviceFilter(
            shouldApplyWorkaround = getIsWorkaroundNecessary()
        )
        val servicesPredicate = { services: List<BluetoothGattService> ->
            // Accept if device features gatt-signature.
            val requiredGattSignature = filter?.gattSignature ?: emptyMap()
            services.isSuperset(of = requiredGattSignature)
        }
        val addressPredicate = { address: String ->
            // Accept if device's address is not member of the blacklist.
            val addressBlacklist = filter?.addressBlacklistGetter?.invoke() ?: emptyList()
            !addressBlacklist.contains(address)
        }

        return CompanionDeviceDiscoveryStrategy(
            bluetoothAdapter,
            companionDeviceManagerFacade,
            associationRequestDeviceFilter,
            addressPredicate,
            servicesPredicate
        )
    }

    companion object {
        private const val TAG = "DeviceDiscoveryManager"
    }
}
