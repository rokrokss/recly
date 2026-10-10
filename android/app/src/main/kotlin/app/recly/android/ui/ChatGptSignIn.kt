package app.recly.android.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import app.recly.android.R
import app.recly.android.auth.LoopbackReceiver
import app.recly.android.auth.loopbackPage
import app.recly.android.core.AndroidLogger
import app.recly.android.core.CoreModule
import app.recly.android.ui.component.ProcessingState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import recly.core.chatgpt.ChatGptAccount
import recly.core.chatgpt.ChatGptResult
import recly.core.chatgpt.SummaryPreferences
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.platform.Logger

/** What the ChatGPT section says besides the connection itself (docs/09 "Summary view"). */
data class ChatGptUiState(
    /** The loopback is open and the browser has the sign-in: Cancel is offered. */
    val signingIn: Boolean = false,
    /** The Continue button's window (docs/09 trend 2), through the exchange that follows the callback. */
    val action: ProcessingState = ProcessingState.IDLE,
    /** Why the last sign-in failed, as the core's code. A cancelled one says nothing. */
    val failure: String? = null,
    /** Signed out here, but OpenAI did not confirm the revocation. */
    val revokeUnconfirmed: Boolean = false,
    /** The one-time confirmation after the first sign-in on this device. */
    val welcome: Boolean = false,
)

/**
 * docs/15 §10 "Sign in with ChatGPT" on the phone: the loopback the browser comes back to, and what the
 * settings section shows about it. Process-wide like [app.recly.android.core.AudioImports], not the
 * activity's: the activity stops while the browser is in front, and the sign-in must still be listening
 * when it comes back. A process that dies meanwhile takes the sign-in with it; the user signs in again.
 */
