package de.kishorrana.signalboy_android.service.sync

class AlreadyTrainingException : kotlin.IllegalStateException()

class NoTrainingAttemptsLeftException : kotlin.IllegalStateException()

class IllegalStateException internal constructor(state: State) :
    kotlin.IllegalStateException(
        "Requested operation is not supported by SyncManager's current" +
                "state ($state)."
    )
