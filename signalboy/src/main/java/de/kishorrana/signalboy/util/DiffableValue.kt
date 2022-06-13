package de.kishorrana.signalboy.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.scan

internal data class DiffableValue<T>(val oldValue: T?, val value: T)

internal fun <T> Flow<T>.diffable(): Flow<DiffableValue<T>> = this
    .scan(null) { acc: DiffableValue<T>?, value -> DiffableValue(acc?.value, value) }
    .filterNotNull()
