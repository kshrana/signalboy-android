package de.kishorrana.signalboy_android.service.discovery.companion_device.ui

import android.app.Fragment
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import de.kishorrana.signalboy_android.service.discovery.ActivityResultProxy

private const val TAG = "AssociateFragment"
private const val REQUEST_CODE_SELECT_DEVICE = 1

/**
 * A headless fragment.
 */
class AssociateFragment : Fragment(), ActivityResultProxy {
    // Fragment is headless (`retainInstance==true`),
    // thus we're able to pass our dependencies directly via
    // its constructor here.
//    @SuppressLint("ValidFragment")
//    constructor() : this() {
//        this.callback = callback
//    }

    private var callback: ActivityResultProxy.Callback? = null

    @Deprecated("Deprecated in Java")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Headless fragment: s. https://luboganev.dev/blog/headless-fragments/
        retainInstance = true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CODE_SELECT_DEVICE -> callback?.onActivityResult(resultCode, data)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun launch(
        intentSender: IntentSender,
        callback: ActivityResultProxy.Callback
    ) {
        this.callback = callback
        startIntentSenderForResult(
            intentSender,
            REQUEST_CODE_SELECT_DEVICE, null, 0, 0, 0, null
        )
    }
}
