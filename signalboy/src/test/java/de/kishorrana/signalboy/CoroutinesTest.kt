@file:OptIn(ExperimentalCoroutinesApi::class)

package de.kishorrana.signalboy

import de.kishorrana.signalboy.util.DiffableValue
import de.kishorrana.signalboy.util.diffable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CoroutinesTest {
    @Test
    fun diffable_Sequence() = runTest {
        suspend fun generateDiffableValues(range: IntRange) = coroutineScope {
            val sequentialEmitting = with(MutableStateFlow(range.first)) {
                launch {
                    for (i in IntRange(range.first + 1, range.last)) {
                        delay(100L)
                        tryEmit(i)
                    }
                }
                asStateFlow()
            }

            sequentialEmitting
                .take(range.count())
                // ^ apply flow-truncating operator
                // in order to turn State Flow into a completing Flow.
                // Background: Shared Flows (and State Flows) won't terminate normally.
                .diffable()
                .toList()
        }

        val result = generateDiffableValues(1..3)
        assertArrayEquals(
            arrayOf(
                DiffableValue(null, 1),
                DiffableValue(1, 2),
                DiffableValue(2, 3),
            ), result.toTypedArray()
        )
    }

}