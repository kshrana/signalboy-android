package de.kishorrana.signalboy_android.service.discovery.companion_device

import android.app.Activity
import android.companion.CompanionDeviceManager
import android.content.Context
import androidx.core.content.getSystemService

internal open class OriginAwareCompanionDeviceManager private constructor(
    private val origin: Context,
    open val wrappedValue: CompanionDeviceManager
) {
    open fun ensureCanAssociate() {
        // TODO: Find out for which Android versions this requirement is necessary.
        check(origin is Activity) {
            "Context is required to be an instance of `Activity`." +
                    " Older Android implementation cast the context" +
                    " (which was used to acquire the CompanionDeviceManager)" +
                    " to `Activity` when calling [CompanionDeviceManager.associate()]."
        }
    }

    companion object {
        fun instantiate(context: Context) = with(context) {
            OriginAwareCompanionDeviceManager(this, getSystemService()!!)
        }
    }
}
