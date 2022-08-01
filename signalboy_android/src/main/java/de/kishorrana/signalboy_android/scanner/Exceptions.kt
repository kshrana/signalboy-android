package de.kishorrana.signalboy_android.scanner

class AlreadyScanningException : IllegalStateException("Already scanning…")

class BluetoothLeScanFailed(errorCode: Int) :
    Exception("Bluetooth-LE Scan failed (errorCode: $errorCode).")
