package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

internal data class Session(val device: BluetoothDevice, val gattClient: BluetoothGatt)
