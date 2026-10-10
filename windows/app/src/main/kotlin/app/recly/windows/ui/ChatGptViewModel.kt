package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.auth.LoopbackReceiver
import app.recly.windows.auth.openInSystemBrowser
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.ChatGptAccount
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptResult
import recly.core.chatgpt.Summaries
import recly.core.chatgpt.Summary
import recly.core.chatgpt.SummaryCitations
import recly.core.chatgpt.SummaryFormat
import recly.core.chatgpt.SummaryPreferences
import recly.core.chatgpt.SummaryState
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.platform.Logger
import recly.core.storage.StorageKind

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
    /** Settings → ChatGPT's format, My format and About you are the summaries' own (docs/08 "Summaries"). */
    private val summaries: Summaries,
    private val receiver: LoopbackReceiver,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val browser: suspend (String) -> Unit = { openInSystemBrowser(it) },
    /** How long the loopback waits for the browser to come back (docs/15 §10: five minutes). */
    private val timeout: Duration = SIGN_IN_TIMEOUT,
    private val browserTimeout: Duration = BROWSER_TIMEOUT,
) {
    var connection: ChatGptConnection by mutableStateOf(account.observe().value)
        // Set by the off-screen shots alone, which draw the signed-in rows without signing in.
        internal set

    /** What every summary on this PC is asked for: its format, My format's words and About you — kept signed out too. */
    var preferences: SummaryPreferences by mutableStateOf(SummaryPreferences())
        private set

    /** One save at a time, in the order they were made: each one carries every field as it was then. */
    private val saving = Mutex()

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
        scope.launch {
            try {
                summaries.observePreferences().collect { preferences = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Logger.Level.ERROR, "shell.chatgpt.preferences.failed", error = e)
            }
        }
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

    /** Settings → Summary format: saved the moment it is chosen, like the model. */
    fun selectFormat(format: SummaryFormat) = savePreferences { it.copy(format = format) }

    /** My format's words, once the field is left; empty is allowed, and puts the format back to General. */
    fun saveCustomFormat(text: String) = savePreferences { it.copy(customFormat = text) }

    fun saveAboutMe(text: String) = savePreferences { it.copy(aboutMe = text) }

    /** Shown at once; the core keeps it trimmed and capped, and [preferences] follows what it kept. */
    private fun savePreferences(change: (SummaryPreferences) -> SummaryPreferences) {
        val wanted = change(preferences)
        if (wanted == preferences) return
        preferences = wanted
        scope.launch {
            saving.withLock {
                try {
                    summaries.setPreferences(wanted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(Logger.Level.ERROR, "shell.chatgpt.preferences.failed", error = e)
                }
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
 * failure is the screen's own headline with the diagnostic under it.
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

/**
 * What a failed summary's centred notice says, and the button under it. [attention] is a sentence that says where to
 * go — said in the warning tone, not the failure's red.
 */
data class SummaryFailure(val headline: UiMessage, val detail: String?, val recovery: SummaryRecovery, val attention: Boolean = false)

/** [failed] is the headline when the reason has no sentence of its own: `Could not summarize`, or Ask's `Could not answer`. */
internal fun summaryFailure(reason: String, failed: Str = Str.SUMMARY_FAILED): SummaryFailure {
    val sentence = chatGptSentence(reason)
    val recovery = when (CoreMessageRef.parse(reason)?.message) {
        CoreMessage.CHATGPT_USAGE_LIMIT -> SummaryRecovery.MANAGE_USAGE
        // The sentence says where: Settings.
        CoreMessage.CHATGPT_SIGN_IN_REQUIRED -> SummaryRecovery.NONE
        // An account apps cannot use stays one however often it is asked (2026-10-10).
        CoreMessage.CHATGPT_PLAN_REQUIRED -> SummaryRecovery.NONE
        else -> SummaryRecovery.RETRY
    }
    return if (sentence != null) {
        SummaryFailure(sentence, null, recovery, attention = true)
    } else {
        SummaryFailure(failed.message(), chatGptDiagnostic(reason), recovery)
    }
}

/** More → Summarize, or Summarize again once the recording has a summary to replace. */
internal fun summarizeLabel(summary: SummaryState): Str = when (summary) {
    is SummaryState.Ready -> Str.SUMMARY_AGAIN
    is SummaryState.Running -> if (summary.previous != null) Str.SUMMARY_AGAIN else Str.SUMMARY_SUMMARIZE
    is SummaryState.Failed -> if (summary.previous != null) Str.SUMMARY_AGAIN else Str.SUMMARY_SUMMARIZE
    SummaryState.None -> Str.SUMMARY_SUMMARIZE
}

/** The summary Summarize again would replace: the one on show, or the one still kept under a run or a failure. */
internal fun SummaryState.saved(): Summary? = when (this) {
    is SummaryState.Ready -> summary
    is SummaryState.Running -> previous
    is SummaryState.Failed -> previous
    SummaryState.None -> null
}

/** Summarize again over a summary the user edited asks before replacing it (docs/08 "Summaries"). */
internal fun summarizeAsksFirst(summary: SummaryState): Boolean = summary.saved()?.editedAt != null

/**
 * More → Edit summary: offered once the recording has a summary, and disabled with `Summarizing…` while a new
 * one is being made over it. Null when there is nothing to offer.
 */
internal data class SummaryEditItem(val summary: Summary, val blocked: Str?)

internal fun summaryEditItem(summary: SummaryState): SummaryEditItem? = when (summary) {
    is SummaryState.Ready -> SummaryEditItem(summary.summary, null)
    is SummaryState.Running -> summary.previous?.let { SummaryEditItem(it, Str.SUMMARY_RUNNING) }
    // A failed run keeps the summary under it, and that one can still be edited.
    is SummaryState.Failed -> summary.previous?.let { SummaryEditItem(it, null) }
    else -> null
}

/**
 * What saving the edit changes: the text, trimmed as the core keeps it. An emptied field is not an edit — the core
 * keeps the summary as it was — so it neither offers Save nor asks before it is left.
 */
internal fun summaryEdited(original: String, text: String): Boolean = text.trim().let { it.isNotEmpty() && it != original.trim() }

/** The editor's note: Drive and iCloud carry the summary to the other devices; a local folder, or no folder yet, does not. */
internal fun summaryEditNote(storage: StorageKind?): Str =
    if (storage == StorageKind.DRIVE || storage == StorageKind.ICLOUD) Str.SUMMARY_EDIT_NOTE_SHARED else Str.SUMMARY_EDIT_NOTE_LOCAL

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
): Str? = askBlocked(writing, hasTranscript, transcriptionRunning, connection)
    ?: if (summary is SummaryState.Running) Str.SUMMARY_RUNNING else null

/**
 * Why More → Ask about this recording cannot run now (docs/08 "Ask"): Summarize's reasons, but a summary being made
 * is none — a question is asked beside it.
 */
internal fun askBlocked(
    writing: Boolean,
    hasTranscript: Boolean,
    transcriptionRunning: Boolean,
    connection: ChatGptConnection,
): Str? = when {
    writing -> Str.DETAIL_STILL_RECORDING
    !hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
    transcriptionRunning -> Str.DETAIL_TRANSCRIBING
    connection is ChatGptConnection.SignedOut || connection is ChatGptConnection.Expired -> Str.CORE_CHATGPT_SIGN_IN_REQUIRED
    else -> null
}

/**
 * The Transcript | Summary chips: once the recording has a summary, or right after Summarize was chosen —
 * and never while the transcript is being edited.
 */
internal fun showsSummaryChips(summary: SummaryState, summaryChosen: Boolean, editing: Boolean): Boolean =
    !editing && (summary != SummaryState.None || summaryChosen)

/**
 * The footer's model: the plan's name for it when it was written ([Summary.modelName], kept with it so it reads the
 * same signed out), else OpenAI's name for it while the plan lists it, otherwise its id.
 */
internal fun summaryModelLabel(model: String, modelName: String?, connection: ChatGptConnection): String =
    modelName ?: (connection as? ChatGptConnection.SignedIn)?.models?.firstOrNull { it.id == model }?.label ?: model

/** docs/08 "Summaries": a format's name, in Settings and in More → Summarize as. */
internal fun summaryFormatLabel(format: SummaryFormat): Str = when (format) {
    SummaryFormat.AUTO -> Str.SUMMARY_FORMAT_AUTO
    SummaryFormat.ONE_ON_ONE -> Str.SUMMARY_FORMAT_ONE_ON_ONE
    SummaryFormat.LECTURE -> Str.SUMMARY_FORMAT_LECTURE
    SummaryFormat.INTERVIEW -> Str.SUMMARY_FORMAT_INTERVIEW
    SummaryFormat.CUSTOM -> Str.SUMMARY_FORMAT_CUSTOM
}

/**
 * The line under a summary (docs/09 "Summary view"): `ChatGPT · <model>`, then the format unless it is General, then
 * `Edited` once the user has changed it — one separator throughout.
 */
internal fun summaryFooter(strings: Strings, model: String, format: SummaryFormat, edited: Boolean): String =
    buildList {
        add(strings[Str.SUMMARY_MODEL, model])
        if (format != SummaryFormat.AUTO) add(strings[summaryFormatLabel(format)])
        if (edited) add(strings[Str.SUMMARY_EDITED])
    }.joinToString(FOOTER_SEPARATOR)

/** The footer's own join, the one [Str.SUMMARY_MODEL] puts between ChatGPT and the model. */
private const val FOOTER_SEPARATOR = " · "

/** docs/08 "Ask": a preset's chip. Translate names the app's language the way the language list does. */
internal fun askPresetLabel(preset: AskPreset, strings: Strings): String = when (preset) {
    AskPreset.FOLLOW_UP_EMAIL -> strings[Str.ASK_FOLLOW_UP_EMAIL]
    AskPreset.ACTION_ITEMS -> strings[Str.ASK_ACTION_ITEMS]
    AskPreset.OPEN_QUESTIONS -> strings[Str.ASK_OPEN_QUESTIONS]
    AskPreset.TRANSLATE -> strings[
        Str.ASK_TRANSLATE,
        AppLanguage.choices.firstOrNull { it.first == AppLanguage.of(strings.language) }?.let { strings[it.second] } ?: strings.language,
    ]
    AskPreset.MY_SPEAKING -> strings[Str.ASK_MY_SPEAKING]
}

/**
 * One piece of a summary or an answer as the detail draws it: plain text, or a citation — its text as written,
 * brackets and all, the time inside them for a screen reader, and the second it plays from.
 */
internal data class CitationRun(val text: String, val time: String? = null, val atSec: Double? = null)

/** [text] cut at its citations ([SummaryCitations], the one rule every shell reads them by): every character once, in order. */
internal fun citationRuns(text: String): List<CitationRun> {
    val runs = mutableListOf<CitationRun>()
    var at = 0
    for (citation in SummaryCitations.parse(text)) {
        if (citation.offset > at) runs += CitationRun(text.substring(at, citation.offset))
        val written = text.substring(citation.offset, citation.offset + citation.length)
        runs += CitationRun(written, written.removePrefix("[").removeSuffix("]"), citation.atSec)
        at = citation.offset + citation.length
    }
    if (at < text.length) runs += CitationRun(text.substring(at))
    return runs
}
