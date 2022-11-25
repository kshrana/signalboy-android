package de.kishorrana.signalboy_android.service.connection_supervising

import android.app.Activity
import android.util.Log
import de.kishorrana.signalboy_android.MIN_IN_MILLISECONDS
import de.kishorrana.signalboy_android.service.ISignalboyService
import de.kishorrana.signalboy_android.service.InteractionRequest
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import de.kishorrana.signalboy_android.service.discovery.UserInteractionRequiredException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

internal class ConnectionSupervisingManager(
    private val signalboyService: ISignalboyService
) {
    val hasAnyOpenUserInteractionRequest: Boolean
        get() = userInteractionRequest?.canAcceptDependency == true

    private var supervising: Job? = null
    private var recoverStrategy: RecoverStrategy? = null

    private val userInteractionRequest
        get() = recoverStrategy?.let { recoverStrategy ->
            when (recoverStrategy) {
                is RecoverStrategy.DefaultRecoverStrategy -> null
                is RecoverStrategy.UserInteractionRecoverStrategy -> recoverStrategy.userInteractionRequest
            }
        }

    suspend fun superviseConnection() = coroutineScope {
        check(supervising?.isActive != true) { "Receiver is already supervising." }

        supervising = launch {
            with(signalboyService) {
                if (state is ISignalboyService.State.Disconnected) {
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
        checkNotNull(userInteractionRequest) { "No User Interaction Request available." }
            .resumeAndAwaitResolving(
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
    ): RecoverStrategy? {
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
                    signalboyService.connectToPeripheral()
                } catch (exception: UserInteractionRequiredException) {
                    // Switch to User-Interaction Recover-Strategy.
                    return continueWithNextAttempt(
                        exception,
                        RecoverStrategy.UserInteractionRecoverStrategy(cause = exception)
                    )
                } catch (exception: Exception) {
                    // Retain current recover strategy.
                    return continueWithNextAttempt(exception, recoverStrategy)
                }
            }
            is RecoverStrategy.UserInteractionRecoverStrategy -> {
                val userInteractionRequest = recoverStrategy.userInteractionRequest

                if (delayMillis > 0) {
                    Log.d(
                        TAG,
                        "Awaiting continuation for User-Interaction Recover-Strategy..." +
                                " Will launch next headless connection attempt when continuation" +
                                " was not received (timeout in ${delayMillis / 1_000}s..."
                    )
                }

                withTimeoutOrNull(delayMillis) {
                    userInteractionRequest.waitForDependency()
                }

                try {
                    // Try to connect:
                    //   * Either with user-interaction enabled (if dependencies received)…
                    //   * or otherwise falling back to (regular) headless mode (no user-interaction)
                    try {
                        userInteractionRequest.resolveUsingDependencyOrThrow { dependency ->
                            // Received dependency required for user-interaction:
                            // The following connection-attempt might trigger (User-)Interaction,
                            // if necessary.
                            val (activity, activityResultProxy) = dependency
                            signalboyService.connectToPeripheral(activity, activityResultProxy)
                        }
                    } catch (missingDependencyException: InteractionRequest.MissingDependencyException) {
                        // Resolving the (User-)Interaction-Request was not successful, because
                        // it did not receive the required dependency:
                        // Will fallback to headless connection-attempt.
                        signalboyService.connectToPeripheral()
                    }
                } catch (exception: Exception) {
                    // Connection attempt was not successful.
                    return continueWithNextAttempt(
                        exception,
                        recoverStrategy.also {
                            if (it.userInteractionRequest.isResolved) {
                                it.resetRequest()
                            }
                        }
                    )
                }
            }
        }

        // Connected successfully.
        return null
    }

    private suspend fun reconnectIfNeeded(state: ISignalboyService.State) {
        // Reconnect if connection was dropped due to an error.
        val disconnectCause = (state as? ISignalboyService.State.Disconnected)?.cause
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
            var userInteractionRequest = makeRequest()
                private set

            fun resetRequest() {
                userInteractionRequest.cancel()
                userInteractionRequest = makeRequest()
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
