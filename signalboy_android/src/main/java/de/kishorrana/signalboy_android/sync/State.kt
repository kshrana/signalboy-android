package de.kishorrana.signalboy_android.sync

import de.kishorrana.signalboy_android.client.Client
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

internal sealed class State {
    object Detached : State()

    data class Attaching(
        override val scope: CoroutineScope,
        override val client: Client
    ) : State(), Initiated

    data class Training(
        override val scope: CoroutineScope,
        override val client: Client,
        override val timeNeedsSyncSubscription: Client.NotificationSubscription.CancellationToken
    ) : State(), Attached

    data class Synced(
        override val scope: CoroutineScope,
        override val client: Client,
        override val timeNeedsSyncSubscription: Client.NotificationSubscription.CancellationToken
    ) : State(), Attached

    // Interfaces

    interface Initiated {
        val scope: CoroutineScope
        val client: Client
    }

    interface Attached : Initiated {
        val timeNeedsSyncSubscription: Client.NotificationSubscription.CancellationToken
    }
}

internal sealed class Event {
    object OnDetachRequest : Event()

    data class OnAttachRequest(val context: CoroutineContext, val client: Client) : Event()

    data class OnAttachSuccess(
        val initialTimeNeedsSyncValue: ByteArray,
        val timeNeedsSyncSubscription: Client.NotificationSubscription.CancellationToken
    ) : Event()

    object OnSyncRequired : Event()

    object OnSyncSatisfy : Event()

    object OnTrainingTimeout : Event()

    // debug for testing sync (manual trigger)
    object OnSyncRequest : Event()
}

internal sealed class SideEffect
