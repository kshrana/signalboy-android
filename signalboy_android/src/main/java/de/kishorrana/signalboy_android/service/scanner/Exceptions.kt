package de.kishorrana.signalboy_android.service.scanner

class AlreadyScanningException : IllegalStateException("Already scanning…")

class BluetoothLeScanFailed(errorCode: Int) :
    Exception("Bluetooth-LE Scan failed (errorCode: $errorCode).")
