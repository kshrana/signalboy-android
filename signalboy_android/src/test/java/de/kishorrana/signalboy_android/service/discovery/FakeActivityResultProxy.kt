package de.kishorrana.signalboy_android.service.discovery

import android.app.Activity
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Parcelable

class FakeActivityResultProxy : ActivityResultProxy {
    var lastLaunchedIntentSender: IntentSender? = null
        private set
    var lastLaunchedCallback: ActivityResultProxy.Callback? = null
        private set

    override fun launch(
        intentSender: IntentSender,
        callback: ActivityResultProxy.Callback
    ) {
        lastLaunchedIntentSender = intentSender
        lastLaunchedCallback = callback
    }

    fun chooseDevice(devicePayload: Parcelable) {
        with(checkNotNull(lastLaunchedCallback)) {
            onActivityResult(
                Activity.RESULT_OK,
                Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, devicePayload)
            )
        }
    }

    fun cancel() {
        with(checkNotNull(lastLaunchedCallback)) {
            onActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
}
