package de.kishorrana.signalboy_android.service.connection_supervising

import android.app.Activity
import android.util.Log
import de.kishorrana.signalboy_android.MIN_IN_MILLISECONDS
import de.kishorrana.signalboy_android.service.InteractionRequest
import de.kishorrana.signalboy_android.service.SignalboyMediator
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import de.kishorrana.signalboy_android.service.discovery.UserInteractionRequiredException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

internal class ConnectionSupervisingManager(
    private val signalboyMediator: SignalboyMediator
) {
    val hasUserInteractionRequest: Boolean
        get() = userInteractionRequest?.canAcceptDependency == true

    private var supervising: Job? = null
    private var recoverStrategy: RecoverStrategy? = null

    private val userInteractionRequest
        get() = recoverStrategy?.let { recoverStrategy ->
            when (recoverStrategy) {
                is RecoverStrategy.DefaultRecoverStrategy -> null
                is RecoverStrategy.UserInteractionRecoverStrategy -> recoverStrategy.request
            }
        }

    suspend fun superviseConnection() = coroutineScope {
        check(supervising?.isActive != true) { "Receiver is already supervising." }

        supervising = launch {
            with(signalboyMediator) {
                if (state is SignalboyMediator.State.Disconnected) {
                    connect()
                }
                latestState
                    .buffer(1, BufferOverflow.DROP_OLDEST)
                    .collect { state -> reconnectIfNeeded(state) }
            }
        }
    }

    suspend fun resolveUserInteractionRequest(
        activity: Activity,
        userInteractionProxy: ActivityResultProxy
    ): Result<Unit> = runCatching {
        checkNotNull(userInteractionRequest).resumeAndAwaitResolving(
            Pair(activity, userInteractionProxy)
        )
    }

    private suspend fun connect(
        shouldDelayFirstAttempt: Boolean = false,
        initialRecoverStrategy: RecoverStrategy = RecoverStrategy.DefaultRecoverStrategy(null)
    ) = flow {
        var reconnectAttempt = if (shouldDelayFirstAttempt) {
            1
        } else {
            0
        }

        var recoverStrategy: RecoverStrategy? = initialRecoverStrategy
            .also { emit(it) }
        while (recoverStrategy != null) {
            recoverStrategy = connectOrRecoverStrategy(reconnectAttempt, recoverStrategy).also {
                it?.let { emit(it) }
            }

            reconnectAttempt += 1
        }
    }
        .onCompletion { error ->
            if (error != null) {
                throw error
            }
            recoverStrategy = null
        }
        .collect { recoverStrategy = it }

    private suspend fun connectOrRecoverStrategy(
        reconnectAttempt: Int = 0,
        recoverStrategy: RecoverStrategy = RecoverStrategy.DefaultRecoverStrategy(null)
    ): RecoverStrategy? = coroutineScope {
        fun continueWithNextAttempt(
            cause: Throwable?,
            recoverStrategy: RecoverStrategy
        ): RecoverStrategy {
            Log.w(
                TAG,
                "Connection attempt failed (reconnectAttempt=$reconnectAttempt)" +
                        " due to error:",
                cause
            )

            return recoverStrategy
        }

        val delayMillis = getDelayMillis(reconnectAttempt)
        when (recoverStrategy) {
            is RecoverStrategy.DefaultRecoverStrategy -> {
                if (delayMillis > 0) {
                    Log.d(
                        TAG,
                        "Will launch next connection attempt in ${delayMillis / 1_000}s" +
                                " (backoff-strategy)..."
                    )
                    delay(delayMillis)
                }
                try {
                    // Will fail when user-interaction is required.
                    signalboyMediator.connectToPeripheral()
                } catch (exception: UserInteractionRequiredException) {
                    // Switch to User-Interaction Recover-Strategy.
                    return@coroutineScope continueWithNextAttempt(
                        exception,
                        RecoverStrategy.UserInteractionRecoverStrategy(cause = exception)
                    )
                } catch (exception: Exception) {
                    // Retain current recover strategy.
                    return@coroutineScope continueWithNextAttempt(exception, recoverStrategy)
                }
            }
            is RecoverStrategy.UserInteractionRecoverStrategy -> {
                val userInteractionRequest = recoverStrategy.request

                if (delayMillis > 0) {
                    Log.d(
                        TAG,
                        "Awaiting continuation for User-Interaction Recover-Strategy..." +
                                " Will launch next (headless) connection attempt on timeout " +
                                "in ${delayMillis / 1_000}s..."
                    )
                }

                val recoveryActionResult = userInteractionRequest.resolve { deferredPayload ->
                    val payload = withTimeoutOrNull(delayMillis) { deferredPayload.await() }

                    if (payload != null) {
                        // Received dependency required for user-interaction:
                        // This connection-attempt might trigger User Interaction Requests,
                        // if necessary.
                        val (activity, activityResultProxy) = payload
                        signalboyMediator.connectToPeripheral(activity, activityResultProxy)
                    } else {
                        // Timeout occurred when awaiting the dependency required
                        // for user-interaction:
                        // Will fall back to headless connection-attempt.
                        signalboyMediator.connectToPeripheral()
                    }
                }

                return@coroutineScope try {
                    recoveryActionResult.getOrThrow().let {
                        // Recovery action was successful (connection (re-)established).
                        null
                    }
                } catch (exception: Exception) {
                    continueWithNextAttempt(
                        exception,
                        recoverStrategy.also {
                            if (it.request.isResolved) {
                                it.resetRequest()
                            }
                        }
                    )
                }
            }
        }

        // Connected successfully.
        return@coroutineScope null
    }

    private suspend fun reconnectIfNeeded(state: SignalboyMediator.State) {
        // Reconnect if connection was dropped due to an error.
        val disconnectCause = (state as? SignalboyMediator.State.Disconnected)?.cause
        if (disconnectCause != null) {
            Log.w(TAG, "Connection lost due to error:", disconnectCause)
            val recoverStrategy: RecoverStrategy = when (disconnectCause) {
                is UserInteractionRequiredException ->
                    RecoverStrategy.UserInteractionRecoverStrategy(disconnectCause)
                else -> RecoverStrategy.DefaultRecoverStrategy(disconnectCause)
            }
            connect(true, recoverStrategy)
        }
    }

    private fun getDelayMillis(reconnectAttempt: Int): Long =
        // Backoff strategy:
        when (reconnectAttempt) {
            0 -> 0L
            1 -> 1 * 1_000L
            2 -> 5 * 1_000L
            3 -> 20 * 1_000L
            else -> 3 * MIN_IN_MILLISECONDS
        }

    private sealed class RecoverStrategy(
        /**
         * The exception, that was thrown when this recovery-strategy was created.
         */
        val cause: Throwable?
    ) {
        class DefaultRecoverStrategy(cause: Throwable?) : RecoverStrategy(cause)

        class UserInteractionRecoverStrategy(
            cause: Throwable?,
        ) : RecoverStrategy(cause) {
            var request = makeRequest()
                private set

            fun resetRequest() {
                request.cancel()
                request = makeRequest()
            }

            companion object {
                private fun makeRequest() = UserInteractionRequest()
            }
        }
    }

    companion object {
        private const val TAG = "ConnectionSupervisingManager"
    }
}

internal typealias UserInteractionRequest = InteractionRequest<Pair<Activity, ActivityResultProxy>, Unit>
