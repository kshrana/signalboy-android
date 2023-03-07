package com.example.signalboycentral.testrunner

import android.util.Log
import com.example.signalboycentral.serial.SerialController
import de.kishorrana.signalboy_android.service.SignalboyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max

private const val THRESHOLD = 100

class ScheduledEventsTestRunner(val eventTimestamps: List<Long>) {

    /**
     * Executes the test runner. The test runner will emit events according to the specified
     * schedule.
     *
     * @param signalboyService
     */
    suspend fun execute(signalboyService: SignalboyService, serialController: SerialController) {
        val startTime = now()

        try {
            for (timestamp in eventTimestamps) {
                // non-blocking wait (up until target - threshold)
                delay(max(timestamp - THRESHOLD, 0) - (now() - startTime))
                // active wait (blocking thread) for accuracy
                while(timestamp - (now() - startTime) > 0) {/* no-op */}

                Log.d(TAG, "now=${now() - startTime}")
                // Notify event via Serial (for measuring latency)
                if (serialController.isConnected) {
                    serialController.println("")
                }
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