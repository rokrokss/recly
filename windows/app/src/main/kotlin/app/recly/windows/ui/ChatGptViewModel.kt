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
import recly.core.chatgpt.FillNeeds
import recly.core.chatgpt.FillResult
import recly.core.chatgpt.FillRun
import recly.core.chatgpt.Summaries
import recly.core.chatgpt.SummaryCitations
import recly.core.chatgpt.SummaryPreferences
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.notes.NoteSection
import recly.core.notes.Notes
import recly.core.notes.NotesPreferences
import recly.core.notes.RecordingNotes
import recly.core.platform.Logger
import recly.core.storage.StorageKind

/** What the ChatGPT section says under its rows, besides the account itself (docs/09 "AI view"). */
sealed interface ChatGptNotice {
    /** [reason] is a `CoreMessage` wire code — or, for a sign-in this shell could not even start, its diagnostic. */
    data class SignInFailed(val reason: String) : ChatGptNotice

    /** Signed out here, but OpenAI did not confirm the revocation. Not a failure: nothing is left on this PC. */
    data object RevokeUnconfirmed : ChatGptNotice
}

/**
 * One of Settings → Notes' My sections as the window edits it (docs/09 "AI view"). [key] is the window's own, so a
 * section keeps its fields while the core gives it its [id]; an empty [id] is one not kept yet.
 */
data class SectionDraft(val key: Int, val id: String, val name: String, val description: String)

/**
 * docs/15 §10 "Sign in with ChatGPT", the shell's half: the loopback and the browser. The core owns the
 * rest — PKCE, the exchange, the tokens, the model list — and the tokens never pass through here.
 *
 * Its own [action] rather than [ShellModel.action]: that one is the Drive sign-in's and the tray's, and a
 * ChatGPT sign-in left waiting in the browser for minutes would hold their buttons in "…" with it.
 */
