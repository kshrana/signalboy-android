package de.kishorrana.signalboy_android.util

import com.natpryce.hamkrest.MatchResult
import com.natpryce.hamkrest.Matcher
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.describe
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class IOHelperTest {
    //region Conversion from Data
    @Test
    fun booleanFromByteArrayLE_false_isCorrect() {
        // Given:
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        // When:
        val value = Boolean.fromByteArrayLE(data)

        // Then:
        assertThat(value, equalTo(false))
    }

    @Test
    fun booleanFromByteArrayLE_true_isCorrect() {
        // Given:
        val data = byteArrayOf(0x01, 0x00, 0x00, 0x00)

        // When:
        val value = Boolean.fromByteArrayLE(data)

        // Then:
        assertThat(value, equalTo(true))
    }

    @Test
    fun intFromByteArrayLE_smallNumber_isCorrect() {
        // Given:
        val data = byteArrayOf(0x01, 0x00, 0x00, 0x00)

        // When:
        val value = Int.fromByteArrayLE(data)

        // Then:
        assertThat(value, equalTo(1))
    }

    @Test
    fun intFromByteArrayLE_allBitsSet_isCorrect() {
        // Given:
        val data =
            byteArrayOf((0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte())

        // When:
        val value: Int = Int.fromByteArrayLE(data)

        // Then:
        // interpret as signed
        assertThat(value, equalTo(-1))
        // interpret as unsigned
        assertThat(value.toUInt(), equalTo(UInt.MAX_VALUE))
    }

    @Test
    fun intFromByteArrayLE_minValue_isCorrect() {
        // Given:
        val data = byteArrayOf(0x00, 0x00, 0x00, (0x80u).toByte())

        // When:
        val value: Int = Int.fromByteArrayLE(data)

        // Then:
        // interpret as signed
        assertThat(value, equalTo(Int.MIN_VALUE))
        // interpret as unsigned
        assertThat(value.toUInt(), equalTo(2147483648U))
    }

    @Test
    fun uIntFromByteArrayLE_allBitsSet_isCorrect() {
        // Given:
        val data =
            byteArrayOf((0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte())

        // When:
        val value = UInt.fromByteArrayLE(data)

        // Then:
        assertThat(value, equalTo(UInt.MAX_VALUE))
    }
    //endregion

    //region Conversion to Data
    @Test
    fun booleanToByteArrayLE_false_isCorrect() {
        // Given:
        val value = false

        // When:
        val data = value.toByteArray()

        // Then:
        assertThat(data, equalToByteArray(byteArrayOf(0x00)))
    }

    @Test
    fun booleanToByteArrayLE_true_isCorrect() {
        // Given:
        val value = true

        // When:
        val data = value.toByteArray()

        // Then:
        assertThat(data, equalToByteArray(byteArrayOf(0x01)))
    }

    @Test
    fun intToByteArrayLE_smallNumber_isCorrect() {
        // Given:
        val value = 1

        // When:
        val data = value.toByteArrayLE()

        // Then:
        assertThat(data, equalToByteArray(byteArrayOf(0x01, 0x00, 0x00, 0x00)))
    }

    @Test
    fun intToByteArrayLE_allBitsSet_isCorrect() {
        // Given:
        val value = -1

        // When:
        val data = value.toByteArrayLE()

        // Then:
        assertThat(
            data, equalToByteArray(
                byteArrayOf(
                    (0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte()
                )
            )
        )
    }

    @Test
    fun intToByteArrayLE_minValue_isCorrect() {
        // Given:
        val value = Int.MIN_VALUE

        // When:
        val data = value.toByteArrayLE()

        // Then:
        assertThat(data, equalToByteArray(byteArrayOf(0x00, 0x00, 0x00, (0x80u).toByte())))
    }

    @Test
    fun uIntToByteArrayLE_allBitsSet_isCorrect() {
        // Given:
        val value = UInt.MAX_VALUE

        // When:
        val data = value.toByteArrayLE()

        // Then:
        assertThat(
            data, equalToByteArray(
                byteArrayOf(
                    (0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte(), (0xFFu).toByte()
                )
            )
        )
    }
    //endregion
}

//region Helpers
private fun equalToByteArray(expected: ByteArray?) = object : Matcher<ByteArray?> {
    override val description: String get() = "is equal to ${describe(expected)} (asHexString: ${expected?.toHexString()})"
    override val negatedDescription: String get() = "is not equal to ${describe(expected)} (asHexString: ${expected?.toHexString()})"

    override fun invoke(actual: ByteArray?): MatchResult {
        return when {
            expected == null && actual == null -> MatchResult.Match
            expected != null && actual == null -> MatchResult.Mismatch("was: null")
            expected == null && actual != null -> MatchResult.Mismatch("was: some")
            expected != null && actual != null -> {
                if (actual.withIndex().all { (index, byte) -> byte == expected[index] }) {
                    MatchResult.Match
                } else {
                    MatchResult.Mismatch(
                        "was: ${describe(actual)} (asHexString: ${actual.toHexString()})"
                    )
                }
            }
            else -> {
                throw IllegalStateException()
            }
        }
    }
}
//endregion
