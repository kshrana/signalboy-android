package de.kishorrana.signalboy_android.service

class MissingRequiredRuntimePermissionException(
    val permission: String? = null,
    cause: Throwable? = null
) : Exception(cause) {
    constructor(cause: Throwable?) : this(null, cause)
}

class BluetoothDisabledException : Exception("Expects Bluetooth to be enabled.")

class NoCompatiblePeripheralDiscovered(message: String?) : Exception(message)

class AlreadyConnectingException : IllegalStateException("Already connecting…")

class GattClientIsMissingAttributesException : IllegalStateException()

/// Thrown when Peripheral indicated a Connection Reject Request.
class ConnectionRejectedException : RuntimeException()