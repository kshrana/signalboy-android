package de.kishorrana.signalboy_android.gatt

private const val CONNECTION_OPTION_REJECT_REQUEST = 1 shl 0

class SignalboyConnectionOptions(private val rawValue: Int) {
    val hasRejectRequest
        get() = rawValue and CONNECTION_OPTION_REJECT_REQUEST != 0
}
