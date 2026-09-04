package app.recly.android.ui

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an activity's `StartIntentSenderForResult` launcher brought back. */
data class ConsentResult(val ok: Boolean, val data: Intent?)

/**
 * The consent round trip, owned by the ViewModel rather than the activity.
 *
 * An `AuthorizationClient` consent screen can easily outlive the activity that launched it — a
 * rotation is enough. If the continuation lived in the activity it would be lost, and the
 * authorization coroutine would hang forever holding the UI in its busy state. Here the
 * continuation survives with the ViewModel, and the recreated activity only has to forward the
 * result it receives.
 *
 * [request] is the pending handle; [consumeLaunch] hands the launch to exactly one activity, so
 * the recreated one re-collects [request] without showing a second consent screen.
 *
 * Generic in the handle purely so this state machine can be unit-tested: `PendingIntent` cannot be
 * constructed off-device. The app uses `ConsentController<PendingIntent>`.
 */
class ConsentController<T : Any> {

    private val _request = MutableStateFlow<T?>(null)
    val request: StateFlow<T?> = _request.asStateFlow()

    private var awaiting: CompletableDeferred<ConsentResult>? = null
    private var launched = false

    /** Publishes [handle] for whichever activity is attached, and suspends until a result arrives. */
    suspend fun await(handle: T): ConsentResult {
        val deferred = CompletableDeferred<ConsentResult>()
        awaiting = deferred
        launched = false
        _request.value = handle
        return try {
            deferred.await()
        } finally {
            awaiting = null
            _request.value = null
        }
    }

    /**
     * True at most once per request. The activity that gets `true` shows the consent screen; an
     * activity recreated while that screen is up gets `false` and just waits for the result.
     */
    fun consumeLaunch(): Boolean {
        if (_request.value == null || launched) return false
        launched = true
        return true
    }

    /**
     * Forwarded from the activity's launcher. A duplicate — a stale launcher delivering twice —
     * finds the deferred already completed and is dropped.
     */
    fun onResult(resultCode: Int, data: Intent?) {
        awaiting?.complete(ConsentResult(resultCode == Activity.RESULT_OK, data))
    }
}
