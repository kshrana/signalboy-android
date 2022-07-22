package com.example.signalboycentral

import de.kishorrana.signalboy.Signalboy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class TestRunner {
    suspend fun execute() {
        try {
            while (true) {
                Signalboy.sendEvent()
                delay(1 * 1_000L)
            }
        } catch (err: CancellationException) {
            // Could trigger additional actions here, e.g. print statistics.
            throw err
        }
    }
}