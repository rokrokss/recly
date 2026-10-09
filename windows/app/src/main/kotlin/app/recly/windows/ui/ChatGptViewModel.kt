package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.auth.LoopbackReceiver
import app.recly.windows.auth.openInSystemBrowser
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.coreMessage
import app.recly.windows.i18n.message
import app.recly.windows.ui.theme.Motion
import app.recly.windows.ui.theme.ProcessingState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recly.core.chatgpt.ChatGptAccount
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptResult
import recly.core.chatgpt.SummaryState
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.platform.Logger

/** What the ChatGPT section says under its rows, besides the account itself (docs/09 "Summary view"). */
sealed interface ChatGptNotice {
    /** [reason] is a `CoreMessage` wire code — or, for a sign-in this shell could not even start, its diagnostic. */
    data class SignInFailed(val reason: String) : ChatGptNotice

    /** Signed out here, but OpenAI did not confirm the revocation. Not a failure: nothing is left on this PC. */
    data object RevokeUnconfirmed : ChatGptNotice
}

/**
 * docs/15 §10 "Sign in with ChatGPT", the shell's half: the loopback and the browser. The core owns the
 * rest — PKCE, the exchange, the tokens, the model list — and the tokens never pass through here.
 *
 * Its own [action] rather than [ShellModel.action]: that one is the Drive sign-in's and the tray's, and a
 * ChatGPT sign-in left waiting in the browser for minutes would hold their buttons in "…" with it.
 */
