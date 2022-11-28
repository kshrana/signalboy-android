package com.example.signalboycentral

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import de.kishorrana.signalboy_android.service.SignalboyService
import java.lang.ref.WeakReference

class BoundSignalboyService private constructor(
    private val weakContext: WeakReference<Context>
) {
    private var service: SignalboyService? = null
    private var onBind: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            checkNotNull(onBind).let { onBind ->
                this@BoundSignalboyService.service = (service as SignalboyService.LocalBinder)
                    .getService()

                onBind()
                this@BoundSignalboyService.onBind = null
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            Log.e(TAG, "onServiceDisconnected")
            service = null
        }
    }

    fun getService() = ensureService()

    fun unbind() {
        weakContext.get()?.unbindService(serviceConnection)
        service = null
    }

    private fun bind(
        context: Context,
        config: SignalboyService.Configuration,
        completion: () -> Unit
    ) {
        onBind = completion

        Intent(context, SignalboyService::class.java)
            .putExtra(SignalboyService.EXTRA_CONFIGURATION, config)
            .also { intent ->
                val result = context.bindService(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
                Log.d(TAG, "result=$result")
            }
    }

    private fun ensureService(): SignalboyService =
        checkNotNull(service) { "Service is not bound." }

    companion object {
        private const val TAG = "BoundSignalboyService"

        fun instantiate(
            context: Context,
            config: SignalboyService.Configuration,
            completion: (BoundSignalboyService) -> Unit
        ) {
            val boundService = BoundSignalboyService(WeakReference(context))
            boundService.bind(context, config) {
                completion(boundService)
            }
        }
    }
}

