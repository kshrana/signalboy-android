package de.kishorrana.signalboy_android.service.client

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

internal data class Session(val device: BluetoothDevice, val gattClient: BluetoothGatt)
