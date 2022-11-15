package de.kishorrana.signalboy_android.service.discovery

import android.bluetooth.BluetoothDevice
import de.kishorrana.signalboy_android.service.client.Client

internal interface DeviceDiscoveryStrategy {
    /**
     * Discovers a supported device using the specified client.
     * User interaction might be resolved using the specified proxy (`userInteractionProxy`),
     * otherwise an UserInteractionRequiredException is thrown when user interaction
     * is required.
     */
    suspend fun discover(
        client: Client,
        userInteractionProxy: ActivityResultProxy? = null
    ): BluetoothDevice
}
