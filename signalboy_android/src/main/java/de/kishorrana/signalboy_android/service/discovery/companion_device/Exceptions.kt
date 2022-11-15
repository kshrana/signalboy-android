package de.kishorrana.signalboy_android.service.discovery.companion_device

internal data class IllegalStateException(val state: State) : kotlin.IllegalStateException(
    "Requested operation is not supported by CompanionDeviceDiscoveryStrategy's current" +
            "state (state=$state)."
)

class AssociationDiscoveryTimeoutException : kotlin.IllegalStateException(
    "Timeout occurred when awaiting a response from CompanionDeviceManager after" +
            " requesting an association (`CompanionDeviceManager.associate()`)."
)

/**
 * The Companion Device Manager API failed to find a device satisfying our filters.
 */
data class AssociationFailureException(val error: CharSequence?) : kotlin.IllegalStateException()

/**
 * User has interactively chosen a Peripheral-device, that has an active Reject-Request on this
 * Central-device.
 */
class AssociationFailureDueToRejectionException : kotlin.IllegalStateException()

/**
 * User has interactively denied association with a Companion Device in the selection dialog
 * (created and managed by the Companion Device Manager API) after the system had found a compatible
 * device via the Companion Device Manager API.
 */
class UserCancellationException : kotlin.IllegalStateException()
