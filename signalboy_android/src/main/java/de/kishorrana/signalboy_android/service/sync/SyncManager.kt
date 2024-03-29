package de.kishorrana.signalboy_android.service.sync

import android.util.Log
import com.tinder.StateMachine
import de.kishorrana.signalboy_android.TRAINING_INTERVAL_IN_MILLIS
import de.kishorrana.signalboy_android.TRAINING_MESSAGES_COUNT
import de.kishorrana.signalboy_android.service.client.Client
import de.kishorrana.signalboy_android.service.client.endpoint.readGattCharacteristicAsync
import de.kishorrana.signalboy_android.service.client.endpoint.startNotifyAsync
import de.kishorrana.signalboy_android.service.client.endpoint.writeGattCharacteristicAsync
import de.kishorrana.signalboy_android.service.gatt.TimeNeedsSyncCharacteristic
import de.kishorrana.signalboy_android.service.sync.Event.*
import de.kishorrana.signalboy_android.service.sync.State.*
import de.kishorrana.signalboy_android.util.fromByteArrayLE
import de.kishorrana.signalboy_android.util.now
import de.kishorrana.signalboy_android.util.toByteArrayLE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.CoroutineContext

private const val TAG = "SignalboySyncManager"

internal class SyncManager {
    private val _latestState: MutableStateFlow<State> = MutableStateFlow(Detached)
    val latestState: StateFlow<State> = _latestState.asStateFlow()

    private val stateManager = StateManager()
    private var syncing: Job? = null

    fun attach(context: CoroutineContext, client: Client) =
        stateManager.handleEvent(OnAttachRequest(context, client))

    fun detach() = stateManager.handleEvent(OnDetachRequest)

    /**
     * Triggers sync manually.
     * NOTE: **Must _not_ be called from outside of package.** Method is only accessible for
     * DEBUGGING.
     *
     */
    fun debugTriggerSync() {
        stateManager.handleEvent(OnSyncRequest)
    }

