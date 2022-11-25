package de.kishorrana.signalboy_android.service

import kotlinx.coroutines.CompletableDeferred

internal class InteractionRequest<T, R> {
    val canAcceptDependency get() = deferredDependency.isActive
    val isResolved get() = deferredResult.isCompleted

    // Dependency is only allowed to be consumed once.
    private val deferredDependency = CompletableDeferred<T>()
    private val deferredResult = CompletableDeferred<R>()

    suspend fun resumeAndAwaitResolving(dependency: T): R {
        check(!isResolved) { "Already resolved." }
        check(deferredDependency.complete(dependency)) { "Already received dependency." }

        // Await result
        return deferredResult.await()
    }

    suspend fun waitForDependency() = deferredDependency.join()

    suspend fun resolveUsingDependencyOrThrow(dependencyConsumer: suspend (T) -> R): R {
        // Fail fast
        check(!isResolved) { "Already resolved." }

        if (!deferredDependency.isCompleted) {
            throw MissingDependencyException("Resolving failed due to missing dependency.")
        }

        return try {
            // Should return immediately, as we asserted `deferredDependency.isCompleted`.
            val dependency = deferredDependency.await()

            dependencyConsumer(dependency)
                .also(deferredResult::complete)
        } catch (exception: Throwable) {
            deferredResult.completeExceptionally(exception)
            throw exception
        }
    }

    fun cancel() {
        deferredDependency.cancel()
        deferredResult.cancel()
    }

    class MissingDependencyException(message: String) : Exception(message)
}
