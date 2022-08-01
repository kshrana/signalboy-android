package de.kishorrana.signalboy_android.util

internal fun UByte.toHexString() = "0x" + ("%02x".format(this))
internal fun Byte.toHexString() = "0x" + ("%02x".format(this))

internal fun UInt.toHexString() = "0x" + ("%04x".format(this))
internal fun Int.toHexString() = "0x" + ("%04x".format(this))

internal fun ByteArray.toHexString() = "0x" + joinToString(" ") { "%02x".format(it) }
