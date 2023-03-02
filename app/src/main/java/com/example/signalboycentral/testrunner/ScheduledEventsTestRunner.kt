package com.example.signalboycentral.testrunner

import android.util.Log
import de.kishorrana.signalboy_android.service.SignalboyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max

class ScheduledEventsTestRunner(val eventTimestamps: List<Long>) {
    /**
     * Executes the test runner. The test runner will emit events according to the specified
     * schedule.
     *
     * @param signalboyService
     */
    suspend fun execute(signalboyService: SignalboyService) {
        val startTime = now()

        try {
            for (timestamp in eventTimestamps) {
                delay(timestamp - (now() - startTime))
                signalboyService.trySendEvent()
            }
        } catch (err: CancellationException) {
            // Could trigger additional actions here, e.g. print statistics.
            throw err
        } finally {
            // These timestamps are expressed in relation to the Reference Timestamp.
            val timestamps = eventTimestamps.firstOrNull()?.let { referenceTimestamp ->
                eventTimestamps.drop(1).fold(emptyList<Long>()) { acc, next ->
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

    companion object {
        private const val TAG = "ScheduledEventsTestRunner"
    }
}