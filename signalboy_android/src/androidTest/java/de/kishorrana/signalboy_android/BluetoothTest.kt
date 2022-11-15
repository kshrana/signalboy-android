package de.kishorrana.signalboy_android

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BluetoothTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @get:Rule
    var runtimePermissionRule: GrantPermissionRule = GrantPermissionRule.grant(BLUETOOTH_CONNECT)

    @Test
    fun bluetoothConnect() = runTest {
        Log.i(TAG, "bluetoothConnect")
        val semaphore = Channel<Int>(Channel.RENDEZVOUS)

        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(DEVICE_ADDRESS_1)
        val gatt = device.connectGatt(application, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                Log.i(TAG, "onConnectionStateChange: status=$status newState=$newState")
                semaphore.trySend(newState)
            }
        })

        assertThat(semaphore.receive(), `is`(BluetoothProfile.STATE_DISCONNECTED))
    }

    companion object {
        private const val TAG = "BluetoothTest"

        private const val DEVICE_ADDRESS_1 = "00:11:22:AA:BB:CC"
        private const val DEVICE_ADDRESS_2 = "11:22:33:BB:CC:DD"
    }
}