    inner class StateManager {
        val state: State
            get() = stateMachine.state

        private val stateMachine = StateMachine.create<State, Event, SideEffect> {
            initialState(Detached)
            state<Detached> {
                on<OnAttachRequest> {
                    val (context, client) = it

                    val scope = CoroutineScope(context).apply {
                        launchTimeNeedsSyncSubscriptionSetup(client)
                    }
                    transitionTo(Attaching(scope, client))
                }
                on<OnSyncRequired> { throw IllegalStateException(this) }
                on<OnSyncRequest> { throw IllegalStateException(this) }
            }
            state<Attaching> {
                on<OnDetachRequest> {
                    detach()
                    transitionTo(Detached)
                }
                on<OnAttachSuccess> { (initialTimeNeedsSyncValue, timeNeedsSyncSubscription) ->
                    if (Boolean.fromByteArrayLE(initialTimeNeedsSyncValue)) {
                        startSync()
                        transitionTo(Training(scope, client, timeNeedsSyncSubscription))
                    } else {
                        transitionTo(Synced(scope, client, timeNeedsSyncSubscription))
                    }
                }
                on<OnSyncRequired> { throw IllegalStateException(this) }
                on<OnSyncRequest> { throw IllegalStateException(this) }
            }
            state<Training> {
                on<OnDetachRequest> {
                    detach()
                    transitionTo(Detached)
                }
                on<OnSyncRequired> { throw AlreadyTrainingException() }
                on<OnSyncRequest> { throw AlreadyTrainingException() }
                on<OnTrainingTimeout> {
                    val delayMillis = 1 * 1_000L
                    val isAnyAttemptLeft = (attempt + 1 <= 3).also {
                        Log.v(TAG, "Training attempt failed (attempt=$attempt).")
                    }
                    if (isAnyAttemptLeft) {
                        Log.v(
                            TAG,
                            "Will launch next training attempt in ${delayMillis / 1_000}s..."
                        )
                        scheduleNextTrainingAttempt(delayMillis)

                        // Transition is triggered later (when `delayMillis` passed).
                        dontTransition()
                    } else {
                        Log.v(TAG, "No training attempts left.")
                        throw NoTrainingAttemptsLeftException()
                    }
                }
                on<OnTrainingRetry> {
                    startSync()
                    transitionTo(Training(scope, client, timeNeedsSyncSubscription, attempt + 1))
                }
                on<OnSyncSatisfy> { transitionTo(Synced(scope, client, timeNeedsSyncSubscription)) }
            }
            state<Synced> {
                on<OnDetachRequest> {
                    detach()
                    transitionTo(Detached)
                }
                on<OnSyncRequired> {
                    startSync()
                    transitionTo(Training(scope, client, timeNeedsSyncSubscription))
                }
                on<OnSyncRequest> {
                    startSync()
                    dontTransition()
                }
            }
            onTransition {
                val validTransition =
                    it as? StateMachine.Transition.Valid ?: return@onTransition

                if (validTransition.sideEffect != null) {
                    throw NotImplementedError() // No side-effects implemented yet
                }

                if (validTransition.fromState != validTransition.toState) {
                    _latestState.value = state
                }
            }
        }

        fun handleEvent(event: Event) {
            // Log.v(TAG, "handleEvent: I'm working in thread ${Thread.currentThread().name}")
            stateMachine.transition(event)  // StateMachine.transition() seems to be thread-safe.
        }

        private fun CoroutineScope.launchTimeNeedsSyncSubscriptionSetup(client: Client) {
            launch {
                val initialValue = client.readGattCharacteristicAsync(TimeNeedsSyncCharacteristic)

                // Subscribe to characteristic
                val subscription =
                    client.startNotifyAsync(TimeNeedsSyncCharacteristic) { (newValue) ->
                        handleTimeNeedsSyncCharacteristicNotification(newValue)
                    }

                stateMachine.transition(OnAttachSuccess(initialValue, subscription))
            }
        }

        private fun CoroutineScope.launchSyncResponseTimeout() {
            launch {
                try {
                    withTimeout(500L) {
                        awaitCancellation()
                    }
                } catch (err: TimeoutCancellationException) {
                    // Training did timeout
                    stateMachine.transition(OnTrainingTimeout)
                }
            }
        }

        private fun handleTimeNeedsSyncCharacteristicNotification(value: ByteArray) {
            val timeNeedsSync = Boolean.fromByteArrayLE(value).also {
                Log.v(TAG, "handleTimeNeedsSyncCharacteristic() - timeNeedsSync=$it")
            }

            val event = when (timeNeedsSync) {
                false -> OnSyncSatisfy
                true -> OnSyncRequired
            }
            stateMachine.transition(event)
        }

        private fun Initiated.detach() =
            scope.cancel("SyncManager is transitioning to Detached-state.")

        private fun Attached.detach() {
            (this as Initiated).detach()
            timeNeedsSyncSubscription.cancel()
        }

        private fun Initiated.startSync() {
            syncing?.cancel()
            syncing = scope.launch {
                performSync()
                launchSyncResponseTimeout()
            }
        }

        private suspend fun Initiated.performSync() = sendTrainingMessages(TRAINING_MESSAGES_COUNT)

        private suspend fun Initiated.sendTrainingMessages(count: Int) {
            val deltas = withContext(Dispatchers.IO) {
                val firetimes = (1..count).map { msgIndex ->
                    now() + TRAINING_INTERVAL_IN_MILLIS * msgIndex
                }

                val actualFiretimes = firetimes
                    .map { firetime ->
                        sendTrainingMessage(firetime)
                    }

                firetimes
                    .zip(actualFiretimes)
                    .map {
                        val (scheduledFiretime, actualFiretime) = it
                        // delta
                        actualFiretime - scheduledFiretime
                    }
            }
            Log.d(TAG, "sendTrainingMessages() - deltas: $deltas")
        }

        private suspend fun Initiated.sendTrainingMessage(firetime: Long): Long {
            val timestamp = firetime.toUInt()

            // Actively sleeping in this thread for accuracy:
            // kotlinx.coroutines.delay() would have been the appropriate suspending alternative here,
            // but it was always off for some 1-3 ms, which is enough to cause the training messages
            // to miss their required windows (the connection's Connection-Latency).
            val sleepCycles = sleep(firetime)
            Log.d(TAG, "sendTrainingMessage() - $sleepCycles")

            val actualFiretime = now().also {
                Log.d(
                    TAG, "sendTrainingMessage() - firetime=$firetime now=$it: will send now"
                )
            }

            client.writeGattCharacteristicAsync(
                de.kishorrana.signalboy_android.service.gatt.ReferenceTimestampCharacteristic,
                timestamp.toByteArrayLE(),
                false
            )

            return actualFiretime
        }

        private fun Training.scheduleNextTrainingAttempt(delayMillis: Long) {
            scope.launch {
                delay(delayMillis)
                stateMachine.transition(OnTrainingRetry)
            }
        }

        //region Helpers
        private fun sleep(until: Long): Int {
            var cycles = 0
            while (until - now() > 0) {
                // Blocking thread here…
                cycles++
            }
            return cycles
        }
        //endregion
    }
}
