package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothGatt
import de.kishorrana.signalboy.util.toHexString

// Exceptions thrown during connection-attempt:
class NoConnectionAttemptsLeftException : IllegalStateException()
class ConnectionAttemptCancellationException : IllegalStateException()

// Exceptions thrown after a connection has been established:
class ConnectionTimeoutException : IllegalStateException()

class OperationNotSupportedByCurrentStateException internal constructor(state: State) :
    IllegalStateException(
        "Requested operation is not supported by Client's current" +
                "state ($state)."
    )

// Thrown when GATT-Service Discovery operation failed for a connected Signalboy-peripheral.
class ServiceDiscoveryFailed(val gattClient: BluetoothGatt) : IllegalStateException()

class ServiceNotFoundException : IllegalStateException()
class CharacteristicNotFoundException : IllegalStateException()
class DescriptorNotFoundException : IllegalStateException()

// IO
class FailedToStartAsyncOperationException : IllegalStateException()
class AsyncOperationFailedException(val status: Int) : IllegalStateException(
    "Async operation failed with status-code: ${status.toByte().toHexString()}"
)
