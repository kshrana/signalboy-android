@file:JvmName("SignalboyServiceJavaInterop")

package de.kishorrana.signalboy_android.service

import android.app.Activity
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

fun SignalboyService.resolveUserInteractionRequest(
    activity: Activity,
    userInteractionProxy: ActivityResultProxy,
    callback: InteractionRequestCallback
) {
    MainScope().launch {
        resolveUserInteractionRequest(activity, userInteractionProxy)
            .also { result ->
                try {
                    result.getOrThrow()
                } catch (exception: Throwable) {
                    callback.requestFinishedExceptionally(exception)
                    return@also
                }
                callback.requestFinishedSuccessfully()
            }
    }
}

interface InteractionRequestCallback {
    fun requestFinishedSuccessfully()
    fun requestFinishedExceptionally(exception: Throwable)
}
