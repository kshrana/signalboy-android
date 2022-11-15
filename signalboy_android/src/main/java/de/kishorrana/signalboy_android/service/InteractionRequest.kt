package de.kishorrana.signalboy_android.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal class InteractionRequest<T, R> {
    val canAcceptDependency get() = deferredDependency.isActive
    val isResolved get() = deferredResult.isCompleted

    // Dependency is only allowed to be consumed once.
    private val deferredDependency = CompletableDeferred<T>()
    private val deferredResult = CompletableDeferred<R>()

    suspend fun resumeAndAwaitResolving(dependency: T): R {
        check(canAcceptDependency) { "Already received dependency." }
        check(!isResolved) { "Already resolved." }

        deferredDependency.complete(dependency)

        // Await result.
        return deferredResult.await()
    }

    suspend fun resolve(dependencyConsumer: suspend (Deferred<T>) -> R): Result<R> {
        check(!isResolved) { "Already resolved." }

        return try {
            dependencyConsumer(deferredDependency)
                .also(deferredResult::complete)
                .let(Result.Companion::success)
        } catch (exception: Throwable) {
            exception
                .also(deferredResult::completeExceptionally)
                .let(Result.Companion::failure)
        }
    }

    fun cancel() {
        deferredDependency.cancel()
        deferredResult.cancel()
    }
}
