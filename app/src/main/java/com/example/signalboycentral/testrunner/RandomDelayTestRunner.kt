package com.example.signalboycentral.testrunner

import android.util.Log
import de.kishorrana.signalboy_android.service.SignalboyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

class RandomDelayTestRunner {
    /**
     * Executes the test runner. The test runner will emit events with a random-delay
     * indefinitely until canceled.
     *
     * @param signalboyService
     */
    suspend fun execute(signalboyService: SignalboyService) {
        val eventTimestamps = mutableListOf<Long>()

        try {
            while (eventTimestamps.count() < datapointsQuota) {
                if (eventTimestamps.isNotEmpty()) {
                    // skip delay for first iteration
                    delay(randomDelayMillis())
                }

                eventTimestamps.add(now())
                signalboyService.trySendEvent()
            }
        } catch (err: CancellationException) {
            // Could trigger additional actions here, e.g. print statistics.
            throw err
        } finally {
            // These timestamps are expressed in relation to the Reference Timestamp.
            val timestamps = eventTimestamps.removeFirstOrNull()?.let { referenceTimestamp ->
                eventTimestamps.fold(emptyList<Long>()) { acc, next ->
                    acc + listOf(
                        // Add delta from first timestamp
                        next - referenceTimestamp
                    )
                }
            }

            if (timestamps != null) {
                Log.i(TAG, "Event Log:\n$timestamps")
            }
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun randomDelayMillis() = Random.nextLong(1_000L, 45_000L)

    companion object {
        private const val TAG = "RandomDelayTestRunner"

        /**
         * Count of test data to generate. (Test-Runner will cancel, once this quota is met.)
         */
        private const val datapointsQuota = 20
    }
}