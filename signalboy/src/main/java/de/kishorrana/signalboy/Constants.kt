package de.kishorrana.signalboy

// Stops scanning after 10 seconds.
const val SCAN_PERIOD_IN_MILLIS: Long = 10_000L
const val CONNECTION_ATTEMPT_TIMEOUT_IN_MILLIS: Long = 3_000L

// Training interval is expected to match the BLE-connection's connection interval.
const val TRAINING_INTERVAL_IN_MILLIS = 20L
const val TRAINING_MESSAGES_COUNT = 3

const val MIN_IN_MILLISECONDS: Long = 1000 * 60
