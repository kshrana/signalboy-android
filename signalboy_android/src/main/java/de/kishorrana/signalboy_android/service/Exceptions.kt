package de.kishorrana.signalboy_android.service

class MissingRequiredRuntimePermissionException(
    val permission: String? = null,
    cause: Throwable? = null
) : Exception(cause) {
    constructor(cause: Throwable?) : this(null, cause)
}

class BluetoothDisabledException : IllegalStateException("Bluetooth must be enabled.")

class CompanionDeviceSetupNotSupportedException : IllegalStateException(
    "uses-feature declaration for `PackageManager#FEATURE_COMPANION_DEVICE_SETUP` required" +
            " (AndroidManifest)."
)

class NoCompatiblePeripheralDiscovered(message: String?) : IllegalStateException(message)

class AlreadyConnectingException : IllegalStateException("Already connecting…")

class GattClientIsMissingAttributesException : IllegalStateException()

/// Thrown when Peripheral indicated a Connection Reject Request.
class ConnectionRejectedException : RuntimeException()
