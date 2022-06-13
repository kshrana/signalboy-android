package de.kishorrana.signalboy.scanner

class AlreadyScanningException : IllegalStateException("Already scanning…")

class BluetoothLeScanFailed(errorCode: Int) :
    Exception("Bluetooth-LE Scan failed (errorCode: $errorCode).")