class ChatGptViewModel(
    private val account: ChatGptAccount,
    /** Settings → Notes' About you and the title dialog's boxes are the requests' own (docs/08 "AI requests"). */
    private val summaries: Summaries,
    /**
     * docs/08 "Organizing": the consent the sign-in asks for, a recording start while it is missing, and Settings →
     * Notes' instructions and sections.
     */
    private val notes: Notes,
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

    /** About you, which notes and answers on this PC send, and how the title dialog's boxes were left — kept signed out too. */
    var preferences: SummaryPreferences by mutableStateOf(SummaryPreferences())
        private set

    /** One save at a time, in the order they were made: each one carries every field as it was then. */
    private val saving = Mutex()

    /**
     * docs/09 "AI view": Settings → Notes' instructions as last typed — shown at once, the core's trimmed copy once
     * it has kept them — and My sections as the window edits them ([sections]).
     */
    var notesInstructions: String by mutableStateOf("")
        private set

    /** My sections, in order: the kept ones and any just added that has no name yet. */
    var sections: List<SectionDraft> by mutableStateOf(emptyList())
        private set

    /** What the core holds now, so a save that would change nothing sends nothing. */
    private var notesKept: NotesPreferences? = null

    private var nextSectionKey = 0

    private val notesSaving = Mutex()

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

    /**
     * docs/08 "Organizing": the consent question is up — right after a sign-in, or in front of a recording start
     * ([askOrganizeConsent]). Continue agrees, Sign out signs out ([answerOrganizeConsent]).
     */
    var organizeConsent: Boolean by mutableStateOf(false)
        private set

    /** Signed in to ChatGPT and not agreed to organizing yet (`Notes.observeNeedsConsent`): a start asks first. */
    var needsOrganizeConsent: Boolean by mutableStateOf(false)
        private set

    /** The first sign-in's welcome, held until the consent question it follows is answered. */
    private var welcomeAfterConsent = false

    private var signIn: Job? = null

    /** Bumped by every sign-in and every Cancel, so a browser open that arrives late knows it is stale. */
    @Volatile private var attempt = 0

    /** Follows the core's connection for as long as the app runs, and reads it once now. */
    fun start() {
        scope.launch { account.observe().collect { connection = it } }
        scope.launch { notes.observeNeedsConsent().collect { needsOrganizeConsent = it } }
        scope.launch {
            try {
                summaries.observePreferences().collect { preferences = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Logger.Level.ERROR, "shell.chatgpt.preferences.failed", error = e)
            }
        }
        // Read once: Settings → Notes is the only place this PC changes them, and it holds its own edits.
        scope.launch {
            try {
                val stored = notes.preferences()
                notesSaving.withLock {
                    notesKept = stored
                    notesInstructions = stored.instructions
                    sections = stored.sections.map { SectionDraft(nextSectionKey++, it.id, it.name, it.description) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Logger.Level.ERROR, "shell.notes.preferences.failed", error = e)
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
                    // docs/08 "Organizing": the consent first — a sign-in OpenAI had ended may still hold one — then
                    // the welcome, once it is answered.
                    if (needsConsentNow()) {
                        welcomeAfterConsent = result.welcome
                        organizeConsent = true
                    } else if (result.welcome) {
                        welcome = true
                    }
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

    /** No confirmation: Continue with ChatGPT signs back in to the same registration (docs/09 "AI view"). */
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

    fun saveAboutMe(text: String) = savePreferences { it.copy(aboutMe = text) }

    /**
     * docs/08 "Titles and speaker names": each of the title dialog's ChatGPT boxes, ticked or not, is how the next
     * dialog starts — kept on its own the moment it is changed, as Settings keeps its own.
     */
    fun rememberFillAfterStop(half: FillHalf, ticked: Boolean) = savePreferences {
        when (half) {
            FillHalf.TITLE -> it.copy(fillTitleAfterStop = ticked)
            FillHalf.NAMES -> it.copy(fillNamesAfterStop = ticked)
        }
    }

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

    /** Settings → Notes instructions, once the field is left; empty is allowed. */
    fun saveNotesInstructions(text: String) {
        if (text == notesInstructions) return
        notesInstructions = text
        saveNotes()
    }

    /**
     * Add section: an empty one at the end, while there are fewer than [NotesPreferences.SECTIONS_MAX] — kept only
     * once it has a name. The key of the one added, for its name to take the cursor; null when there is no room.
     */
    fun addSection(): Int? {
        if (sections.size >= NotesPreferences.SECTIONS_MAX) return null
        val key = nextSectionKey++
        sections = sections + SectionDraft(key, "", "", "")
        return key
    }

    /**
     * A section's editing ended — the cursor left it, Return in its name, the window put behind another or closed —
     * with its fields as they are: saved, or gone from the list without a name. A section removed meanwhile is left
     * alone.
     */
    fun endSection(key: Int, name: String, description: String) {
        val at = sections.indexOfFirst { it.key == key }
        if (at < 0) return
        sections = if (name.isBlank()) {
            sections - sections[at]
        } else {
            sections.toMutableList().also { it[at] = it[at].copy(name = name, description = description) }
        }
        saveNotes()
    }

    /** Remove: saved at once, without asking. */
    fun removeSection(key: Int) {
        val section = sections.firstOrNull { it.key == key } ?: return
        sections = sections - section
        saveNotes()
    }

    /**
     * Sends the instructions and the named sections as they are when its turn comes, one save at a time, and gives a
     * new section the id the core gave it — under the key it was edited by, so its fields stay where they are.
     */
    private fun saveNotes() {
        scope.launch {
            notesSaving.withLock {
                val sent = sections.filter { it.name.isNotBlank() }
                val wanted = NotesPreferences(notesInstructions, sent.map { NoteSection(it.id, it.name, it.description) })
                if (wanted == notesKept) return@withLock
                try {
                    val kept = notes.setPreferences(wanted)
                    notesKept = kept
                    // In the order sent: the core keeps every named section, and only cuts them to its limits.
                    val byKey = sent.map { it.key }.zip(kept.sections).toMap()
                    sections = sections.map { draft -> byKey[draft.key]?.let { draft.copy(id = it.id, name = it.name, description = it.description) } ?: draft }
                    if (notesInstructions == wanted.instructions) notesInstructions = kept.instructions
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(Logger.Level.ERROR, "shell.notes.preferences.failed", error = e)
                }
            }
        }
    }

    fun dismissWelcome() {
        welcome = false
    }

    /** docs/08 "Organizing": a recording start from the app while [needsOrganizeConsent] — the same question. */
    fun askOrganizeConsent() {
        organizeConsent = true
    }

    /**
     * The consent question's answer: Continue ([agree] true) agrees, Sign out (false) is Settings' own sign-out, and a
     * question closed without either (null) leaves it to be asked at the next start. The welcome a first sign-in
     * earned follows, unless the answer was to sign out.
     */
    fun answerOrganizeConsent(agree: Boolean?) {
        organizeConsent = false
        val welcomeNext = welcomeAfterConsent
        welcomeAfterConsent = false
        when (agree) {
            // Only while it is still wanted: a sign-out meanwhile must not leave an agreement for the next sign-in.
            true -> scope.launch {
                try {
                    if (notes.needsConsent()) notes.consent()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(Logger.Level.ERROR, "shell.chatgpt.consent.failed", error = e)
                }
            }
            false -> {
                signOut()
                return
            }
            null -> Unit
        }
        if (welcomeNext) welcome = true
    }

    /** [Notes.needsConsent] now; a store that will not be read asks, as one that says so would. */
    private suspend fun needsConsentNow(): Boolean = try {
        notes.needsConsent()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.log(Logger.Level.ERROR, "shell.chatgpt.consent.failed", error = e)
        true
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

/** A sign-in the user cancelled in the browser says nothing (docs/09 "AI view"). */
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

/** The one button under a failed Organize's or Ask's notice (docs/09 "AI view"). */
enum class ChatGptRecovery { MANAGE_USAGE, NONE, RETRY }

/**
 * What a failed request's centred notice says, and the button under it. [attention] is a sentence that says where to
 * go — said in the warning tone, not the failure's red.
 */
data class ChatGptFailure(val headline: UiMessage, val detail: String?, val recovery: ChatGptRecovery, val attention: Boolean = false)

/** [failed] is the headline when the reason has no sentence of its own: `Could not organize`, or Ask's `Could not answer`. */
internal fun chatGptFailure(reason: String, failed: Str): ChatGptFailure {
    val sentence = chatGptSentence(reason)
    val recovery = when (CoreMessageRef.parse(reason)?.message) {
        CoreMessage.CHATGPT_USAGE_LIMIT -> ChatGptRecovery.MANAGE_USAGE
        // The sentence says where: Settings.
        CoreMessage.CHATGPT_SIGN_IN_REQUIRED -> ChatGptRecovery.NONE
        // An account apps cannot use stays one however often it is asked (2026-10-10).
        CoreMessage.CHATGPT_PLAN_REQUIRED -> ChatGptRecovery.NONE
        else -> ChatGptRecovery.RETRY
    }
    return if (sentence != null) {
        ChatGptFailure(sentence, null, recovery, attention = true)
    } else {
        ChatGptFailure(failed.message(), chatGptDiagnostic(reason), recovery)
    }
}

/** More → Organize, or Organize again once the recording has notes (docs/09 "AI view"). */
internal fun organizeLabel(notes: RecordingNotes?): Str = if (notes?.isEmpty == false) Str.NOTES_ORGANIZE_AGAIN else Str.NOTES_ORGANIZE

/**
 * Why More → Organize cannot run now, or null when it can. One reason, in this order: a take still being written has
 * no transcript yet either, and says so first, as Rename does; then no transcript; then a job of the recording not
 * settled yet — [busy] is its reason, the one Edit transcript gives (D16) — which may still replace the text; then
 * the ChatGPT sign-in; then the recording being organized already ([organizing], `RecordingNotes.organizing`).
 */
internal fun organizeBlocked(
    writing: Boolean,
    hasTranscript: Boolean,
    busy: Str?,
    connection: ChatGptConnection,
    organizing: Boolean,
): Str? = askBlocked(writing, hasTranscript, busy, connection) ?: Str.NOTES_ORGANIZING.takeIf { organizing }

/**
 * Why More → Edit notes cannot open now (docs/09 "AI view") — an item offered only while the recording has notes:
 * the take still being written, then the notes being organized, which would land under the editor.
 */
internal fun editNotesBlocked(notes: RecordingNotes, writing: Boolean): Str? = when {
    writing -> Str.DETAIL_STILL_RECORDING
    notes.organizing -> Str.NOTES_ORGANIZING
    else -> null
}

/** The editor's note: Drive and iCloud carry the notes to the other devices; a local folder, or no folder yet, does not. */
internal fun notesEditNote(storage: StorageKind?): Str =
    if (storage == StorageKind.DRIVE || storage == StorageKind.ICLOUD) Str.NOTES_EDIT_NOTE_SHARED else Str.NOTES_EDIT_NOTE_LOCAL

/**
 * Why More → Ask about this recording cannot run now (docs/08 "Ask"): Organize's reasons, but the notes being made
 * are none — a question is asked beside them.
 */
internal fun askBlocked(
    writing: Boolean,
    hasTranscript: Boolean,
    busy: Str?,
    connection: ChatGptConnection,
): Str? = when {
    writing -> Str.DETAIL_STILL_RECORDING
    !hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
    busy != null -> busy
    connection is ChatGptConnection.SignedOut || connection is ChatGptConnection.Expired -> Str.CORE_CHATGPT_SIGN_IN_REQUIRED
    else -> null
}

/**
 * docs/08 "Titles and speaker names": the two things More can fill in, each its own item — the title and the speakers'
 * names, never one action (2026-10-15) — with what each says while it cannot run, while it runs and when it failed.
 */
enum class FillHalf(
    val item: Str,
    /** `FillNeeds` says nothing of this half is missing. */
    val nothing: Str,
    /** The line under the detail's header while a fill of this half runs. */
    val running: Str,
    val failed: Str,
) {
    TITLE(Str.FILL_TITLE_MENU, Str.FILL_HAS_TITLE, Str.FILL_TITLE_RUNNING, Str.FILL_TITLE_FAILED),
    NAMES(Str.FILL_NAMES_MENU, Str.FILL_NO_UNNAMED, Str.FILL_NAMES_RUNNING, Str.FILL_NAMES_FAILED);

    /** Whether [run] — the recording's fill that is asking ChatGPT now, if any — asks for this half. */
    fun runs(run: FillRun?): Boolean = when (this) {
        TITLE -> run?.title == true
        NAMES -> run?.names == true
    }

    /** Whether the recording still lacks this half: what the item is offered by. */
    fun needed(needs: FillNeeds): Boolean = when (this) {
        TITLE -> needs.title
        NAMES -> needs.names
    }
}

/**
 * Why More → Fill in title or Fill in speaker names cannot run now (docs/09 "AI view"), in this order: Ask's
 * reasons, then a fill of [half] running ([running] — `observeFills()`, the title dialog's too), then nothing of it
 * missing — [needed] is [FillHalf.needed] for the recording as it is now, which a fill that is running may be about
 * to change.
 */
internal fun fillBlocked(
    half: FillHalf,
    writing: Boolean,
    hasTranscript: Boolean,
    busy: Str?,
    connection: ChatGptConnection,
    running: Boolean,
    needed: Boolean,
): Str? = askBlocked(writing, hasTranscript, busy, connection) ?: when {
    running -> Str.FILL_RUNNING
    !needed -> half.nothing
    else -> null
}

/** What the detail says under its header once Fill in title or Fill in speaker names has answered (docs/08 "Titles and speaker names"). */
sealed interface FillNotice {
    /** Nothing was sent: the More menu's own reason, as a refused Transcribe again says it. */
    data class Refused(val reason: Str) : FillNotice

    /** [half]'s failure — `Could not fill in the title` — with the core's sentence for [reason] when it gave one. */
    data class Failed(val half: FillHalf, val reason: String?) : FillNotice
}

/**
 * [result] of asking for [half] as a line under the header, or null for nothing to say — what arrived is seen where it
 * goes. Nothing of it missing says the item's own reason; ChatGPT answering without the half asked is that half's
 * failure alone. A null [result] is a call that threw. [busy] is what the More menu says about the job in the way.
 */
internal fun fillNotice(result: FillResult?, half: FillHalf, busy: Str): FillNotice? = when (result) {
    is FillResult.Filled -> if (if (half == FillHalf.TITLE) result.title else result.names > 0) null else FillNotice.Failed(half, null)
    FillResult.NothingToFill -> FillNotice.Refused(half.nothing)
    FillResult.Busy -> FillNotice.Refused(busy)
    FillResult.NoTranscript -> FillNotice.Refused(Str.DETAIL_NO_TRANSCRIPT)
    is FillResult.Failed -> FillNotice.Failed(half, result.reason)
    null -> FillNotice.Failed(half, null)
}

/**
 * The Transcript | Notes chips (docs/09 "AI view"): once the recording has notes, or Organize was chosen
 * ([notesChosen]), or the last Organize of it failed — and never while the transcript is being edited.
 */
internal fun showsNotesChips(notes: RecordingNotes?, notesChosen: Boolean, editing: Boolean): Boolean =
    !editing && (notesChosen || notes?.isEmpty == false || notes?.failure != null)

/**
 * The footer's model under an answer: the plan's name for it when it was written (kept with it so it reads the same
 * signed out), else OpenAI's name for it while the plan lists it, otherwise its id.
 */
internal fun chatGptModelLabel(model: String, modelName: String?, connection: ChatGptConnection): String =
    modelName ?: (connection as? ChatGptConnection.SignedIn)?.models?.firstOrNull { it.id == model }?.label ?: model

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
 * One piece of an answer as the detail draws it: plain text, or a citation — its text as written,
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
