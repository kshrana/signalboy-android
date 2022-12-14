package de.kishorrana.signalboy_android.service.scanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.util.Log
import de.kishorrana.signalboy_android.service.BluetoothDisabledException
import de.kishorrana.signalboy_android.service.MissingRequiredRuntimePermissionException
import kotlinx.coroutines.*
import java.util.*

private const val TAG = "SignalboyScanner"

internal class Scanner(private val bluetoothAdapter: BluetoothAdapter) {
    // String key is the address of the bluetooth device
    private val scanResults = mutableMapOf<String, BluetoothDevice>()
    private var errorCode: Int? = null

    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private var scanTimeoutJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private val scanSettings: ScanSettings

    init {
        scanSettings = buildScanSettings()
    }

    /**
     * Discover peripherals, optionally using a filter.
     *
     * @param addressFilter Peripherals with the given MAC-addresses will be filtered form the
     * results.
     * @param serviceUUIDFilter if specified, only Peripherals advertising a service with the
     * specified UUID will be returned in the results.
     * @param scanTimeout the scan will timeout after the specified timeout (in milliseconds).
     * @param shouldCancelAfterFirstMatch if `true`, scanning might be canceled before the timeout,
     * once at least one (matching) peripheral has been discovered.
     * @throws BluetoothLeScanFailed thrown if an exception has been encountered during the scan.
     * @return a list consisting of the discovered peripherals.
     */
    suspend fun discoverPeripherals(
        addressFilter: List<String>,
        serviceUUIDFilter: UUID?,
        scanTimeout: Long,
        shouldCancelAfterFirstMatch: Boolean = false
    ): List<BluetoothDevice> = coroutineScope {
        if (scanTimeoutJob != null) throw AlreadyScanningException()

        withContext(Dispatchers.IO) {
            Log.v(TAG, "discoverPeripherals: I'm working in thread ${Thread.currentThread().name}")

            startScan(
                addressFilter,
                serviceUUIDFilter?.let(::ParcelUuid),
                shouldCancelAfterFirstMatch
            )
            scanTimeoutJob = launch {
                Log.d(TAG, "Waiting for scan to complete...")
                delay(scanTimeout)
                Log.d(TAG, "Finished waiting.")
            }

            // Wait for scan-timeout.
            scanTimeoutJob?.join()

            stopScanning()
            scanTimeoutJob = null

            // Return results.
            if (errorCode == null) {
                scanResults.values.toList()
            } else {
                throw BluetoothLeScanFailed(errorCode!!)
            }
        }
    }

    private fun startScan(
        addressFilter: List<String>,
        serviceUUID: ParcelUuid?,
        shouldCancelAfterFirstMatch: Boolean
    ) {
        if (scanCallback == null) {
            // Reset
            scanResults.clear()
            errorCode = null

            if (!bluetoothAdapter.isEnabled) throw BluetoothDisabledException()
            try {
                bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

                Log.d(TAG, "Start Scanning")
                scanCallback = DeviceScanCallback(addressFilter, shouldCancelAfterFirstMatch)
                bluetoothLeScanner?.startScan(
                    buildScanFilters(serviceUUID),
                    scanSettings,
                    scanCallback
                )
            } catch (err: SecurityException) {
                throw MissingRequiredRuntimePermissionException(err)
            }
        } else {
            Log.d(TAG, "startScanning: already scanning")
        }
    }

    private fun stopScanning() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (err: SecurityException) {
            throw MissingRequiredRuntimePermissionException(err)
        } catch (err: Throwable) {
            Log.e(TAG, "Failed to stop scan due to error: $err")
        } finally {
            bluetoothLeScanner = null
            scanCallback = null
        }
    }

    /**
     * Return a List of [ScanFilter] objects to filter by Service UUID.
     */
    private fun buildScanFilters(serviceUUID: ParcelUuid?): List<ScanFilter> {
        val builder = ScanFilter.Builder()
        serviceUUID?.let { builder.setServiceUuid(it) }

        val filter = builder.build()
        return listOf(filter)
    }

    /**
     * Return a [ScanSettings] object.
     */
    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
    }

    /**
     * Custom ScanCallback object - adds found devices to list on success, displays error on failure.
     */
    private inner class DeviceScanCallback(
        val addressFilter: List<String>,
        val shouldCancelAfterFirstMatch: Boolean
    ) : ScanCallback() {
        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)

            Log.v(TAG, "on -> batchScanResults: results=$results addressFilter=$addressFilter")
            for (item in results) {
                item.device?.let { device ->
                    if (!addressFilter.contains(device.address)) {
                        scanResults[device.address] = device
                    } else {
                        Log.v(
                            TAG, "Discarding scan-result (address=${device.address} " +
                                    "is blacklisted)."
                        )
                    }
                }
            }

            if (shouldCancelAfterFirstMatch && scanResults.isNotEmpty()) {
                scanTimeoutJob?.cancel()
            }
        }

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            super.onScanResult(callbackType, result)

            Log.v(TAG, "on -> scanResult: result: $result addressFilter=$addressFilter")
            result.device?.let { device ->
                if (!addressFilter.contains(device.address)) {
                    scanResults[device.address] = device
                } else {
                    Log.v(
                        TAG, "Discarding scan-result (address=${device.address} " +
                                "is blacklisted)."
                    )
                }
            }

            if (shouldCancelAfterFirstMatch && scanResults.isNotEmpty()) {
                scanTimeoutJob?.cancel()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.w(TAG, "on-> scanFailed: errorCode: $errorCode")

            this@Scanner.errorCode = errorCode
            scanTimeoutJob?.cancel()
        }
    }
}
