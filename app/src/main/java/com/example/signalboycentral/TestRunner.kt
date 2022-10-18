package com.example.signalboycentral

import de.kishorrana.signalboy_android.service.SignalboyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class TestRunner {
    suspend fun execute(signalboyService: SignalboyService) {
        try {
            while (true) {
                signalboyService.trySendEvent()
                delay(1 * 1_000L)
            }
        } catch (err: CancellationException) {
            // Could trigger additional actions here, e.g. print statistics.
            throw err
        }
    }
}