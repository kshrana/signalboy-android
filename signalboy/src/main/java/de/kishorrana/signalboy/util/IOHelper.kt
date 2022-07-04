package de.kishorrana.signalboy.util

internal fun Boolean.toByteArray(): ByteArray {
    val buffer = ByteArray(1)
    buffer[0] = if (this) 0x01 else 0x00

    return buffer
}

internal fun Boolean.Companion.fromByteArrayLE(byteArrayLE: ByteArray): Boolean {
    require(byteArrayLE.isNotEmpty()) { "Array is empty." }

    // We will just truncate the byte array by interpreting the first byte as the
    // boolean to be returned.
    return when (littleEndianConversion(byteArrayLE)) {
        0 -> false
        else -> true
    }
}

internal fun Int.toByteArrayLE(): ByteArray {
    val buffer = ByteArray(UInt.SIZE_BYTES)
    writeIntToByteBuffer(buffer, 0, this)

    return buffer
}

internal fun UInt.toByteArrayLE(): ByteArray = toInt().toByteArrayLE()

internal fun Int.Companion.fromByteArrayLE(byteArrayLE: ByteArray): Int = littleEndianConversion(byteArrayLE)

internal fun UInt.Companion.fromByteArrayLE(byteArrayLE: ByteArray): UInt =
    Int.fromByteArrayLE(byteArrayLE).toUInt()

// private

private fun writeIntToByteBuffer(buffer: ByteArray, offset: Int, data: Int) {
    buffer[offset + 0] = (data shr 0).toByte()
    buffer[offset + 1] = (data shr 8).toByte()
    buffer[offset + 2] = (data shr 16).toByte()
    buffer[offset + 3] = (data shr 24).toByte()
}

private fun littleEndianConversion(byteArrayLE: ByteArray): Int {
    require(byteArrayLE.size <= Int.SIZE_BYTES) {
        "length must not exceed Int.SIZE_BYTES=${Int.SIZE_BYTES}, got: ${byteArrayLE.size}"
    }

    var result = 0
    for (i in byteArrayLE.indices) {
        result = result or (byteArrayLE[i].toInt() shl 8 * i)
    }
    return result
}
