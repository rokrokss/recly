package app.recly.android.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * The way out of "this device has no Google account": the system add-account screen, then one retry.
 *
 * Its own class for the same reason as [ConsentController] — the activity is the only thing that
 * can launch a screen, but the state has to outlive it — and in [SavedStateHandle] rather than in
 * memory, because this screen belongs to *another app*. Adding an account is long enough that the
 * system may well kill this process behind it, and the `ActivityResultRegistry` restores its
 * pending launch and delivers the result to the recreated activity regardless. Held in memory, the
 * controller would come back with nothing pending and drop that result on the floor — exactly the
 * case the retry exists for.
 *
 * [request] is true while the add-account screen should be up; [consumeLaunch] hands the launch to
 * exactly one activity, so a rotation does not open a second one. [onReturned] allows exactly one
 * retry: an account the user declined to add must not turn into a sign-in loop.
 */
class AddAccountController(private val saved: SavedStateHandle) {

    val request: StateFlow<Boolean> = saved.getStateFlow(KEY_REQUEST, false)

    /**
     * The user pressed Sign in themselves. The one-retry guard is about a single trip to Settings —
     * not about the rest of the process's life — so a deliberate new attempt starts over, and any
     * request left pending by a trip whose result never arrived is dropped with it.
     */
    fun onUserSignIn() {
        saved[KEY_REQUEST] = false
        launched = false
        retried = false
    }

    /** Sign-in came back [app.recly.android.auth.SignInResult.NoAccount]. */
    fun onNoAccount() {
        if (retried) return
        launched = false
        saved[KEY_REQUEST] = true
    }

    /** True at most once per request: the activity that gets `true` opens the screen. */
    fun consumeLaunch(): Boolean {
        if (!request.value || launched) return false
        launched = true
        return true
    }

    /** Back from the add-account screen. True when sign-in should be retried. */
    fun onReturned(): Boolean {
        if (!request.value) return false
        saved[KEY_REQUEST] = false
        if (retried) return false
        retried = true
        return true
    }

    private var launched: Boolean
        get() = saved[KEY_LAUNCHED] ?: false
        set(value) {
            saved[KEY_LAUNCHED] = value
        }

    private var retried: Boolean
        get() = saved[KEY_RETRIED] ?: false
        set(value) {
            saved[KEY_RETRIED] = value
        }

    private companion object {
        const val KEY_REQUEST = "addAccount.request"
        const val KEY_LAUNCHED = "addAccount.launched"
        const val KEY_RETRIED = "addAccount.retried"
    }
}
