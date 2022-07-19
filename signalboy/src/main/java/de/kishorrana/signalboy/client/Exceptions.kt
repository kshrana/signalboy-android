package de.kishorrana.signalboy.client

import android.bluetooth.BluetoothGatt
import de.kishorrana.signalboy.util.toHexString

// Exceptions thrown during connection-attempt:
class NoConnectionAttemptsLeftException : RuntimeException()

// Exceptions thrown after a connection has been established:
class ConnectionTimeoutException : RuntimeException()

class OperationNotSupportedByCurrentStateException internal constructor(state: State) :
    IllegalStateException(
        "Requested operation is not supported by Client's current" +
                "state ($state)."
    )

// Thrown when GATT-Service Discovery operation failed for a connected Signalboy-peripheral.
class ServiceDiscoveryFailed(val gattClient: BluetoothGatt) : RuntimeException()

class ServiceNotFoundException : RuntimeException()
class CharacteristicNotFoundException : RuntimeException()
class DescriptorNotFoundException : RuntimeException()

// IO
class FailedToStartAsyncOperationException : RuntimeException()
class AsyncOperationFailedException(val status: Int) : RuntimeException(
    "Async operation failed with status-code: ${status.toByte().toHexString()}"
)
