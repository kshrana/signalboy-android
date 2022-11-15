package de.kishorrana.signalboy_android.service.discovery

import android.content.Intent
import android.content.IntentSender

interface ActivityResultProxy {
    fun launch(intentSender: IntentSender, callback: Callback)

    interface Callback {
        fun onActivityResult(resultCode: Int, data: Intent?)
    }
}