class ChatGptViewModel(
    private val account: ChatGptAccount,
    private val receiver: LoopbackReceiver,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val browser: suspend (String) -> Unit = { openInSystemBrowser(it) },
    /** How long the loopback waits for the browser to come back (docs/15 §10: five minutes). */
    private val timeout: Duration = SIGN_IN_TIMEOUT,
    private val browserTimeout: Duration = BROWSER_TIMEOUT,
) {
    var connection: ChatGptConnection by mutableStateOf(account.observe().value)
        private set

    /** The loopback is open and the browser is where the user is: the status line and its Cancel. */
    var listening: Boolean by mutableStateOf(false)
        private set

    /** The Continue button's window (docs/09 trend 2), from the click until the core has answered. */
    var action: ProcessingState by mutableStateOf(ProcessingState.IDLE)
        private set

    var notice: ChatGptNotice? by mutableStateOf(null)
        private set

    /** The one-time welcome dialog, after the first sign-in on this PC. */
    var welcome: Boolean by mutableStateOf(false)
        private set

    private var signIn: Job? = null

    /** Bumped by every sign-in and every Cancel, so a browser open that arrives late knows it is stale. */
    @Volatile private var attempt = 0

    /** Follows the core's connection for as long as the app runs, and reads it once now. */
    fun start() {
        scope.launch { account.observe().collect { connection = it } }
        refresh()
    }

    /** What this PC holds and the plan's models — when the app and the settings window open. */
    fun refresh() {
        scope.launch {
            try {
                account.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Logger.Level.ERROR, "shell.chatgpt.refresh.failed", error = e)
            }
        }
    }

    fun signIn() {
        if (signIn?.isActive == true) return
        notice = null
        action = ProcessingState.PROCESSING
        listening = true
        val mine = ++attempt
        val opened = AtomicBoolean(false)
        signIn = scope.launch {
            val result = try {
                // The browser opens on a detached thread (LoopbackReceiver): a Cancel pressed before it gets there
                // must not open it anyway.
                val callback = receiver.awaitCallback(timeout, browserTimeout, begin = account::beginSignIn) { url ->
                    if (attempt != mine) {
                        account.cancelSignIn()
                    } else {
                        browser(url)
                        opened.set(true)
                    }
                }
                // The browser has its page and the port is closed; what is left is the core's exchange.
                listening = false
                account.finishSignIn(callback)
            } catch (e: TimeoutCancellationException) {
                // Five minutes with no callback: given up on, as Cancel would (docs/15 §10). A browser that never
                // opened is a failure to say, though: nothing on screen would tell the user what happened.
                account.cancelSignIn()
                if (opened.get()) null else ChatGptResult.Failed(CoreMessage.PROVIDER_ERROR.code(detail = "no browser opened"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                account.cancelSignIn()
                logger.log(Logger.Level.WARN, "shell.chatgpt.signin.failed", error = e)
                ChatGptResult.Failed(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                listening = false
            }
            when (result) {
                is ChatGptResult.Done -> {
                    settle(ProcessingState.DONE)
                    if (result.welcome) welcome = true
                }
                is ChatGptResult.Failed -> {
                    settle(ProcessingState.FAILED)
                    if (!cancelled(result.reason)) notice = ChatGptNotice.SignInFailed(result.reason)
                }
                null -> settle(ProcessingState.IDLE)
            }
        }
    }

    /** The status line's Cancel: the port closes with the wait, and the core forgets the sign-in. */
    fun cancelSignIn() {
        attempt++
        signIn?.cancel()
        signIn = null
        account.cancelSignIn()
        listening = false
        action = ProcessingState.IDLE
    }

    /** No confirmation: Continue with ChatGPT signs back in to the same registration (docs/09 "Summary view"). */
    fun signOut() {
        notice = null
        scope.launch {
            val result = try {
                account.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Logger.Level.ERROR, "shell.chatgpt.signout.failed", error = e)
                null
            }
            if (result is ChatGptResult.Failed) notice = ChatGptNotice.RevokeUnconfirmed
        }
    }

    fun selectModel(id: String) {
        scope.launch {
            try {
                account.selectModel(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Logger.Level.ERROR, "shell.chatgpt.model.failed", error = e)
            }
        }
    }

    fun dismissWelcome() {
        welcome = false
    }

    /** ChatGPT's own usage page — the plan's limits and Recly's share of them. */
    fun openUsage() {
        // `Desktop.browse` blocks until the OS has found a browser; never on the UI thread.
        scope.launch(Dispatchers.IO) { runCatching { browser(USAGE_URL) } }
    }

    /** Puts [state] up and takes it down again once the button has had its window ([ShellModel.settle]). */
    private fun settle(state: ProcessingState) {
        action = state
        if (state == ProcessingState.IDLE) return
        scope.launch {
            delay(Motion.PROCESSING_MAX_MS)
            if (action == state) action = ProcessingState.IDLE
        }
    }

    companion object {
        const val USAGE_URL = "https://chatgpt.com/settings/usage"

        val SIGN_IN_TIMEOUT: Duration = 5.minutes

        /** Handing a URL to the shell is not a round trip; anything this long is a browser missing. */
        val BROWSER_TIMEOUT: Duration = 30.seconds
    }
}

/** A sign-in the user cancelled in the browser says nothing (docs/09 "Summary view"). */
internal fun cancelled(reason: String): Boolean = CoreMessageRef.parse(reason)?.message == CoreMessage.SIGN_IN_CANCELLED

/**
 * The reasons that are a sentence of their own — where the fix is, or why there is none. Any other
 * failure is the screen's own headline with the diagnostic under it: `PROVIDER_ERROR`'s sentence says
 * "It will try again", which nothing here does.
 */
private val SENTENCES = setOf(
    CoreMessage.CHATGPT_SIGN_IN_REQUIRED,
    CoreMessage.CHATGPT_USAGE_LIMIT,
    CoreMessage.CHATGPT_PLAN_REQUIRED,
    CoreMessage.PROVIDER_REGION_RESTRICTED,
)

/** [reason]'s own sentence when it has one ([SENTENCES]), or null. */
internal fun chatGptSentence(reason: String): UiMessage? =
    CoreMessageRef.parse(reason)?.takeIf { it.message in SENTENCES }?.let { coreMessage(reason) }

/** The diagnostic behind [reason] — the provider's line, the core's detail — or a reason that is not a code, as it came. */
internal fun chatGptDiagnostic(reason: String): String? {
    val ref = CoreMessageRef.parse(reason) ?: return reason.takeIf { it.isNotBlank() }
    return ref.detail ?: ref.arg
}

/** The line under "Could not sign in to ChatGPT": the reason's sentence, or its diagnostic. */
internal fun signInFailureLine(reason: String): UiMessage? =
    chatGptSentence(reason) ?: chatGptDiagnostic(reason)?.let(UiMessage::Text)

/** The one button under a failed summary's notice (docs/09 "Summary view"). */
enum class SummaryRecovery { MANAGE_USAGE, NONE, RETRY }

/** What a failed summary's centred notice says, and the button under it. */
data class SummaryFailure(val headline: UiMessage, val detail: String?, val recovery: SummaryRecovery)

internal fun summaryFailure(reason: String): SummaryFailure {
    val sentence = chatGptSentence(reason)
    val recovery = when (CoreMessageRef.parse(reason)?.message) {
        CoreMessage.CHATGPT_USAGE_LIMIT -> SummaryRecovery.MANAGE_USAGE
        // The sentence says where: Settings.
        CoreMessage.CHATGPT_SIGN_IN_REQUIRED -> SummaryRecovery.NONE
        else -> SummaryRecovery.RETRY
    }
    return if (sentence != null) {
        SummaryFailure(sentence, null, recovery)
    } else {
        SummaryFailure(Str.SUMMARY_FAILED.message(), chatGptDiagnostic(reason), recovery)
    }
}

/** More → Summarize, or Summarize again once the recording has a summary to replace. */
internal fun summarizeLabel(summary: SummaryState): Str = when (summary) {
    is SummaryState.Ready -> Str.SUMMARY_AGAIN
    is SummaryState.Running -> if (summary.previous != null) Str.SUMMARY_AGAIN else Str.SUMMARY_SUMMARIZE
    is SummaryState.Failed -> if (summary.previous != null) Str.SUMMARY_AGAIN else Str.SUMMARY_SUMMARIZE
    SummaryState.None -> Str.SUMMARY_SUMMARIZE
}

/**
 * Why More → Summarize cannot run now, or null when it can. One reason, in this order: a take still being
 * written has no transcript yet either, and says so first, as Rename does; then no transcript; then a
 * transcription of it queued or running, which would replace the text; then the ChatGPT sign-in; then a
 * summary already running.
 */
internal fun summarizeBlocked(
    writing: Boolean,
    hasTranscript: Boolean,
    transcriptionRunning: Boolean,
    connection: ChatGptConnection,
    summary: SummaryState,
): Str? = when {
    writing -> Str.DETAIL_STILL_RECORDING
    !hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
    transcriptionRunning -> Str.DETAIL_TRANSCRIBING
    connection is ChatGptConnection.SignedOut || connection is ChatGptConnection.Expired -> Str.CORE_CHATGPT_SIGN_IN_REQUIRED
    summary is SummaryState.Running -> Str.SUMMARY_RUNNING
    else -> null
}

/**
 * The Transcript | Summary chips: once the recording has a summary, or right after Summarize was chosen —
 * and never while the transcript is being edited.
 */
internal fun showsSummaryChips(summary: SummaryState, summaryChosen: Boolean, editing: Boolean): Boolean =
    !editing && (summary != SummaryState.None || summaryChosen)

/** The footer's model: OpenAI's name for it while the plan lists it, otherwise its id. */
internal fun summaryModelLabel(model: String, connection: ChatGptConnection): String =
    (connection as? ChatGptConnection.SignedIn)?.models?.firstOrNull { it.id == model }?.label ?: model