class ChatGptSignIn private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = AndroidLogger()
    private val _state = MutableStateFlow(ChatGptUiState())
    val state: StateFlow<ChatGptUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** What this device holds and the plan's models — the app opening, or Settings. */
    fun refresh() {
        scope.launch { runCatching { account().refresh() } }
    }

    /**
     * Continue with ChatGPT: listen, start the sign-in on that port, and hand [open] the page to show — the
     * activity opens it, so the browser tab is part of the app's task. [open] false: nothing could show it.
     */
    fun signIn(open: (String) -> Boolean) {
        if (job?.isActive == true) return
        _state.update { it.copy(action = ProcessingState.PROCESSING, failure = null, revokeUnconfirmed = false) }
        job = scope.launch {
            var outcome = ProcessingState.IDLE
            var receiver: LoopbackReceiver? = null
            var account: ChatGptAccount? = null
            try {
                account = account()
                receiver = withContext(Dispatchers.IO) { LoopbackReceiver.open(::page) }
                logger.log(Logger.Level.INFO, "shell.chatgpt.listening", mapOf("port" to receiver.port))
                val started = account.beginSignIn(receiver.redirectUri)
                if (!withContext(Dispatchers.Main) { open(started.authorizationUrl) }) {
                    account.cancelSignIn()
                    return@launch
                }
                _state.update { it.copy(signingIn = true) }
                val callback = withTimeoutOrNull(TIMEOUT) { receiver.awaitCallback(started.state) }
                // The browser already has its page; the exchange is the core's, and is not cancelled.
                _state.update { it.copy(signingIn = false) }
                if (callback == null) {
                    logger.log(Logger.Level.INFO, "shell.chatgpt.timeout", emptyMap())
                    account.cancelSignIn()
                    return@launch
                }
                when (val result = account.finishSignIn(callback)) {
                    is ChatGptResult.Done -> {
                        outcome = ProcessingState.DONE
                        _state.update { it.copy(welcome = result.welcome) }
                    }
                    is ChatGptResult.Failed -> {
                        outcome = ProcessingState.FAILED
                        // A consent the user declined is their answer, not a failure (docs/09).
                        val cancelled = CoreMessageRef.parse(result.reason)?.message == CoreMessage.SIGN_IN_CANCELLED
                        if (!cancelled) _state.update { it.copy(failure = result.reason) }
                    }
                }
            } catch (e: CancellationException) {
                account?.cancelSignIn()
                throw e
            } catch (e: Exception) {
                outcome = ProcessingState.FAILED
                account?.cancelSignIn()
                _state.update { it.copy(failure = CoreMessage.PROVIDER_ERROR.code(detail = e.message ?: e::class.simpleName)) }
            } finally {
                receiver?.close()
                _state.update { it.copy(signingIn = false, action = outcome) }
            }
        }
    }

    /** Cancel under the status line: the listener closes and the sign-in it started can no longer finish. */
    fun cancel() {
        job?.cancel()
    }

    /** No confirmation: Continue with ChatGPT signs back in to the same registration (docs/09 "Summary view"). */
    fun signOut() {
        _state.update { it.copy(failure = null, revokeUnconfirmed = false) }
        scope.launch {
            val result = runCatching { account().signOut() }.getOrNull()
            if (result is ChatGptResult.Failed) _state.update { it.copy(revokeUnconfirmed = true) }
        }
    }

    fun selectModel(id: String) {
        scope.launch { runCatching { account().selectModel(id) } }
    }

    /**
     * Settings → ChatGPT's Summary format, My format and About you (docs/08 "Summaries"): [change] is applied to what
     * is stored at the time, one save after another, so a field saved as the format changes keeps both. Here rather than
     * on the screen, so a field saved as the screen goes still is.
     */
    fun updatePreferences(change: (SummaryPreferences) -> SummaryPreferences) {
        scope.launch {
            preferencesLock.withLock {
                runCatching {
                    val summaries = CoreModule.get(context).core.summaries
                    summaries.setPreferences(change(summaries.preferences()))
                }
            }
        }
    }

    private val preferencesLock = Mutex()

    fun dismissWelcome() {
        _state.update { it.copy(welcome = false) }
    }

    private suspend fun account(): ChatGptAccount = CoreModule.get(context).core.chatGpt

    /**
     * The tab the browser is left on, in the language the app is in when it is served (docs/07 rule 3). Its
     * link brings the app back over the tab — `CLEAR_TOP | SINGLE_TOP`, so the activity already under it
     * takes the intent rather than a second one opening on top.
     */
    private fun page(page: LoopbackReceiver.Page): String {
        val back = context.getString(R.string.chatgpt_page_return) to
            "intent://$RETURN_HOST#Intent;scheme=$RETURN_SCHEME;package=${context.packageName};launchFlags=0x24000000;end"
        return when (page) {
            LoopbackReceiver.Page.SIGNED_IN -> loopbackPage(context.getString(R.string.chatgpt_page_ok), back)
            LoopbackReceiver.Page.DECLINED -> loopbackPage(context.getString(R.string.chatgpt_page_declined), back)
            LoopbackReceiver.Page.DONE -> loopbackPage(context.getString(R.string.chatgpt_page_done), null)
        }
    }

    companion object {
        /** The return link's address, which only the main activity's filter takes. */
        const val RETURN_SCHEME = "app.recly"
        const val RETURN_HOST = "chatgpt"

        /** docs/09 "Summary view": then the listener closes, as Cancel does. */
        private val TIMEOUT = 5.minutes

        @Volatile private var instance: ChatGptSignIn? = null

        fun get(context: Context): ChatGptSignIn = instance ?: synchronized(this) {
            instance ?: ChatGptSignIn(context.applicationContext).also { instance = it }
        }
    }
}

/** Where ChatGPT shows and limits what Recly uses of the plan (docs/09 "Summary view"). */
internal const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

/**
 * The sign-in page in a Custom Tab, from the activity so the tab sits on the app's task. A browser without
 * Custom Tabs takes the same `VIEW` as a plain link. False when nothing on the phone opens a link.
 *
 * A partial tab — a sheet over Recly, which stays visible behind it — rather than a page in front of it:
 * Android freezes an app that is not visible about a minute after it leaves the screen, and a frozen app's
 * loopback cannot answer the browser (measured on API 36, 2026-10-09). A partial tab needs a result launch;
 * a browser without partial tabs (before Chrome 107) shows the same tab full height.
 */
internal fun Context.openSignIn(url: String): Boolean = try {
    val tab = CustomTabsIntent.Builder()
        .setInitialActivityHeightPx(resources.displayMetrics.heightPixels * 9 / 10)
        .build()
    tab.intent.data = url.toUri()
    val activity = findActivity()
    @Suppress("DEPRECATION")
    if (activity != null) activity.startActivityForResult(tab.intent, SIGN_IN_TAB) else tab.launchUrl(this, url.toUri())
    true
} catch (e: ActivityNotFoundException) {
    AndroidLogger().log(Logger.Level.WARN, "shell.openUrl.failed", error = e)
    false
}

/** The tab's result is not read: the loopback is how the sign-in comes back. */
private const val SIGN_IN_TAB = 0x5157

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
