@file:OptIn(ExperimentalTime::class)

package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.auth.RevokeResult
import app.recly.windows.auth.SignInResult
import app.recly.windows.core.AppGraph
import app.recly.windows.core.AppModule
import app.recly.windows.core.FfmpegImporter
import app.recly.windows.core.Host
import app.recly.windows.detect.MeetingDetectionRule
import app.recly.windows.detect.MeetingDetector
import app.recly.windows.detect.MeetingNotifier
import app.recly.windows.detect.MicAccess
import app.recly.windows.detect.MicrophoneAccess
import app.recly.windows.detect.UnknownMicrophoneAccess
import app.recly.windows.detect.NoDetection
import app.recly.windows.detect.RunningApps
import app.recly.windows.helper.CaptureHelper
import app.recly.windows.helper.HelperClient
import app.recly.windows.helper.HelperNotice
import app.recly.windows.helper.NetworkCost
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.coreMessage
import app.recly.windows.i18n.message
import app.recly.windows.APP_NAME
import app.recly.windows.jobs.CoreJobQueue
import app.recly.windows.jobs.JobRunner
import app.recly.windows.jobs.RecentItem
import app.recly.windows.jobs.Recents
import app.recly.windows.record.Completion
import app.recly.windows.record.PowerSources
import app.recly.windows.record.RecordingOutcome
import app.recly.windows.record.RecordingRecovery
import app.recly.windows.record.StopResult
import app.recly.windows.record.WindowsRecorder
import app.recly.windows.record.completeRecording
import app.recly.windows.record.line
import app.recly.windows.settings.AppTheme
import app.recly.windows.settings.LaunchAtLogin
import app.recly.windows.settings.LaunchAtLogins
import app.recly.windows.settings.RecordingMode
import app.recly.windows.settings.Settings
import app.recly.windows.ui.component.BadgeTone
import app.recly.windows.ui.theme.Motion
import app.recly.windows.ui.theme.ProcessingState
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.prefs.BackingStoreException
import javax.swing.JFileChooser
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import okio.Path
import recly.core.DisconnectResult
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskState
import recly.core.chatgpt.FillRun
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepReport
import recly.core.model.Highlight
import recly.core.model.RecordingStatus
import recly.core.notes.NoteEdit
import recly.core.notes.NoteLine
import recly.core.notes.NotesHeadings
import recly.core.notes.RecordingNotes
import recly.core.platform.Logger
import recly.core.processing.ProcessingSaveResult
import recly.core.recording.DeleteResult
import recly.core.recording.ExportFormat
import recly.core.recording.ImportResult
import recly.core.recording.SearchHit
import recly.core.transcribe.EditResult
import recly.core.transcribe.RetranscribeResult
import recly.core.transcribe.TranscriptEdit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import recly.core.recording.RecordingRecord
import recly.core.storage.StorageKind
import recly.core.transcribe.TranscriptAvailability
import recly.core.transcribe.Transcript

/**
 * docs/09 screen principle 1: the two state codes that are a move rather than a state — the Mac's
 * `RecorderState.starting` and `.stopping`, which is where its node reads them from.
 */
enum class Transition { STARTING, STOPPING }

/**
 * docs/14 deliverable 5: recording cannot start because the capture helper is missing or does not answer — said
 * once the shell has looked, and only on Windows: the development host runs without one on purpose.
 */
internal fun helperUnavailable(windows: Boolean, ready: Boolean, missing: Boolean, version: String?): Boolean =
    windows && ready && (missing || version == null)

/**
 * What the line says once a stopped recording has been handed to the queue: a job that could not be made comes first,
 * then a recording the helper's death ended, then what the queue did with it ([line]) — and back to waiting when it
 * was simply queued.
 */
internal fun completionLine(failed: Boolean, completion: Completion?): Str = when {
    completion == null -> Str.ENQUEUE_FAILED
    failed -> Str.RECORDING_FAILED
    else -> completion.line() ?: Str.STATUS_WAITING
}

/**
 * docs/09 "What a start or a stop says": what a stop had to report — anything [completionLine] says but plain waiting —
 * is news of that moment, so the wait for Drive a job pass finds next does not take its place on the line. The banner
 * and the row already say that wait.
 */
internal fun isStopReport(status: UiMessage): Boolean = (status as? UiMessage.Res)?.key in STOP_REPORTS

private val STOP_REPORTS = setOf(
    Str.ENQUEUE_FAILED, Str.RECORDING_FAILED, Str.RECORDING_TITLE_TOO_LATE, Str.ENQUEUE_NO_WORKFLOW,
    Str.ENQUEUE_PARTS_PURGED, Str.ENQUEUE_SKIPPED_SHORT, Str.ENQUEUE_ALREADY_DONE,
)

/**
 * D21 · D22: a disconnect's outcome under the Drive row — what failed in the danger colour, what is in the way in the
 * warning ink, the rest muted — and nothing where the revoke-debt rows under it already say it.
 */
internal fun disconnectNote(message: UiMessage): ShellNotice? {
    val tone = when ((message as? UiMessage.Res)?.key) {
        Str.DISCONNECT_STILL_LISTED -> return null
        Str.DISCONNECT_REVOKE_FAILED, Str.DISCONNECT_CLEANUP_FAILED, Str.DISCONNECT_SAVE_FAILED -> BadgeTone.DANGER
        Str.DISCONNECT_BUSY, Str.DISCONNECT_STOP_RECORDING, Str.PLAYER_STOP_FAILED -> BadgeTone.WARNING
        else -> BadgeTone.NEUTRAL
    }
    return ShellNotice(message, tone)
}

/** The privacy policy's canonical page, as the phones open it (`SettingsScreen.privacyPolicyUrl`). */
internal fun privacyPolicyUrl(language: String): String =
    "https://recly.dev/policy/" + if (language == "ko") "privacy-policy.ko" else "privacy-policy"

/** A wait for the Drive connection is ended by connecting Drive, not by asking the queue again (D8). */
internal fun retriesBySigningIn(status: JobStatus?): Boolean = status == JobStatus.NEEDS_AUTH

/**
 * docs/03 "Naming rules": an imported file's start — when the file was created, where the file system keeps that, and
 * when it was last written otherwise. Zero or less is a file system that does not keep it.
 */
internal fun importStartMillis(createdMillis: Long?, modifiedMillis: Long): Long =
    createdMillis?.takeIf { it > 0 } ?: modifiedMillis

/** What the detail window shows for one recording (docs/08 "Result files"). */
data class RecordingDetail(
    val recordingId: String,
    /** A name rather than a sentence: the window is open while the language can change under it. */
    val title: UiMessage,
    val loading: Boolean = true,
    val transcript: Transcript? = null,
    val availability: TranscriptAvailability = TranscriptAvailability.PENDING,
    /** docs/08 "Result files": the audio beside the transcript, when this PC still has it. */
    val audio: RecordingPlaylist.Selection = RecordingPlaylist.Selection.EMPTY,
    /** A take still being written to has nothing whole to play yet, so the detail offers nothing. */
    val writing: Boolean = false,
    /** docs/03 ADR-017: how the trip to Drive for the parts the retention sweep took is going. */
    val driveFetch: DriveFetch = DriveFetch.DECIDING,
    /** How much of that trip is done, 0 to 1, by the bytes of the parts it brings back. */
    val fetchProgress: Float = 0f,
    /** docs/03 "Storage location": the trip is to the local folder this PC picked, not to Drive. */
    val folder: Boolean = false,
    /** Where the recording's folder is, and null while it is not uploaded: whether an edit reaches the other devices. */
    val storage: StorageKind? = null,
    /** Its folder in Drive, once it has one — More → Open in Drive (D24). */
    val driveUrl: String? = null,
    /** Its folder under the local folder, once its files are there — More → Open the folder (D24). */
    val folderPath: String? = null,
    /** docs/03 "Metadata": the moments the user marked, in seconds of the recording. */
    val highlights: List<Double> = emptyList(),
    /** The recording's whole length as its meta records it, which picks how its times are drawn (2026-10-08). */
    val lengthSec: Double? = null,
    /** A job of this recording has not settled: editing and transcribing again wait for it (docs/08 "Editing"). */
    val transcribing: Boolean = false,
    /** That job is a re-transcription (docs/10 "Re-transcription"), on this PC or with a provider. */
    val retranscribing: Boolean = false,
    val retranscribingLocal: Boolean = false,
    /** Neither in storage yet nor another device's: there is no folder to transcribe again into. */
    val notUploaded: Boolean = false,
    /** An unsettled job of this recording waits for the Drive connection (`NEEDS_AUTH`). */
    val waitsForDrive: Boolean = false,
    /**
     * Why Edit transcript and Transcribe again wait while [transcribing] (2026-10-08): `Transcribing…` only
     * while a transcription of it is really queued or running, `Waiting for Drive` for a job parked on the
     * connection, `Not uploaded yet` for any other upload still to happen.
     */
    val busyReason: Str = Str.DETAIL_TRANSCRIBING,
    /** Why Transcribe again, Fill in title or Fill in speaker names did not start, said under the header for a moment. */
    val notice: Str? = null,
    /**
     * docs/08 "Titles and speaker names": Fill in title or Fill in speaker names came to nothing — `Could not fill in the
     * title`, said under the header for a moment.
     */
    val fillFailure: FillNotice.Failed? = null,
    /** docs/10 "Search": the query this detail was opened from, to tint, and the hit to scroll to. */
    val find: String? = null,
    val findAtSec: Double? = null,
    /**
     * The Notes chip is the chosen one (docs/09 "AI view"). A detail opens on the transcript, or on notes a search
     * found it in.
     */
    val showingNotes: Boolean = false,
    /** docs/08 "Ask": the question asked about it in this run of the app, and where it has got to. */
    val ask: AskState = AskState.None,
    /** docs/08 "Organizing": its notes as the core has them — whether they are being made, and the last failure; null until read. */
    val notes: RecordingNotes? = null,
    /**
     * The recording as its list row has it ([Recents.one]), null until it has been read: More → Delete's rule and
     * what the delete dialog asks with (2026-10-15), the same as the list's own Delete had.
     */
    val row: RecentItem? = null,
)

/** docs/10 "Re-transcription": what the confirmation names — the method and the language — before it runs. */
data class RetranscribeRequest(
    val recordingId: String,
    /** The provider's name, or null for on-device transcription. */
    val provider: String?,
    val language: recly.core.model.Language,
    /** The transcript was edited or its speakers named, and the new one replaces that. */
    val replacesEdits: Boolean,
)

/** What the player bar has to say while the parts are on their way back, and after. */
enum class DriveFetch {
    /**
     * Whether there is a trip to make is not known yet — asking Drive whether it holds the
     * recording is itself a round trip. The bar keeps its clock and offers no Play until this is
     * over: what Play would start is not settled while it lasts.
     */
    DECIDING,

    /** Nothing to fetch, or the fetch is over: what the bar shows is what there is. */
    IDLE,
    FETCHING,
    FAILED,
}

/**
 * A sentence a surface says about what the user just did, in its tone (docs/09 "Every state is color + text"): a
 * failure in the danger colour, a wait or a refusal in the warning ink, anything else muted.
 */
data class ShellNotice(val message: UiMessage, val tone: BadgeTone)

/**
 * docs/03: the recording the rename dialog is asking about, and the name the field opened on. Plain
 * text rather than a [UiMessage], because it is what the user is editing rather than what the app
 * is saying.
 */
data class RenameRequest(val recordingId: String, val title: String)

/**
 * Everything the tray shows and everything its items do — the Mac's `MenuModel` (docs/12 "Menu bar"),
 * in Kotlin. One per process: it owns the core, the recorder and the executor, and `⌘Q` and the
 * menu's quit item have to be talking to the same recorder.
 *
 * Its own scope, not the composition's: the executor's five-minute timer and the helper reader
 * outlive any window, and neither belongs on the UI thread.
 */
class ShellModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * docs/07: the app's language, held here rather than in the composition because the tray
     * balloons and the sign-in page are drawn outside one. Built before [load] so the very first
     * status line and the tray already have a language.
     */
    val localization: Localization = Localization(),
) {
    private val statusLine = StatusLine(Str.STATUS_OPENING.message())

    /**
     * The tray's first line, as a name rather than a sentence: choosing English in the settings
     * window has to change what is already on the tray, not only what is written next (docs/07).
     */
    var status: UiMessage
        get() = statusLine.text
        // A diagnostic belongs to the line it came with, and never outlives it; see [StatusLine].
        private set(value) = statusLine.say(value)

    /**
     * The diagnostic behind [status], when the line that set it carried one (`CODE|detail`,
     * docs/07 §5). On its own line: an AWT menu item is one run of text in the system font, so the
     * tray cannot put it under the status any other way.
     */
    val statusDetail: String? get() = statusLine.detail
    var recording: Boolean by mutableStateOf(false)
        private set

    /**
     * docs/09 screen principle 1: the two codes between `IDLE` and `REC` — a capture whose helper is coming
     * up, and one whose last segment is being closed. Null the rest of the time, when the state the
     * node says is a state rather than a move between two of them.
     *
     * Its own field and not [action]: that one is the *button's* window (docs/09 trend 2) and every
     * high-risk action on this shell shares it, so a sign-in would have the node saying `STARTING`.
     * The Mac has no such field because its `RecorderState` carries all four codes itself.
     */
    var transition: Transition? by mutableStateOf(null)
        private set
    var ready: Boolean by mutableStateOf(false)
        private set
    /** docs/14 deliverable 5: no helper binary — recording is off, and the tray says why. */
    var helperMissing: Boolean by mutableStateOf(false)
        private set
    /** A job is parked waiting for a sign-in (docs/06). The tray says so. */
    var needsAuth: Boolean by mutableStateOf(false)
        private set

    /**
     * docs/10 "Failures the user can fix, and their notices": the whole queue's blocked jobs, folded one entry
     * per reason. The popup's banner draws it, the tray icon wears it, and [JobAlertBalloons] turns
     * a *change* in it into a balloon — never a reading that said the same thing as the last one.
     */
    var alerts: List<JobAlert> by mutableStateOf(emptyList())
        private set
    var signedIn: Boolean by mutableStateOf(false)
        private set
    var recentsLoading: Boolean by mutableStateOf(true)
        private set
    var recents: List<RecentItem> by mutableStateOf(emptyList())
        private set
    /**
     * docs/09 screen principle 2: how far down the recordings the ledger has been read — one [Recents.PAGE]
     * to begin with, and another one every time the user scrolls onto the last row
     * ([loadMoreRecents]). Every refresh reads this much, so a job or a pull moving a row does not
     * fold the list back up under a user who had scrolled down it.
     */
    private var recentsLimit = Recents.PAGE
    /**
     * The first words of each transcript this PC holds, by recording id, for the Details list's rows (2026-10-08) —
     * asked of the core for the rows the ledger has read, and again whenever those rows or a transcript change.
     * A recording with no transcript here is not in it.
     */
    var previews: Map<String, String> by mutableStateOf(emptyMap())
        private set
    /**
     * docs/08 "Titles and speaker names": the fills asking ChatGPT now, one per recording — a publication's and the
     * detail's alike ([followFills]). Their rows say `Filling in`, and the open detail what is being filled in. Set
     * here only by the core, and by the off-screen shots, which draw a run without asking ChatGPT.
     */
    var fills: List<FillRun> by mutableStateOf(emptyList())
        internal set

    /** Whether a [loadMoreRecents] is already reading, so scrolling does not ask twice for a page. */
    private var loadingMoreRecents = false
    /**
     * docs/03: a recording that has just ended and is waiting for its name. The window the tray
     * shows for it is the only thing standing between the stop and the job (see [completeRecording]),
     * and [TitleGate] is what keeps a new start from walking past one.
     */
    private val titles = TitleGate()

    val titlePrompt: RecordingOutcome? get() = titles.pending

    /**
     * docs/09 screen principle 6: the tray's own window. The AWT menu can only carry words, so everything
     * with a shape — the state nodes, the ledger — lives in a Compose popup and this is whether it
     * is up.
     */
    var popupOpen: Boolean by mutableStateOf(false)

    /** docs/08 Result files: the window that shows what the transcribe step wrote. */
    var recordingsOpen: Boolean by mutableStateOf(false)

    /** docs/08 "Editing": that window holds an edit with changes, so closing it asks first ([closeRecordings]). */
    var editsPending: Boolean = false

    var closeAsked: Boolean by mutableStateOf(false)
        private set

    fun closeRecordings() {
        if (editsPending) closeAsked = true else recordingsOpen = false
    }

    fun closeAnswered() {
        closeAsked = false
    }

    /** The recording that window is showing, once one has been picked. */
    var detail: RecordingDetail? by mutableStateOf(null)
        private set
    var settingsOpen: Boolean by mutableStateOf(false)
    var processing: ProcessingViewModel? by mutableStateOf(null)
        private set

    /**
     * docs/05: the on-device speech model's download, one per process — settings, the banner, a
     * waiting row and the first-run card all go through it. Null until [load] has opened the core.
     */
    var modelDownload: ModelDownload? by mutableStateOf(null)
        private set

    /** "Not now" on the first-run card. Read at construction, like the other switches of this PC. */
    var modelPromptDismissed: Boolean by mutableStateOf(settings.modelPromptDismissed)
        private set

    var launchAtLogin: Boolean by mutableStateOf(false)
        private set

    /** docs/15 §10 "Sign in with ChatGPT": the settings section and what the notes run on. */
    var chatGpt: ChatGptViewModel? by mutableStateOf(null)
        private set

    /** docs/03 "Deleting in the app": the recording the delete dialog is asking about, while it is up. */
    var deleteRequest: DeleteRequest? by mutableStateOf(null)
        private set

    /** docs/03: the recording the rename dialog is asking about, while it is up. */
    var renameRequest: RenameRequest? by mutableStateOf(null)
        private set

    /**
     * What the last delete or retry of a list row had to say, above the Details list (D9) — and nothing for a delete
     * that did what was asked. The tray's status line carries the same sentence. Put away by a click on it.
     */
    var listMessage: ShellNotice? by mutableStateOf(null)
        private set

    fun dismissListMessage() {
        listMessage = null
    }

    /** A list action's outcome, in the Details window above its list and on the tray's line. */
    private fun sayAboutList(message: UiMessage, tone: BadgeTone) {
        listMessage = ShellNotice(message, tone)
        status = message
    }

    /**
     * A tray row clicked while the Details window is already up: the window takes it through the same leave
     * as its own list ([RecordingsWindow]), so a transcript or notes being edited ask before they go (D7).
     */
    var detailAsked: RecentItem? by mutableStateOf(null)
        private set

    /**
     * Counts the tray rows clicked: a row is the way into its recording (docs/09 screen principle 2, 2026-10-28), so a
     * Details window left behind other windows comes to the front with it, as the Mac's does.
     */
    var recordingsRaised: Int by mutableStateOf(0)
        private set

    fun showDetail(item: RecentItem) {
        if (recordingsOpen) detailAsked = item else openDetail(item)
        recordingsOpen = true
        recordingsRaised++
    }

    fun detailAnswered() {
        detailAsked = null
    }

    /** docs/10 "Re-transcription": the confirmation, while it is up. */
    var retranscribeRequest: RetranscribeRequest? by mutableStateOf(null)
        private set

    /** docs/08 "Ask": the recording the Ask panel is open over, while it is. */
    var askingAbout: String? by mutableStateOf(null)
        private set

    /** docs/09 "Screen principles": the detail player's speed and Skip silence, this PC's own. */
    var playbackSpeed: Float by mutableStateOf(settings.playbackSpeed)
        private set

    var skipSilence: Boolean by mutableStateOf(settings.skipSilence)
        private set

    /**
     * D21: what the last Drive connect or disconnect came to, said under the Drive row in Settings — a connection that
     * failed in the danger colour, never with the raw reason; null once there is nothing to say.
     */
    var driveNote: ShellNotice? by mutableStateOf(null)
        private set

    /** docs/03 "Disconnect": the warning dialog, and the counts it has to name, while it is up. */
    var disconnectPrompt: DisconnectPrompt? by mutableStateOf(null)
        private set

    /**
     * docs/06: how far the last disconnect got. Read at construction, because the settings window
     * can be up before [load] has finished and a disconnect that is owed has to be on screen.
     */
    var disconnectPhase: DisconnectPhase by mutableStateOf(settings.disconnectPhase)
        private set

    /**
     * docs/03: whether Google is still listing this app because a revoke could not take the grant
     * away. Read at construction for the same reason the phase is — it outlives the disconnect that
     * could not pay it, and the settings window has to say so from the first frame after a restart.
     */
    var revokeDebt: Boolean by mutableStateOf(settings.revokeDebt)
        private set

    /**
     * Whether the dialogs on screen are the questions or photographs of them ([DialogMode]). The
     * `--show-…` flags raise them through the `preview…` entries below, and every destructive answer
     * asks this before it does anything.
     */
    var dialogMode: DialogMode by mutableStateOf(DialogMode.LIVE)
        private set

    /**
     * docs/09 trend 2: where the last high-risk action the user asked for is — a sign-in, an upload,
     * an export. Only what the button draws depends on it (`ProcessingButton`); nothing about what
     * any of those actions *do* does. The Mac's `MenuModel.action` is the same field.
     */
    var action: ProcessingState by mutableStateOf(ProcessingState.IDLE)
        private set

    /**
     * Puts [state] up and takes it down again once the button has had its window (docs/09 "Motion").
     *
     * A terminal state left standing is a trap for the *next* press: a button whose operation
     * returns early — refused, nothing to do — never sets `PROCESSING`, so it would read the `DONE`
     * the last operation left behind and wear a check for something that did not happen. The guard
     * is that nothing else has started in the meantime.
     */
    private fun settle(state: ProcessingState) {
        action = state
        scope.launch {
            delay(Motion.PROCESSING_MAX_MS)
            if (action == state) action = ProcessingState.IDLE
        }
    }

    /**
     * When the recording that is running started, for the popup's monospace timer. Set from the
     * recorder's own state callback, so a popup opened ten minutes in still shows ten minutes.
     */
    var recordingSince: Long? by mutableStateOf(null)
        private set

    /**
     * When the recording that is being saved stopped: while the stop files its parts, the popup's timer
     * holds the recording's final length rather than dropping to nothing (2026-10-08), and both go once
     * the recorder is idle again ([stop]).
     */
    var recordingEndedAt: Long? by mutableStateOf(null)
        private set

    /**
     * docs/09 screen principle 6: the levels of the recording that is running, for the popup's strip —
     * empty when there is none. A function rather than state: the recorder's ring changes ten times
     * a second on the helper's reader, and the strip is the one thing that wants to know.
     */
    fun livePeaks(): List<Float> = recorder?.livePeaks() ?: emptyList()

    // --- detection (docs/14 "Detection", ADR-011) -----------------------------------------------------

    /** docs/12 M8: asked once before the first meeting recording. On until the user turns it off. */
    var consentReminder: Boolean by mutableStateOf(true)
        private set

    /** Track layout of the active recording. Desktop starts always use meeting capture. */
    private var captureMode: RecordingMode = RecordingMode.MEETING

    /**
     * A recording that is waiting for the consent question to be answered ([Consent]). Non-null is
     * the dialog the tray shows, and nothing is recorded until it is answered.
     */
    var consentRequest: ConsentRequest? by mutableStateOf(null)
        private set

    /**
     * docs/08 "Organizing": a recording start waiting for the organizing consent question ([ChatGptViewModel.organizeConsent])
     * — it starts whatever the answer ([answerOrganizeConsent]).
     */
    var organizeConsentStart: RecordingMode? by mutableStateOf(null)
        private set

    /** Whether a start asks that question first; a test, which has no ChatGPT sign-in, answers it itself. */
    internal var asksOrganizeConsent: () -> Boolean = { chatGpt?.needsOrganizeConsent == true }

    /**
     * The detection offer that is outstanding. An AWT balloon has no buttons (`TrayNotifier`), so
     * the tray carries the offer as a menu item for as long as it stands.
     */
    var meetingOffer: MeetingDetectionRule.Prompt? by mutableStateOf(null)
        private set

    /** Which offer that is. The tray item carries it back so a stale click does nothing. */
    private var meetingOfferToken: Long = MeetingDetector.NO_OFFER

    /** docs/14 "Permissions": desktop apps and the microphone. [MicAccess.DENIED] is a recording of silence. */
    var micAccess: MicAccess by mutableStateOf(MicAccess.UNKNOWN)
        private set

    /** The registry switch [micAccess] is read from — again at every start, since the user can flip it at any time. */
    private var microphone: MicrophoneAccess = UnknownMicrophoneAccess

    /**
     * A start refused because the microphone is off for desktop apps: the dialog that says so and offers the page
     * that turns it back on, as the Mac's `presentMicrophoneDenied` does.
     */
    var micPrompt: Boolean by mutableStateOf(false)
        private set

    /** deliverable 3: what the bundled helper says it is, or null when it would not run. Said in `shell.ready`. */
    var helperVersion: String? by mutableStateOf(null)
        private set

    /** Settings' one line about the capture helper: there is none, or it does not answer ([helperUnavailable]). */
    val helperUnavailable: Boolean
        get() = helperUnavailable(Host.isWindows, ready, helperMissing, helperVersion)

    /**
     * docs/07 rule 2: what the settings window's language picker shows as chosen — the language the
     * app is in, which with nothing chosen is the system's rather than [AppLanguage.SYSTEM].
     */
    var language: AppLanguage by mutableStateOf(localization.effective)
        private set

    // docs/09: the dark override, read at construction because the tray popup can be up before
    // [load] has finished.

    var theme: AppTheme by mutableStateOf(settings.theme)
        private set

    /**
     * docs/03 "Storage location": where new recordings go, as the settings' chips show it. Read when the
     * core opens and again whenever the settings window comes to the front — the tray reads it too,
     * so it cannot wait for that window.
     */
    var storage: StorageKind by mutableStateOf(StorageKind.DRIVE)
        private set

    /** The folder the user picked for the local folder storage, kept on this PC; null while none is. */
    var localFolder: String? by mutableStateOf(settings.localFolder)
        private set

    /** Whether [localFolder] can be used right now — a USB drive may have been pulled out since. */
    var localFolderAvailable: Boolean by mutableStateOf(false)
        private set

    /** A chip's save in flight, so a second tap does not race its read-back ([selectStorage]); the chips are off for it. */
    var storageBusy: Boolean by mutableStateOf(false)
        private set

    /** Why the last chip did not take, said as a footnote under the chips; null once one has. */
    var storageMessage: UiMessage? by mutableStateOf(null)
        private set

    /**
     * Whether the recording that is ending gets to be named. False only on the way out: the user
     * asked to quit, and a dialog that keeps the app alive to ask for a title is the opposite of
     * that (the Mac's `MenuModel.finish(askingForTitle:)` draws the same line).
     */
    @Volatile private var askTitle: Boolean = true

    /**
     * docs/01 "Long recordings — battery stop": the recording a stop that asks nothing ended. It is queued untitled,
     * as a quit's is, and only that one — a recording started after it is asked as usual.
     */
    @Volatile private var untitledStop: String? = null

    private var graph: AppGraph? = null

    /**
     * The core's log, for a surface that runs work of its own rather than through this model — the
     * detail's [RecordingPlayer], whose ffmpeg is spawned off the composition. Null until [load]
     * has built the graph, which a window opened before then simply has nothing to report to.
     */
    val logger: Logger? get() = graph?.core?.deps?.logger

    /**
     * The detail's player, while the recordings window has one ([RecordingsWindow] registers it and
     * takes it back on the way out). Deleting is this model's and the speaker is the window's, and
     * the two have to meet: ffmpeg holds the part it is decoding open, which on Windows is a file
     * that cannot be removed — see [stopPlayback].
     */
    @Volatile private var player: RecordingPlayer? = null

    fun usePlayer(player: RecordingPlayer?) {
        this.player = player
    }

    /**
     * Why the detail may not play right now — a capture, or a clean-up removing the files it would
     * read. The window draws Play by it and stops what is going when it goes up; see [PlaybackGate].
     */
    private val playbackGate = PlaybackGate()

    val playbackBlocked: Boolean get() = playbackGate.blocked

    /**
     * Stops the detail's playback and waits for it to be gone, before something removes the files
     * it is reading. `RecordingRepository.delete` deletes the rows first and the directory after,
     * so a delete that raced the decoder would leave an orphaned directory behind a row that is
     * already gone — there would be nothing left to try again with.
     *
     * Off the model's own dispatcher: [RecordingPlayer.stop] blocks until ffmpeg has exited.
     *
     * @return whether the speaker is off the files. False is a decoder that outlived the bound, and
     *   a destructive step is refused on it rather than made over a live handle ([PlaybackGate.cleaning]).
     *
     * The background waveform decode ([WaveformPrecompute]) reads the same files and is stopped the
     * same way — it gives way to the gate on its own, and this waits for its ffmpeg to be gone.
     */
    private suspend fun stopPlayback(): Boolean {
        val precomputed = withContext(Dispatchers.IO) { waveforms?.halt() ?: true }
        val player = player ?: return precomputed
        val stopped = withContext(Dispatchers.IO) { player.stop() }
        if (!stopped || !precomputed) graph?.core?.deps?.logger?.log(Logger.Level.ERROR, "shell.play.stop.timeout")
        return stopped && precomputed
    }

    /**
     * docs/09 screen principle 2: the waveform the core kept for [recordingId] ([WaveformPeaks.FILE]), when it
     * still stands for [selection] — as many windows as the detail's decode would make of those parts.
     * Null when there is none or it does not match, and the detail decodes.
     */
    suspend fun keptWaveform(recordingId: String, selection: RecordingPlaylist.Selection): FloatArray? {
        val graph = graph ?: return null
        val kept = runCatching { graph.core.recordings.waveform(recordingId) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.WARN, "shell.waveform.read.failed", error = it) }
            .getOrNull() ?: return null
        return kept.takeIf { it.size == RecordingWaveform.windows(selection) }?.toFloatArray()
    }

    /** What the detail decoded for [recordingId], kept beside its parts for the next open. */
    fun keepWaveform(recordingId: String, peaks: FloatArray) {
        val graph = graph ?: return
        scope.launch(graph.core.deps.io) {
            runCatching { graph.core.recordings.saveWaveform(recordingId, peaks.toList()) }
                .onFailure { graph.core.deps.logger.log(Logger.Level.WARN, "shell.waveform.save.failed", error = it) }
        }
    }

    /** docs/09 screen principle 2: a recording finalized here has its waveform worked out in the background. */
    private var waveforms: WaveformPrecompute? = null

    private var runner: JobRunner? = null
    private var recorder: WindowsRecorder? = null
    private var detector: MeetingDetector? = null
    private var helperCommand: List<String>? = null
    private var balloon: AlertBalloon? = null
    private var recordingBalloon: RecordingBalloon? = null
    private lateinit var launcher: LaunchAtLogin

    /** The same store [localization] keeps the language in — one `java.util.prefs` node, docs/14. */
    private val settings: Settings get() = localization.settings

    /**
     * docs/09 trend 6: the settings window's about block says what this install actually is — the fact a support
     * question starts with. Compose state rather than a read through [graph], which is a plain field: a window
     * composed before [load] finished would otherwise show a blank id for ever, because nothing invalidates it when
     * the core opens.
     */
    var deviceId: String by mutableStateOf("")
        private set
    val launchAtLoginSupported: Boolean get() = ::launcher.isInitialized && launcher.supported
    val clientConfigured: Boolean get() = !OAuthConfig.isPlaceholder

    /**
     * Opens the core, then the executor. Everything the tray offers is off until this finishes.
     *
     * Both parameters are the machine's own answers and `Main` passes neither. They are here so a
     * test can open a whole shell — core, recorder, executor — over a temp directory and the fake
     * helper instead of the developer's own install, which is what `%LOCALAPPDATA%\Recly` and
     * `CaptureHelper.command()` otherwise resolve to (`ShellStartTest`). `AppModule.build` takes
     * its data directory for the same reason.
     */
    suspend fun load(
        dataDirectory: Path = Host.dataDir(),
        helperCommand: List<String>? = CaptureHelper.command(),
        microphoneAccess: (Logger) -> MicrophoneAccess = MicrophoneAccess::create,
    ) {
        val graph = runCatching {
            AppModule.build(dataDir = dataDirectory, localization = localization)
        }.getOrElse {
            status = Str.STATUS_CORE_ERROR.message()
            it.printStackTrace()
            return
        }
        this.graph = graph
        deviceId = graph.core.deps.device.deviceId
        val logger = graph.core.deps.logger
        balloon = TrayAlertBalloon(logger, localization::current)
        recordingBalloon = TrayRecordingBalloon(logger, localization::current)
        launcher = LaunchAtLogins.create(logger)
        launchAtLogin = launcher.isEnabled()
        consentReminder = settings.consentReminder
        microphone = microphoneAccess(logger)
        micAccess = microphone.state()
        chatGpt = ChatGptViewModel(graph.core.chatGpt, graph.core.summaries, graph.core.notes, graph.loopback, scope, logger).also { it.start() }
        graph.core.notes.setHeadings(notesHeadings(localization.current))

        val command = helperCommand
        this.helperCommand = command
        helperMissing = command == null
        // deliverable 3: the path check above only says a file is there; this is the one that says
        // it runs, and what it is.
        helperVersion = command?.let { withContext(graph.core.deps.io) { CaptureHelper.version(it) } }
        // docs/14 "Detection": before the recorder, because the recorder hands detection back and forth
        // with it (`Detection`) and there must never be two helpers alive at once.
        val detector = if (command == null) {
            null
        } else {
            MeetingDetector(
                helper = { HelperClient(command, graph.core.deps.io, logger) },
                apps = RunningApps.create(logger),
                notifier = MeetingNotifier.create(logger, localization::current),
                actions = DetectActions(),
                clock = graph.core.deps.clock,
                scope = scope,
                logger = logger,
            )
        }
        this.detector = detector

        recorder = WindowsRecorder(
            core = graph.core,
            scope = scope,
            helper = { command?.let { HelperClient(it, graph.core.deps.io, logger) } },
            onFinalized = { outcome ->
                // docs/03: the name is asked for after the recording has ended, and the job waits
                // for the answer. A quit does not ask, and neither does a recording the helper's death
                // ended: the user did not stop it, so it is queued untitled and the line says it failed.
                // A second recording finishing while the first is still unnamed does not take the
                // prompt from it: that one is queued as it stands rather than losing its job.
                if (askTitle && outcome.recordingId != untitledStop && !outcome.failed && titles.publish(outcome)) {
                    status = TITLE_PROMPT.message()
                } else {
                    complete(outcome, title = null)
                }
            },
            onState = { isRecording ->
                recording = isRecording
                // The popup's timer counts from here rather than from when the popup was opened — and,
                // while a stop saves, holds where it ended until the recorder is idle again.
                when {
                    isRecording -> {
                        recordingSince = System.currentTimeMillis()
                        recordingEndedAt = null
                    }
                    transition == Transition.STOPPING && recordingSince != null ->
                        recordingEndedAt = System.currentTimeMillis()
                    else -> {
                        recordingSince = null
                        recordingEndedAt = null
                    }
                }
                // `STARTING` is over the moment the capture is up. The other half — `STOPPING` —
                // outlives this callback, because a stop publishes it before it waits for the
                // trailing parts, and the node has to say so for the whole of that wait ([stop]).
                if (isRecording) transition = null
                // The other end of the gate [begin] raised: it comes down when the capture is over,
                // not when the start returned. Raised here too, so a capture this shell did not
                // open — a recovery, a helper that came back — is gated like any other.
                val capture = PlaybackGate.Reason.CAPTURE
                if (isRecording) playbackGate.raise(capture) else playbackGate.lower(capture)
            },
            detection = detector ?: NoDetection,
            // Said here as well as when the finalize queues it: a stop deferred to the next launch never gets there.
            onHelperDied = { status = Str.RECORDING_FAILED.message() },
            // docs/01 "Long recordings": the watchdog is the helper's to raise, in the language the app is in now.
            watchdog = {
                localization.current.let { HelperNotice(it[Str.NOTIFY_WATCHDOG_TITLE], it[Str.NOTIFY_WATCHDOG_BODY]) }
            },
            onNobodySpeaking = { recordingBalloon?.show(Str.NOTIFY_REMINDER_TITLE, Str.NOTIFY_REMINDER_BODY) },
            power = PowerSources.create(),
            // The stop that asks nothing, then the balloon that says why — as the phones and the Mac do.
            onBatteryLow = {
                stop(askTitle = false)
                recordingBalloon?.show(Str.NOTIFY_BATTERY_STOP, null)
            },
        )

        waveforms = WaveformPrecompute(
            scope = scope,
            worker = WAVEFORM_WORKER,
            load = graph.core.recordings::get,
            kept = graph.core.recordings::waveform,
            keep = graph.core.recordings::saveWaveform,
            spawn = RecordingPlayer.decoder(CaptureHelper.ffmpeg()),
            exists = graph.core.deps.fileSystem::exists,
            // A capture, or a delete or a disconnect removing files: the decode gives way to all three.
            busy = { playbackGate.blocked },
            logger = logger,
        )

        graph.core.initializeProcessing()
        readStorage(graph)
        val models = ModelDownload(
            scope = scope,
            read = graph.core::localEngineInfo,
            prepare = graph.core::prepareLocalEngine,
            // The helper reads the connection's cost; without one there is nothing to ask, and no question.
            networkCost = {
                command?.let { withContext(graph.core.deps.io) { CaptureHelper.networkCost(it) } } ?: NetworkCost.UNKNOWN
            },
            onPrepared = {
                runner?.jobsDue()
                processing?.refreshLocal()
            },
        )
        modelDownload = models
        processing = ProcessingViewModel(graph.core, scope, models, ::saveSettingsFile, ::openSettingsFile) { runner?.jobsDue() }

        // docs/03 "recoverable", before the tray can start anything: a recording the last run left open is
        // finished here, and one whose job never got made is queued — both before the first pass.
        val recovered = runCatching { RecordingRecovery(graph.core).reconcile() }
            .onFailure { logger.log(Logger.Level.ERROR, "rec.recovery.failed", error = it) }
            .getOrDefault(0)

        signedIn = graph.auth.isSignedIn()
        val runner = JobRunner(
            queue = CoreJobQueue(graph.core),
            scope = scope,
            logger = logger,
            onPass = { jobs -> adopt(jobs) },
        )
        this.runner = runner
        runner.start()
        // The ledger while a pass is still running. `onPass` only fires once `runDueJobs` has
        // returned, and the core claims a job `RUNNING` and carries the whole upload out inside that
        // one call — so a ledger fed by the pass alone goes straight from `PENDING` to `DONE` and
        // the State node says `IDLE` for the length of an upload. A job row moving is what moves the
        // badge and the node through `UPLOADING`, and the core writes that row as it happens.
        scope.launch { graph.core.jobs.observe().conflate().collect { refreshRecents() } }
        // docs/03: and the recordings themselves, because a pull adds and drops rows no job was
        // ever made for — the job table never moves for one of those.
        scope.launch { graph.core.recordings.observe().conflate().collect { refreshRecents() } }
        scope.launch { followFills(graph) }

        // Started last: the offer's whole point is the recording behind it, and an offer made
        // before the shell is ready is one that cannot be taken.
        detector?.start()

        ready = true
        status = when {
            helperMissing -> HELPER_MISSING
            micAccess == MicAccess.DENIED -> MIC_DENIED
            else -> Str.STATUS_WAITING
        }.message()
        logger.log(
            Logger.Level.INFO,
            "shell.ready",
            mapOf(
                "windows" to Host.isWindows,
                "helper" to (command != null),
                "helperVersion" to helperVersion,
                "micAccess" to micAccess.name,
                "signedIn" to signedIn,
                "recovered" to recovered,
            ),
        )
    }

    // --- recording ------------------------------------------------------------------------------

    /**
     * docs/12 M8 · ADR-011: a meeting recording captures the other side of the call (ADR-006:
     * `mic`/`sys`/`mix`), so the consent reminder is asked before the first one and the recording
     * waits for the answer. The Mac asks the same question with the same words ([Consent]), and
     * under the same condition: a microphone-only memo has no other participants to have told.
     *
     * Every desktop start uses meeting capture, including starts from notifications.
     */
    fun start() {
        val mode = RecordingMode.MEETING
        // Nor while one is still coming up or being saved: the record control is off for both (STARTING, STOPPING).
        if (recorder == null || titlePrompt != null || consentRequest != null || organizeConsentStart != null || transition != null) return
        // docs/03: the clean-up half of a disconnect walks the recording directory, so a capture
        // started inside it would be one its own delete pass is writing over. Say what is in the
        // way rather than starting something that is about to be taken away.
        DisconnectGate.startBlocker()?.let {
            status = it
            return
        }
        // docs/14 "Permissions": read again now rather than trusted from the launch — the switch is the user's to
        // flip at any time, and with it off the capture would be silence. Refused, with the way to turn it back on.
        micAccess = microphone.state()
        if (micAccess == MicAccess.DENIED) {
            status = MIC_DENIED.message()
            micPrompt = true
            return
        }
        // docs/08 "Organizing": signed in to ChatGPT and not agreed to organizing (signed in before it existed) — asked
        // here, before the reminder, and the recording starts whatever the answer.
        if (asksOrganizeConsent()) {
            organizeConsentStart = mode
            chatGpt?.askOrganizeConsent()
            return
        }
        remindThenBegin(mode)
    }

    /** docs/12 M8: the consent reminder in front of a meeting recording while it is on; then the capture. */
    private fun remindThenBegin(mode: RecordingMode) {
        if (mode.remindsConsent && consentReminder) {
            consentRequest = ConsentRequest(mode)
            return
        }
        begin(mode)
    }

    /**
     * docs/08 "Organizing": the organizing consent question's answer — Continue (true), Sign out (false), or closed
     * without either (null). A recording start that was waiting for it goes on, whatever the answer.
     */
    fun answerOrganizeConsent(agree: Boolean?) {
        chatGpt?.answerOrganizeConsent(agree)
        val mode = organizeConsentStart ?: return
        organizeConsentStart = null
        remindThenBegin(mode)
    }

    /**
     * The tray's stand-in for the notification's missing button (`TrayNotifier`). It goes back
     * through the detector with the offer's token, so a menu drawn before the meeting ended cannot
     * start a recording nobody is in.
     */
    fun startDetected() {
        detector?.act(meetingOfferToken)
    }

    /** The consent dialog's "yes". [dontAskAgain] is its own way into the setting (docs/12 M8). */
    fun consentConfirmed(dontAskAgain: Boolean) {
        val request = consentRequest ?: return
        consentRequest = null
        // A photograph of the question answers nothing: no capture, and not the setting either.
        if (!dialogMode.acts) return
        if (dontAskAgain) toggleConsentReminder(false)
        begin(request.mode)
    }

    /**
     * The consent reminder put on screen to be photographed (`--show-consent`), whatever the setting
     * says. Not [start]: that one is a recording that stops in front of the question, so with the
     * reminder switched off it opens a real capture and never raises the dialog at all.
     */
    fun previewConsent() {
        dialogMode = DialogMode.PREVIEW
        consentRequest = ConsentRequest(RecordingMode.MEETING)
    }

    /** `--show-delete`, for [item]: the same dialog, and a confirm that deletes nothing. */
    fun previewDelete(item: RecentItem) {
        dialogMode = DialogMode.PREVIEW
        askToDelete(item)
    }

    /** `--show-disconnect`: the same warning, and a confirm that revokes nothing. */
    fun previewDisconnect() {
        dialogMode = DialogMode.PREVIEW
        askToDisconnect()
    }

    /**
     * Cancel — the question has to mean something, so this is the end of it. [dontAskAgain] counts whichever way the
     * question was answered: the box is about the question, not about this one recording.
     */
    fun consentCancelled(dontAskAgain: Boolean) {
        consentRequest ?: return
        consentRequest = null
        if (dialogMode.acts && dontAskAgain) toggleConsentReminder(false)
    }

    /** The microphone dialog's Close. */
    fun closeMicPrompt() {
        micPrompt = false
    }

    /** Its Open Windows Settings: the page, and the dialog is done. */
    fun openMicPromptSettings() {
        micPrompt = false
        openMicrophoneSettings()
    }

    /** The settings window has come to the front: the switch may have been flipped while it was behind. */
    fun refreshMicrophone() {
        micAccess = microphone.state()
    }

    private fun begin(mode: RecordingMode) {
        val recorder = recorder ?: return
        // The tray menu is a snapshot: the recording that just ended may still be waiting for its
        // name, and a second one would take the prompt's place and lose the first one's job.
        if (titlePrompt != null) return
        // ADR-006: this PC's capture takes the speakers with it, so what is playing would be *in*
        // the recording. Up here, before anything is asked of the recorder — `onState(true)` is
        // published only after the helper's Start has gone out, so a gate that waited for it would
        // be going up over a capture that had already begun. It stays up until that capture ends.
        val capture = PlaybackGate.Reason.CAPTURE
        playbackGate.raise(capture)
        // docs/09 screen principle 1: `STARTING` for as long as the helper is coming up — from here, where
        // the start was asked for, to the recorder's own `onState` or the refusal below.
        transition = Transition.STARTING
        scope.launch {
            // Read in the `finally` below, which is the only place either of the two things this
            // start raised is put back down.
            var started = false
            try {
                status = Str.STATUS_OPENING.message()
                // And the speaker is off the parts before the Start, for the same reason: a stop the
                // window drives off the gate is a frame away, and the helper is not.
                stopPlayback()
                // docs/03: the gate is held across the last look at it *and* the start itself. The
                // stop above suspends, so the check [start] made before it says nothing about the
                // moment the capture actually opens — a disconnect that took the gate inside that
                // wait would be walking the recording directory while this capture wrote into it. A
                // start that finds the gate held is refused rather than queued behind the disconnect.
                status = DisconnectGate.ifOpen {
                    // The recording that ended a moment ago may have published its prompt while the
                    // playback was being stopped; the prompt is published under this same lock.
                    val id = titles.ifIdle { recorder.start(mode = mode) }
                    started = id != null
                    // What the recording that is running is made of, for as long as it runs — the
                    // detector reads it to tell a meeting's end from a memo's silence.
                    if (started) captureMode = mode
                    when {
                        id != null -> Str.STATUS_RECORDING
                        titlePrompt != null -> TITLE_PROMPT
                        else -> Str.RECORDING_FAILED
                    }.message()
                }
                    // A gate held by the disconnect says so; one held by another start that is
                    // already opening is the same answer the recorder itself would have given this.
                    ?: DisconnectGate.startBlocker() ?: Str.RECORDING_FAILED.message()
            } catch (e: Exception) {
                // A start that threw failed as much as one that was refused; the throw still goes on to the scope.
                status = Str.RECORDING_FAILED.message()
                throw e
            } finally {
                // A capture that never opened has nothing to keep the gate up for; one that did
                // keeps it until `onState(false)`, and `STARTING` with it.
                //
                // In a `finally` because the start is not only refused, it can *throw*: the row and
                // the directory are written before the helper is asked for anything, and a disk or
                // database failure there would otherwise leave this shell saying `STARTING` for the
                // rest of the process with playback blocked and no capture running.
                if (!started) {
                    playbackGate.lower(capture)
                    transition = null
                }
            }
        }
    }

    /**
     * [askTitle] false is the stop that asks nothing (docs/01 "Long recordings — battery stop"): the recording is
     * queued untitled, as a quit's is, and can be named from the list later.
     */
    fun stop(askTitle: Boolean = true) {
        val recorder = recorder ?: return
        if (!askTitle) untitledStop = recorder.recordingId
        // docs/09 screen principle 1: `STOPPING` until the last segment is closed and the parts are filed —
        // the recorder publishes `onState(false)` at the top of that, and the wait is the rest of it.
        transition = Transition.STOPPING
        scope.launch {
            // Everything the stop does is inside the `try`, so that every way out of it — a second
            // stop the recorder refuses, a helper that had to be killed on the timeout, a database
            // failure in the finalize — puts `STOPPING` back down. A node stuck on it would be this
            // shell saying it is closing a recording that ended minutes ago.
            try {
                status = Str.STATUS_SAVING.message()
                // docs/03: a stop that could not file every part finalizes nothing — the recovery
                // pass at the next launch does, once the marked parts are in the meta.
                val result = recorder.stop()
                if (result is StopResult.Deferred) status = Str.STATUS_DEFERRED.message()
            } finally {
                transition = null
                // Idle again: the held length goes with `STOPPING`.
                if (!recording) {
                    recordingSince = null
                    recordingEndedAt = null
                }
            }
        }
    }

    /** Quit: a recording in flight is finalized and queued first — the crash path is not the exit. */
    suspend fun shutdown() {
        askTitle = false
        detector?.stop()
        // A quit with the dialog still open is a skip: that recording is already finalized and
        // `stop` has nothing left to do for it, so without this its job would never be made.
        titles.take()?.let { outcome -> complete(outcome, title = null) }
        recorder?.stop()
    }

    /** Saving a title is the only answer that queues this recording. */
    fun saveTitle(title: String, participants: Int? = null) = answerTitle(title, participants)

    fun cancelTitle() {
        val graph = graph ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The same one-shot claim as Save: only one answer can own this recording.
            val outcome = titles.take() ?: return@launch
            val discarded = playbackGate.cleaning(::stopPlayback) {
                if (detail?.recordingId == outcome.recordingId) detail = null
                status = runCatching {
                    graph.core.recordings.delete(outcome.recordingId, deleteDrive = false)
                }.fold(
                    onSuccess = { result ->
                        when (result) {
                            // The user asked for it to go, so its going is not news: back to idle.
                            is DeleteResult.Deleted, DeleteResult.NotFound -> Str.STATUS_WAITING.message()
                            DeleteResult.Busy -> Str.RECORDING_DISCARD_FAILED.message()
                        }
                    },
                    onFailure = { error ->
                        graph.core.deps.logger.log(Logger.Level.ERROR, "rec.delete.failed", error = error)
                        Str.RECORDING_DISCARD_FAILED.message()
                    },
                )
                refreshRecents()
                true
            }
            if (discarded == null) status = Str.RECORDING_DISCARD_FAILED.message()
        }
    }

    private fun answerTitle(title: String?, participants: Int?) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val outcome = titles.take() ?: return@launch
            complete(outcome, title, participants)
        }
    }

    /**
     * docs/03: name it, then queue it — never the other way round (see [completeRecording]). The line then says what
     * the queue made of it ([completionLine]).
     */
    private suspend fun complete(outcome: RecordingOutcome, title: String?, participants: Int? = null) {
        val graph = graph ?: return
        val completion = runCatching { completeRecording(graph.core, outcome.recordingId, title, participants) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "rec.enqueue.failed", error = it) }
            .getOrNull()
        // Kept, so its shape is worked out now, and the first open draws it at once.
        waveforms?.enqueue(outcome.recordingId)
        // docs/12/14 "Runner" (a): the job exists now, so a pass runs immediately rather than
        // waiting for the five-minute timer.
        runner?.jobsDue()
        status = completionLine(outcome.failed, completion).message()
    }

    // --- executor (docs/14 "Runner") --------------------------------------------------------------

    private fun adopt(jobs: List<Job>) {
        needsAuth = jobs.any { it.status == JobStatus.NEEDS_AUTH }
        if (needsAuth && !isStopReport(status)) status = NEEDS_AUTH_NOTICE.message()
        scope.launch {
            refreshRecents()
            refreshAlerts(jobs)
        }
    }

    /**
     * docs/10 rule 3: the queue is the source of truth, so the banner is folded from **every** job
     * it is carrying rather than from the five recordings the ledger shows — a job blocked on a
     * missing key three recordings ago is still blocked, and folding the ledger would take its line
     * down the moment somebody recorded five more things.
     *
     * A step read that fails takes the whole reading with it rather than folding that job as "no
     * reason": every reason a `FAILED` job carries is read off its steps, so a job whose rows could
     * not be read would fold to *no reason at all* — indistinguishable from one that has come
     * unstuck — and the banner would empty while the job was still stuck. Failing leaves the last
     * reading standing, and the next pass reads the queue again.
     */
    private suspend fun refreshAlerts(jobs: List<Job>) {
        val graph = graph ?: return
        val sources = runCatching {
            jobs.map { job -> alertSource(job.status, job.workflowId, graph.core.jobs.steps(job.id)) }
        }.getOrElse {
            graph.core.deps.logger.log(Logger.Level.ERROR, "shell.alerts.failed", error = it)
            return
        }
        val folded = foldAlerts(sources)
        alerts = folded
        balloon?.let { JobAlertBalloons.publish(it, folded) }
    }

    /**
     * docs/10: "A tap goes to the screen that can fix it — it does not end at 'Open app'." Five
     * surfaces, and the only one that leaves the app is the storage page, because the space is
     * Google's to give back.
     */
    fun fix(alert: JobAlert) = when (alert.reason.fix) {
        FixSurface.SIGN_IN -> signIn()
        FixSurface.DRIVE_STORAGE -> open(DRIVE_STORAGE_URL)
        // The banner is over every waiting recording, so it downloads in the saved settings' language.
        FixSurface.MODEL_DOWNLOAD -> downloadModel()
        // The key and the transcription settings are both in the settings window's processing panel.
        FixSurface.SECRETS, FixSurface.EDITOR -> { settingsOpen = true }
    }

    private suspend fun refreshRecents() {
        val graph = graph ?: return
        runCatching { Recents.load(graph.core, recentsLimit) }
            .onSuccess { recents = it }
            .onFailure {
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.recents.failed", error = it)
            }
        recentsLoading = false
        // A job that moved is where a transcript arrives, so the rows' first words are read again with it.
        refreshPreviews()
    }

    /** [previews] for the rows the ledger has read, through the core's search cache. */
    private suspend fun refreshPreviews() {
        val graph = graph ?: return
        val ids = recents.map { it.id }
        runCatching { graph.core.previews(ids) }
            .onSuccess { previews = it }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.previews.failed", error = it) }
    }

    /**
     * docs/09 screen principle 2 / docs/12 "Menu bar": the last loaded row has been scrolled onto, so the
     * ledger reads the next [Recents.PAGE].
     *
     * A reading that came back short of what it asked for is the end of the recordings, and there is
     * nothing older to ask for — the window is not grown, so the row that fired this can fire it
     * again without walking the limit off past the rows that exist.
     */
    fun loadMoreRecents() {
        if (recents.size < recentsLimit || loadingMoreRecents) return
        loadingMoreRecents = true
        scope.launch {
            recentsLimit += Recents.PAGE
            try {
                refreshRecents()
            } finally {
                loadingMoreRecents = false
            }
        }
    }

    /**
     * docs/03 "Recordings from other devices": what other devices have uploaded since, for a list that has just
     * come on screen — the job pass does this too, but on a throttle a user who opened the window to
     * look for something should not have to wait out.
     *
     * Fire and forget on the core's io: nothing on screen waits for the network, and the rows the
     * pull writes arrive through the `recording` table like any other change. It never throws.
     */
    fun pullRemote() {
        val graph = graph ?: return
        scope.launch(graph.core.deps.io) { graph.core.pullRemoteRecordings(force = true) }
    }

    /**
     * Retry: the job the row failed on is made due now. Only a failure offers it (docs/09). A row waiting for the Drive
     * connection is connected instead ([retriesBySigningIn]); a retry the queue refuses says so, and the button does not
     * wear a check for it.
     */
    fun retry(item: RecentItem) {
        if (retriesBySigningIn(item.jobStatus)) {
            signIn()
            return
        }
        val graph = graph ?: return
        val jobId = item.jobId ?: return
        listMessage = null
        scope.launch {
            runTracked {
                val retried = graph.core.jobs.retry(jobId)
                if (retried) {
                    runner?.jobsDue()
                } else {
                    sayAboutList(Str.RECENT_RETRY_UNAVAILABLE.message(), BadgeTone.WARNING)
                }
                retried
            }
        }
    }

    /** docs/10 "Drive out of space": Google's own storage page — the space is Google's to give back. */
    fun openDriveStorage() = open(DRIVE_STORAGE_URL)

    /** D24: More → Open in Drive, or Open the folder, for the recording the detail is showing. */
    fun openDetailStorage() {
        val detail = detail ?: return
        detail.driveUrl?.let {
            open(it)
            return
        }
        val root = localFolder ?: return
        detail.folderPath?.let { open(File(root, it).path) }
    }

    /**
     * docs/08 "Result files": the local copy if the step ran on this PC, and Drive's if it ran
     * elsewhere — the core decides which, and keeps what it downloads. The audio beside it is read
     * the same way: what is on this PC first, and Drive for what the sweep took ([fetchFromDrive]).
     */
    suspend fun followDetailResults(recordingId: String) = coroutineScope {
        val graph = graph ?: return@coroutineScope
        launch {
            graph.core.observeResults(recordingId).collect { result ->
                updateDetail(recordingId) { it.copy(transcript = result.transcript, availability = result.availability) }
                // A transcript read from Drive for this detail is now one this PC holds: its row says its first words.
                if (result.transcript != null) refreshPreviews()
            }
        }
        launch {
            graph.core.summaries.observeAsk(recordingId).collect { ask -> updateDetail(recordingId) { it.copy(ask = ask) } }
        }
        launch {
            graph.core.notes.observe(recordingId).collect { notes ->
                updateDetail(recordingId) {
                    // The last note deleted, with nothing being made and no failure to say: back to the transcript
                    // rather than a view with nothing in it.
                    val emptied = notes.isEmpty && it.notes?.isEmpty == false && !notes.organizing && notes.failure == null
                    it.copy(notes = notes, showingNotes = it.showingNotes && !emptied)
                }
            }
        }
        graph.core.recordings.observeAudio(recordingId).collectLatest { record ->
            val local = record?.let { rec ->
                RecordingPlaylist.select(rec.meta.parts, rec.dir) { graph.core.deps.fileSystem.exists(it) }
            } ?: RecordingPlaylist.Selection.EMPTY
            updateDetail(recordingId) {
                it.copy(audio = local, writing = record?.meta?.status == RecordingStatus.RECORDING,
                    driveFetch = DriveFetch.DECIDING, folder = record?.storage == StorageKind.FOLDER,
                    storage = record?.storage, lengthSec = record?.meta?.durationSec,
                    driveUrl = record?.driveFolderUrl, folderPath = record?.localFolderPath)
            }
            fetchFromDrive(graph, recordingId, record, local)
        }
    }

    /**
     * What the detail's menus and lines depend on besides the transcript: the highlights, which the audio
     * observation does not follow, whether a job of this recording — a re-transcription among them —
     * is still in flight, and its list row ([RecordingDetail.row]).
     */
    suspend fun followDetailState(recordingId: String) = coroutineScope {
        val graph = graph ?: return@coroutineScope
        launch {
            graph.core.recordings.observe()
                .map { graph.core.recordings.get(recordingId)?.meta?.highlights?.map { it.atSec }.orEmpty() }
                .distinctUntilChanged()
                .collect { highlights -> updateDetail(recordingId) { it.copy(highlights = highlights) } }
        }
        // More → Delete: the recording's row read on its own rather than found in [recents], whose pages a search hit
        // can open past — again whenever a recording or a job changes, as the list's rows are.
        launch {
            merge(graph.core.recordings.observe(), graph.core.jobs.observe().map { })
                .conflate()
                .collect {
                    val row = runCatching { Recents.one(graph.core, recordingId) }.getOrElse { return@collect }
                    updateDetail(recordingId) { it.copy(row = row) }
                }
        }
        graph.core.jobs.observe()
            .map { jobs ->
                jobs.filter { job ->
                    // docs/08 "Organizing": a job with only its notes left is in the way of nothing (StepReport.notesOnly).
                    job.recordingId == recordingId && job.status !in SETTLED &&
                        !runCatching { StepReport.notesOnly(job.workflow, graph.core.jobs.steps(job.id)) }.getOrDefault(false)
                }
            }
            .distinctUntilChanged()
            .collect { open ->
                val again = open.firstOrNull { it.retranscription }
                val localRunning = graph.core.localTranscription.isRunning(recordingId)
                val running = open.isNotEmpty() || localRunning
                // Asked again as its jobs change: a job that settles is where an upload has landed or not.
                val uploaded = runCatching { graph.core.uploaded(recordingId) }.getOrDefault(true)
                val transcribingNow = localRunning || open.any { job ->
                    transcriptionInFlight(job, runCatching { graph.core.jobs.steps(job.id) }.getOrDefault(emptyList()))
                }
                val waitsForDrive = open.any { it.status == JobStatus.NEEDS_AUTH }
                updateDetail(recordingId) {
                    it.copy(
                        transcribing = running,
                        retranscribing = again != null,
                        retranscribingLocal = again?.workflow?.steps?.any { step -> step is recly.core.model.Step.LocalTranscribe } == true,
                        notUploaded = !uploaded,
                        waitsForDrive = waitsForDrive,
                        busyReason = busyReason(transcribingNow, waitsForDrive, uploaded),
                    )
                }
            }
    }

    // --- the detail's tools (docs/08 "Editing" · "Exports", docs/10 "Re-transcription" · "Search") ----------

    /**
     * The whole list at once, saved as it is changed — no Save button (docs/03 "Metadata"). Shown as the core
     * will keep it: a mark within a second of another, or past the limit, is not drawn for a moment first.
     */
    fun setHighlights(recordingId: String, atSecs: List<Double>) {
        val graph = graph ?: return
        updateDetail(recordingId) { it.copy(highlights = Highlight.normalize(atSecs).map { mark -> mark.atSec }) }
        scope.launch(graph.core.deps.io) { graph.core.setHighlights(recordingId, atSecs) }
    }

    /**
     * docs/03 "Metadata": the moment the recording that is running has reached, marked from the tray. The clock is the
     * recorder's written audio ([WindowsRecorder.writtenSec]), so the mark is where that moment is in the file.
     */
    fun highlightNow() {
        val graph = graph ?: return
        if (!recording) return
        val recordingId = recorder?.recordingId ?: return
        // The written audio's clock, not the wall's: the mark is where that moment is in the file.
        val atSec = recorder?.writtenSec() ?: return
        scope.launch {
            if (!graph.core.recordings.addHighlight(recordingId, atSec)) return@launch
            // For two seconds, then the line is the recording's again.
            val marked = Str.HIGHLIGHT_MARKED.message(LedgerFormat.clock((atSec * 1000).toLong()))
            status = marked
            delay(HIGHLIGHT_LINE_MS)
            if (status == marked && recording) status = Str.STATUS_RECORDING.message()
        }
    }

    /** docs/08 "Editing": an edit saved here at once and carried to the recording's folder by the core. */
    suspend fun editTranscript(recordingId: String, edit: TranscriptEdit): EditResult {
        val graph = graph ?: return EditResult.NoTranscript
        return runCatching { graph.core.editTranscript(recordingId, edit) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "rec.transcript.edit.failed", error = it) }
            .getOrDefault(EditResult.Invalid("failed"))
            // The row's first words follow the edit.
            .also { if (it is EditResult.Edited) refreshPreviews() }
    }

    /** docs/08 "Exports": one file of the recording, made by the core under a name for people; null when there is nothing. */
    suspend fun exportFile(recordingId: String, format: ExportFormat): File? {
        val graph = graph ?: return null
        return runCatching { graph.core.exportFile(recordingId, format) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "rec.export.failed", error = it) }
            .getOrNull()?.let(::File)
    }

    /** That file, saved where the user says — the save dialog opens on the core's name for it. */
    suspend fun saveExport(file: File) {
        val target = fileDialog(FileDialog.SAVE, file.name) ?: return
        runCatching { withContext(Dispatchers.IO) { file.copyTo(target, overwrite = true) } }
            .onFailure { graph?.core?.deps?.logger?.log(Logger.Level.ERROR, "rec.export.save.failed", error = it) }
    }

    /** docs/10 "Search": titles and the transcripts on this PC. */
    suspend fun search(query: String): List<SearchHit> {
        val graph = graph ?: return emptyList()
        return runCatching { graph.core.search(query, SEARCH_LIMIT) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.search.failed", error = it) }
            .getOrDefault(emptyList())
    }

    /**
     * A search result: the detail opens on its transcript hit with every match of [query] tinted — or, for a
     * hit in the title alone, as it opens from the list, with nothing to find; or, found in its notes alone, on
     * the notes ([opensOnNotes]).
     */
    fun openSearchHit(hit: SearchHit, query: String) {
        val title = hit.title?.takeIf { it.isNotBlank() }?.let(UiMessage::Text) ?: Str.UNTITLED.message()
        val first = hit.snippets.firstOrNull()
        openDetail(hit.recordingId, title, find = query.takeIf { first != null }, findAtSec = first?.atSec, showingNotes = opensOnNotes(hit))
    }

    /** More → Transcribe again: the confirmation, worded from the settings as they are now. */
    fun askToRetranscribe() {
        val detail = detail ?: return
        val summary = processing?.summary ?: return
        val transcript = detail.transcript
        retranscribeRequest = RetranscribeRequest(
            recordingId = detail.recordingId,
            provider = summary.external?.provider?.takeIf { summary.mode == recly.core.processing.TranscriptionMode.EXTERNAL }
                ?.let(recly.core.transcribe.SttProviders::displayName),
            language = summary.language,
            replacesEdits = transcript != null && (transcript.editedAt != null || transcript.speakers.any { !it.name.isNullOrBlank() }),
        )
    }

    fun cancelRetranscribe() {
        retranscribeRequest = null
    }

    fun retranscribe(request: RetranscribeRequest) {
        val graph = graph ?: return
        retranscribeRequest = null
        scope.launch {
            val result = runCatching { graph.core.retranscribe(request.recordingId) }
                .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "rec.retranscribe.failed", error = it) }
                .getOrNull()
            val notice = when (result) {
                is RetranscribeResult.Started -> {
                    runner?.jobsDue()
                    null
                }
                // The same reason More gives for it.
                RetranscribeResult.Busy -> detail?.takeIf { it.recordingId == request.recordingId }?.busyReason ?: Str.DETAIL_TRANSCRIBING
                RetranscribeResult.NoAudio -> Str.PLAYER_NO_AUDIO
                RetranscribeResult.NoTranscriptionConfigured -> Str.DETAIL_TRANSCRIPTION_OFF
                RetranscribeResult.Unsupported -> Str.DETAIL_NOT_UPLOADED
                null -> null
            } ?: return@launch
            updateDetail(request.recordingId) { it.copy(notice = notice) }
            delay(NOTICE_MS)
            updateDetail(request.recordingId) { if (it.notice == notice) it.copy(notice = null) else it }
        }
    }

    /**
     * docs/08 "Titles and speaker names": what is being filled in, into [fills]. A title a fill gave the open recording
     * — from its More menu or its publication — shows in the header, which does not follow the recording: it is read
     * again once the run that asked for it is over.
     */
    private suspend fun followFills(graph: AppGraph) {
        var before = emptyList<FillRun>()
        graph.core.observeFills().collect { now ->
            fills = now
            val open = detail?.recordingId
            if (open != null && before.any { it.recordingId == open && it.title } && now.none { it.recordingId == open && it.title }) {
                runCatching { graph.core.recordings.get(open)?.meta?.title }.getOrNull()?.takeIf { it.isNotBlank() }?.let { named ->
                    updateDetail(open) { it.copy(title = UiMessage.Text(named)) }
                }
            }
            before = now
        }
    }

    /**
     * docs/08 "Titles and speaker names": More → Fill in title or Fill in speaker names. The run is the core's and goes
     * on if the window closes; while it asks, [fills] has it, the names arrive through the transcript the detail
     * follows, and the title through the header's read once the run is over ([followFills]). A refusal or a failure is
     * said under the header for a moment, as a refused Transcribe again is.
     */
    fun fill(half: FillHalf) {
        val graph = graph ?: return
        val recordingId = detail?.recordingId ?: return
        scope.launch {
            val result = try {
                when (half) {
                    FillHalf.TITLE -> graph.core.fillTitle(recordingId)
                    FillHalf.NAMES -> graph.core.fillNames(recordingId)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.chatgpt.fill.failed", mapOf("recordingId" to recordingId), e)
                null
            }
            val busy = detail?.takeIf { it.recordingId == recordingId }?.busyReason ?: Str.DETAIL_TRANSCRIBING
            when (val notice = fillNotice(result, half, busy)) {
                is FillNotice.Refused -> {
                    updateDetail(recordingId) { it.copy(notice = notice.reason) }
                    delay(NOTICE_MS)
                    updateDetail(recordingId) { if (it.notice == notice.reason) it.copy(notice = null) else it }
                }
                is FillNotice.Failed -> {
                    updateDetail(recordingId) { it.copy(fillFailure = notice) }
                    delay(NOTICE_MS)
                    updateDetail(recordingId) { if (it.fillFailure === notice) it.copy(fillFailure = null) else it }
                }
                null -> Unit
            }
        }
    }

    /**
     * More → Organize and Organize again, and Retry under a failure (docs/09 "AI view"): asks nothing — what the user
     * edited or deleted stays — and goes to the notes at once. It runs on the app's own scope, so closing the window
     * does not stop it, and the detail follows the notes through [followDetailResults]: `Organizing…` while it runs,
     * the failure after one. Notes that came to nothing go back to the transcript, as the last note deleted does.
     */
    fun organize() {
        val graph = graph ?: return
        val recordingId = detail?.recordingId ?: return
        showNotes(true)
        scope.launch {
            try {
                graph.core.notes.organizeAgain(recordingId)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.notes.organize.failed", error = e)
            }
            val now = runCatching { graph.core.notes.get(recordingId) }.getOrNull()
            if (now == null || (now.isEmpty && now.failure == null && !now.organizing)) {
                updateDetail(recordingId) { it.copy(showingNotes = false) }
            }
        }
    }

    /** More → Ask about this recording (docs/08 "Ask"). */
    fun openAsk() {
        askingAbout = detail?.recordingId
    }

    /** Closing the panel forgets its answer; a question still running finishes, and reopening shows it. */
    fun closeAsk() {
        val recordingId = askingAbout ?: return
        askingAbout = null
        graph?.core?.summaries?.clearAsk(recordingId)
    }

    /** The presets the Ask panel offers for [recordingId]: Translate and Feedback only where they make sense. */
    suspend fun askPresets(recordingId: String): List<AskPreset> {
        val graph = graph ?: return emptyList()
        return runCatching { graph.core.summaries.askPresets(recordingId) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.chatgpt.ask.failed", error = it) }
            .getOrDefault(emptyList())
    }

    /** A preset, or the user's own [question], about the recording the panel is open over; the detail follows the answer. */
    fun ask(preset: AskPreset?, question: String?) {
        val graph = graph ?: return
        val recordingId = askingAbout ?: return
        scope.launch {
            try {
                graph.core.summaries.ask(recordingId, preset, question)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.chatgpt.ask.failed", error = e)
            }
        }
    }

    /**
     * Edit notes' Save (docs/09 "AI view"): the overview when it changed, and the items whose words did — an emptied one
     * is deleted. The core keeps them as the user's from then on and carries them to the recording's folder. False when
     * they could not be saved, and the editor stays.
     */
    suspend fun editNotes(recordingId: String, overview: String?, items: List<NoteEdit>): Boolean {
        val graph = graph ?: return false
        return runCatching { graph.core.notes.edit(recordingId, overview, items) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.notes.edit.failed", error = it) }
            .isSuccess
    }

    /** Copy all under the notes: the overview and every group with its heading, as text; null when it cannot be read. */
    suspend fun notesText(recordingId: String): String? {
        val graph = graph ?: return null
        return runCatching { graph.core.notes.text(recordingId) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.notes.copy.failed", error = it) }
            .getOrNull()
    }

    /** The Transcript | Notes chips. */
    fun showNotes(show: Boolean) {
        val recordingId = detail?.recordingId ?: return
        updateDetail(recordingId) { it.copy(showingNotes = show) }
    }

    /** docs/08 "Organizing": the transcript's lines around a note, for its row to open on; none when they cannot be read. */
    suspend fun noteLines(recordingId: String, atSec: Double): List<NoteLine> {
        val graph = graph ?: return emptyList()
        return runCatching { graph.core.notes.linesAround(recordingId, atSec) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.notes.lines.failed", error = it) }
            .getOrDefault(emptyList())
    }

    /** A note's Delete: gone for good, without asking (docs/08 "Organizing"); the detail follows the core's notes. */
    fun deleteNote(recordingId: String, itemId: String) {
        val graph = graph ?: return
        scope.launch {
            runCatching { graph.core.notes.deleteItem(recordingId, itemId) }
                .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.notes.delete.failed", error = it) }
        }
    }

    fun changePlaybackSpeed(speed: Float) {
        settings.playbackSpeed = speed
        playbackSpeed = speed
    }

    fun toggleSkipSilence(enabled: Boolean) {
        settings.skipSilence = enabled
        skipSilence = enabled
    }

    fun reloadDetailResults() {
        val id = detail?.recordingId ?: return
        scope.launch {
            val result = try {
                graph?.core?.retryResults(id) ?: return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                recly.core.transcribe.RecordingResult(availability = TranscriptAvailability.UNAVAILABLE)
            }
            updateDetail(id) { it.copy(transcript = result.transcript, availability = result.availability) }
        }
    }

    fun openDetail(item: RecentItem) = openDetail(item.id, item.title)

    private fun openDetail(
        recordingId: String,
        title: UiMessage,
        find: String? = null,
        findAtSec: Double? = null,
        showingNotes: Boolean = false,
    ) {
        val graph = graph ?: return
        detail = RecordingDetail(recordingId, title, find = find, findAtSec = findAtSec, showingNotes = showingNotes)
        scope.launch {
            val result = runCatching { graph.core.results(recordingId) }
                .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.detail.failed", error = it) }
                .getOrNull()
            // The user may have picked another recording while Drive was answering.
            if (detail?.recordingId != recordingId) return@launch
            // Out of `loading` before the fetch, because the player bar is where the fetch is said —
            // and the bar stays on `DECIDING` until the trip has been decided, so the seconds it
            // spends asking Drive are not seconds in which Play is offered.
            detail = RecordingDetail(
                recordingId = recordingId,
                title = title,
                loading = false,
                transcript = result?.transcript,
                availability = result?.availability ?: TranscriptAvailability.UNAVAILABLE,
                find = find,
                findAtSec = findAtSec,
                showingNotes = showingNotes,
            )
        }
    }

    /**
     * docs/03 ADR-017: the parts the retention sweep took, fetched back from Drive so the detail can
     * play the recording it is about. A take still being written to is left alone — it has nothing
     * whole to play yet, and nothing of it has reached Drive either.
     *
     * A failure is a sentence in the player bar and nothing more. What a missing token needs is a
     * sign-in, and the settings window already carries one — a dialog from here would be a second
     * way to say what is already on screen.
     */
    private suspend fun fetchFromDrive(
        graph: AppGraph,
        recordingId: String,
        record: RecordingRecord?,
        local: RecordingPlaylist.Selection,
    ) {
        if (record == null || record.meta.status == RecordingStatus.RECORDING) {
            updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.IDLE) }
            return
        }
        val track = RecordingPlaylist.playedParts(record.meta.parts)
        val uploaded = runCatching { graph.core.uploaded(recordingId) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.detail.uploaded.failed", error = it) }
            .getOrDefault(false)
        if (!RecordingPlaylist.fetchesFromDrive(local.paths.size, track.size, uploaded)) {
            updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.IDLE) }
            return
        }
        updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.FETCHING, fetchProgress = 0f) }
        runCatching {
            graph.core.audio(recordingId) { done, total ->
                updateDetail(recordingId) { it.copy(fetchProgress = if (total > 0) done.toFloat() / total else 0f) }
            }
        }.fold(
            onSuccess = { fetched ->
                val audio = RecordingPlaylist.fetched(track, fetched.paths.map { it.name }, record.dir)
                updateDetail(recordingId) {
                    // A part that stayed missing is a gap the playlist stops at, so the trip did not
                    // bring the recording back whole — the same sentence as a trip that failed.
                    it.copy(
                        audio = audio,
                        driveFetch = if (fetched.missing.isEmpty()) DriveFetch.IDLE else DriveFetch.FAILED,
                    )
                }
            },
            onFailure = { error ->
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.detail.audio.failed", error = error)
                updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.FAILED) }
            },
        )
    }

    /** Only while the window is still showing that recording: another pick has a load of its own. */
    private fun updateDetail(recordingId: String, change: (RecordingDetail) -> RecordingDetail) {
        val current = detail ?: return
        if (current.recordingId != recordingId) return
        detail = change(current)
    }

    // --- renaming a recording (docs/03) -----------------------------------------------------

    /**
     * Opens the rename dialog on whatever the detail is showing. The field starts on the name the
     * user gave it and empty when nobody has: [Str.UNTITLED] is this shell's word for "no name"
     * rather than a name, and prefilling it would offer it back as one.
     */
    fun askToRename() {
        val detail = detail ?: return
        renameRequest = RenameRequest(
            recordingId = detail.recordingId,
            title = (detail.title as? UiMessage.Text)?.value.orEmpty(),
        )
    }

    fun cancelRename() {
        renameRequest = null
    }

    /**
     * docs/03: the core writes the new name locally at once and pushes it to Drive, and the ledger
     * catches up on its own through `recordings.observe()`. Only the open detail's header has to be
     * told, because its title is a snapshot of the row rather than a read of it. A blank field is
     * the timestamp name back, which is what `null` means to the core.
     */
    fun rename(request: RenameRequest, title: String) {
        val graph = graph ?: return
        renameRequest = null
        val trimmed = title.trim()
        scope.launch {
            val renamed = withContext(graph.core.deps.io) {
                runCatching { graph.core.rename(request.recordingId, trimmed.ifBlank { null }) }
                    .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "rec.rename.failed", error = it) }
                    .getOrDefault(false)
            }
            if (!renamed) return@launch
            val name = trimmed.takeIf { it.isNotBlank() }?.let(UiMessage::Text) ?: Str.UNTITLED.message()
            updateDetail(request.recordingId) { it.copy(title = name) }
        }
    }

    // --- deleting a recording (docs/03 "Deleting in the app") -------------------------------------------

    /**
     * Opens the confirmation. The count is read first because the dialog has to state it: what is
     * still only on this PC is the part of the deletion nothing anywhere else can give back.
     */
    fun askToDelete(item: RecentItem) {
        val graph = graph ?: return
        scope.launch {
            deleteRequest = DeleteRequest(
                recordingId = item.id,
                title = item.title,
                unuploaded = Retention.unuploadedParts(graph.core, item.id),
                remote = item.remote,
                // Another device's recording was read out of Drive; the local folder is never listed.
                folder = !item.remote && Retention.storageOf(graph.core, item.id) == StorageKind.FOLDER,
                hasDriveFolder = item.link != null || item.localFolderPath != null,
            )
        }
    }

    fun cancelDelete() {
        deleteRequest = null
    }

    /**
     * docs/03: the local files and rows always, the Drive folder only when that is what was chosen.
     * A Drive refusal does not undo the local half — there is nothing left to retry with — so it is
     * reported rather than thrown away, and a `RUNNING` job means nothing was deleted at all.
     */
    fun delete(request: DeleteRequest, deleteDrive: Boolean) {
        // A dialog raised to be photographed deletes nothing; closing it is the whole of its confirm.
        if (!dialogMode.acts) {
            deleteRequest = null
            return
        }
        val graph = graph ?: return
        deleteRequest = null
        listMessage = null
        scope.launch {
            // Before the files go, not after, and with Play off the bar for the whole of it: the
            // recording being deleted is the one the detail is showing, and the detail is what plays.
            val deleted = playbackGate.cleaning(::stopPlayback) {
                // And the detail goes with them: a recordings window left open on the recording that
                // is about to stop existing is a detail with nothing behind it (the Mac clears the
                // same one — `MenuModel.delete`). Inside the gate, so a decoder that outlived the
                // bound leaves the detail alone along with the files.
                if (detail?.recordingId == request.recordingId) detail = null
                // D9: a delete that did what was asked is not announced — the row going is the answer.
                runCatching { graph.core.recordings.delete(request.recordingId, deleteDrive) }
                    .fold(
                        onSuccess = { result ->
                            when (result) {
                                is DeleteResult.Deleted -> result.driveError?.let { refused ->
                                    // docs/03 "Storage location": the other half was the local folder's.
                                    val half = if (request.folder) Str.DELETE_FOLDER_FAILED else Str.DELETE_DRIVE_FAILED
                                    ShellNotice(half.message(refused), BadgeTone.DANGER)
                                }

                                DeleteResult.Busy -> ShellNotice(Str.DELETE_BUSY.message(), BadgeTone.WARNING)
                                // Already gone — by a previous attempt whose row this window had not
                                // caught up with.
                                DeleteResult.NotFound -> null
                            }
                        },
                        onFailure = { error ->
                            graph.core.deps.logger.log(Logger.Level.ERROR, "rec.delete.failed", error = error)
                            val reason = error.message ?: error::class.simpleName.orEmpty()
                            ShellNotice(Str.CORE_STEP_FAILED.message(reason), BadgeTone.DANGER)
                        },
                    )
                    ?.let { sayAboutList(it.message, it.tone) }
                refreshRecents()
                runner?.jobsDue()
                true
            }
            // Nothing was deleted: the decoder outlived the bound and the row is still here to try
            // again with, which is the whole point of not having deleted it.
            if (deleted == null) sayAboutList(Str.PLAYER_STOP_FAILED.message(), BadgeTone.WARNING)
        }
    }

    // --- disconnecting (docs/03 "Sign out vs Disconnect" · docs/06) ---------------------------------

    /**
     * Opens the docs/03 warning. The count is read first because the dialog has to state it: a user
     * about to lose the queue deserves to know what is still only on this PC.
     */
    fun askToDisconnect() {
        if (disconnecting) return
        val graph = graph ?: return
        scope.launch {
            disconnectPrompt = DisconnectPrompt(
                unuploaded = Retention.unuploadedRecordings(graph.core),
                recording = recording,
            )
        }
    }

    fun cancelDisconnect() {
        disconnectPrompt = null
    }

    /**
     * docs/03 "Disconnect", both halves and in this order: the Google grant, which is what makes the
     * other devices lose access too, and then the core's local clean-up (tokens, the queue, the
     * folder cache). A revoke that failed does not cancel the local half — the
     * user asked for this PC to be done with the account — but it is what the message talks about,
     * because the grant is then still standing and only they can take it down.
     */
    fun disconnect(alsoDeleteRecordings: Boolean) {
        // Photographed, this warning revokes nothing: it is the developer's own grant behind it.
        if (!dialogMode.acts) {
            disconnectPrompt = null
            return
        }
        val graph = graph ?: return
        // The second half of a double-press must not catch the re-presented prompt below and
        // confirm a warning nobody has read: from the first activation until its re-read decides,
        // every further activation is a no-op.
        val shown = disconnectPrompt ?: return
        if (disconnecting) return
        disconnecting = true
        disconnectPrompt = null
        driveNote = null
        scope.launch {
            try {
                // The dialog may have stood while a recording finished or an upload failed — every
                // window is its own surface. What it promised is read again before it is acted on; a
                // warning it never showed re-asks instead of destroying quietly.
                val fresh = DisconnectPrompt(
                    unuploaded = Retention.unuploadedRecordings(graph.core),
                    recording = recording,
                )
                if (fresh.warnsMore(shown, alsoDeleteRecordings)) {
                    disconnectPrompt = fresh
                    return@launch
                }
                disconnectPrompt = null
                // Shut for the whole of it, before anything is read: the revoke below is a network round
                // trip, and a start inside that wait would give the clean-up a directory that is being
                // written into.
                //
                // The other half of that: a disconnect that takes the recordings with it deletes what
                // the detail may be playing. The speaker is off it before the revoke rather than at the
                // clean-up, and Play stays off the bar for the whole round trip — a press landing inside
                // it would hand ffmpeg a part the clean-up is about to remove ([PlaybackGate.cleaning]).
                val ran = playbackGate.cleaning({ !alsoDeleteRecordings || stopPlayback() }) {
                    runTracked { DisconnectGate.hold { runDisconnect(graph, alsoDeleteRecordings) } }
                    true
                }
                if (ran == null) sayAboutDrive(Str.PLAYER_STOP_FAILED.message())
            } finally {
                disconnecting = false
            }
        }
    }

    /** Published before work starts and held through revocation and local cleanup. */
    var disconnecting: Boolean by mutableStateOf(false)
        private set

    private suspend fun runDisconnect(graph: AppGraph, alsoDeleteRecordings: Boolean): Boolean {
        // The recorder is read again here and not only when the warning opened: the dialog may have
        // stood while something started a capture, and the gate cannot refuse one already running.
        DisconnectGuard.liveBlocker(recording)?.let {
            sayAboutDrive(it)
            return false
        }
        var revoked: RevokeResult? = null
        // A retry of a disconnect whose *local* half failed has no grant left to take away — the
        // tokens went when the revoke succeeded — so it goes straight to the clean-up rather than
        // revoking a grant this disconnect was never about.
        if (DisconnectGuard.revokes(disconnectPhase, signedIn)) {
            // The phase is on disk before the revoke, not after it: both of the branches below
            // delete this PC's credentials (`revokeAccess` clears the tokens on its way out, and
            // `signOut` clears them when it failed), and a phase written afterwards is one that can
            // be lost with them.
            val attempt = DisconnectGuard.revoking({ phase -> persistPhase(phase) }) {
                // A revoke that failed still leaves this PC disconnected — that is what the dialog
                // promised — so the tokens go either way; only the grant is still standing. The
                // debt says so, and it is written *before* `signOut` takes the token: with the
                // token gone the retry has no way to tell a revoke that happened from one that
                // never did, and would report success over a grant Google is still listing.
                DisconnectGuard.owingDebt(
                    owe = { owed -> persistRevokeDebt(owed) },
                    revoke = { graph.auth.revokeAccess() },
                    forgetTokens = { graph.auth.signOut() },
                )
            }
            if (attempt == null) {
                // The store would not commit what this disconnect is about to do, so it did not do
                // it: nothing was revoked, no credential was deleted and the PC is exactly as it
                // was — including the sign-in, which is why this returns before it is cleared.
                sayAboutDrive(DisconnectGuard.saveFailed())
                graph.core.deps.logger.log(Logger.Level.ERROR, "auth.disconnect.refused.store")
                return false
            }
            revoked = attempt
        }
        signedIn = false
        // Once more, now the revoke has had its wait. The phase is written first so the retry row is
        // on screen for it — the grant is already gone. A disconnect that had nothing of its own to
        // revoke has not written it yet, and one that did is writing what is already there.
        DisconnectGuard.liveBlocker(recording)?.let { racing ->
            // A phase that would not commit is the graver of the two: the credentials are already
            // gone and this row is what is left to finish the job with.
            val owed = persistPhase(DisconnectPhase.REVOKED_CLEANUP_OWED)
            sayAboutDrive(if (owed) racing else DisconnectGuard.saveFailed())
            return false
        }
        // Caught separately from the revoke, because the two fail for different reasons and only one
        // of them is the user's to fix from here: the tokens, the queue and the folder cache are
        // still on this PC, and another Disconnect is what removes them.
        var result: DisconnectResult? = null
        var cleanupFailure: String? = null
        try {
            val done = DisconnectGuard.owingCleanup({ phase -> persistPhase(phase) }) {
                graph.core.disconnect(alsoDeleteRecordings)
            }
            if (done == null) {
                // null is the store refusing the phase rather than a clean-up that found nothing:
                // none of it was tried, so the tokens and the queue are all still here and there is
                // nothing to report but the store.
                sayAboutDrive(DisconnectGuard.saveFailed())
                graph.core.deps.logger.log(Logger.Level.ERROR, "auth.disconnect.refused.store")
                return false
            }
            result = done
        } catch (e: Exception) {
            cleanupFailure = e.message ?: e::class.simpleName.orEmpty()
            graph.core.deps.logger.log(Logger.Level.ERROR, "auth.disconnect.cleanup.failed", error = e)
        }
        // Never a plain "disconnected" over an owed debt — including the restart path, where the
        // revoke was skipped and there is no [RevokeResult] here to say the grant is still up.
        sayAboutDrive(DisconnectGuard.completion(revoked, cleanupFailure, result, revokeDebt))
        // The queue is gone with everything else, so nothing is blocked on anything any more.
        needsAuth = false
        alerts = emptyList()
        refreshRecents()
        // A clean-up that had to keep a busy recording is still owed (the phase says so), and the
        // button must not say done over it.
        return revoked !is RevokeResult.Failed && cleanupFailure == null &&
            result?.busyRecordings.isNullOrEmpty()
    }

    /** A disconnect's outcome: the tray's line, and under the Drive row in Settings in its tone ([disconnectNote]). */
    private fun sayAboutDrive(message: UiMessage) {
        status = message
        driveNote = disconnectNote(message)
    }

    /**
     * The store and the screen together, for the two settings a disconnect writes through
     * synchronously: they outlive the process, the state draws them. A transition that has already
     * been made is not written — the store's write is flushed, which is not free.
     *
     * @param write the store first and then the state, in that order: the state is left alone with
     *   the store, and the two say the same thing, which is what the retry reads.
     * @return whether it is on disk. The flush is what commits a `java.util.prefs` write, and a
     *   [BackingStoreException] from it means what this disconnect is about to act on would not
     *   survive the process — so nothing that deletes a credential may run on it
     *   ([DisconnectGuard.revoking]).
     */
    private fun <T> persistFlushed(value: T, held: T, event: String, write: (T) -> Unit): Boolean {
        if (held == value) return true
        return try {
            write(value)
            true
        } catch (e: BackingStoreException) {
            graph?.core?.deps?.logger?.log(Logger.Level.ERROR, event, error = e)
            false
        }
    }

    private fun persistPhase(phase: DisconnectPhase): Boolean =
        persistFlushed(phase, disconnectPhase, "auth.disconnect.phase.failed") {
            settings.disconnectPhase = it
            disconnectPhase = it
        }

    private fun persistRevokeDebt(owed: Boolean): Boolean =
        persistFlushed(owed, revokeDebt, "auth.disconnect.debt.failed") {
            settings.revokeDebt = it
            revokeDebt = it
        }

    /**
     * docs/03: the debt is the only half of a disconnect this app cannot finish for the user, so it
     * is the only one they close themselves — the row stays until they say they took the grant down
     * at Google, or until a later revoke manages it. A store that would not take the answer says so
     * rather than dropping it: the row is still there on the next launch either way.
     */
    fun revokeDebtSettled() {
        if (!persistRevokeDebt(false)) sayAboutDrive(DisconnectGuard.saveFailed())
    }

    /** The consent dialog's link (docs/12 M8) — a browser, so it does not dismiss the question. */
    fun openConsentGuidance() = open(Consent.LINK)

    /** docs/03: the page that takes this app's access away by hand, when the revoke could not. */
    fun openAccountPermissions() = open(GOOGLE_PERMISSIONS_URL)

    /**
     * docs/14 "Permissions": there is no prompt to answer, so a microphone that is switched off for desktop
     * apps is only ever undone in Windows Settings — and this is the page rather than a description
     * of where it is. The Mac deep-links its own pane for exactly the same reason
     * (`MenuModel.presentMicrophoneDenied`); on the development host the scheme is nobody's and the
     * open is a no-op, like every other Windows path here.
     */
    fun openMicrophoneSettings() = open(MICROPHONE_SETTINGS_URL)

    // --- sign-in (docs/06) ----------------------------------------------------------------------

    fun signIn() {
        val graph = graph ?: return
        // docs/06: the account slot belongs to the disconnect until it has finished both halves —
        // a second identity signed in over an owed clean-up owns keys the first one left behind.
        DisconnectGuard.signInBlocker(disconnectPhase.owed)?.let {
            status = it.message()
            return
        }
        scope.launch {
            action = ProcessingState.PROCESSING
            status = Str.STATUS_SIGNING_IN.message()
            driveNote = null
            // Fails the button rather than leaving it "…" if the post-auth unpark throws; the
            // throwable carries on to the scope as before.
            try {
                when (val result = graph.auth.signIn()) {
                    SignInResult.Ok -> {
                        signedIn = true
                        status = Str.STATUS_WAITING.message()
                        unpark()
                        settle(ProcessingState.DONE)
                    }

                    SignInResult.NoClient -> {
                        status = Str.STATUS_NO_CLIENT.message()
                        settle(ProcessingState.FAILED)
                    }

                    is SignInResult.Failed -> {
                        // The reason is Google's or the loopback's own words, and goes to the log (`auth.*`); the
                        // user is told that Drive was not connected, under the row that connects it (D21).
                        val failed = Str.DRIVE_CONNECT_FAILED.message()
                        driveNote = ShellNotice(failed, BadgeTone.DANGER)
                        status = failed
                        settle(ProcessingState.FAILED)
                    }
                }
            } catch (e: Throwable) {
                settle(ProcessingState.FAILED)
                throw e
            }
        }
    }

    fun signOut() {
        // docs/06: the account slot belongs to the disconnect until it has finished, and this is the
        // same rule [signIn] keeps. A plain sign-out after REVOKE_PENDING would delete the refresh
        // token the retry reads to tell "revoke again" from "already revoked", and the grant would
        // be left standing with no debt written. Asked after the graph: with no core open there is
        // no sign-out to refuse, and the status line is saying why the core is not open.
        val graph = graph ?: return
        DisconnectGuard.signInBlocker(disconnectPhase.owed)?.let {
            status = it.message()
            return
        }
        scope.launch {
            graph.auth.signOut()
            signedIn = false
        }
    }

    /** docs/06: a job parked in NEEDS_AUTH resumes when the user signs in. */
    private suspend fun unpark() {
        val graph = graph ?: return
        withContext(graph.core.deps.io) { graph.core.pullRemoteRecordings(force = true) }
        try {
            graph.core.reconnectDrive()
        } finally {
            runner?.jobsDue()
        }
        needsAuth = false
    }

    // --- importing (docs/03 "Naming rules") -----------------------------------------------------

    /** Why the last import left nothing behind, for the recordings window's notice; the next import clears it. */
    var importFailure: UiMessage? by mutableStateOf(null)
        private set

    /** Imports one after another, however many drops and picks arrive at once. */
    private val importing = kotlinx.coroutines.sync.Mutex()

    /** The tray's and the list header's Import audio…: the picker, then [importFiles]. */
    fun chooseImport() {
        if (graph == null) return
        scope.launch { importFiles(filesDialog()) }
    }

    /**
     * Files picked or dropped: each becomes a recording of this PC's, titled after its name, its row in the
     * list from the start (`IMPORTING`) and its job queued once it is whole. The file's own time is its
     * start — when it was created where the file system says, else when it was last written ([importStartMillis]). Nothing during a disconnect's clean-up, which walks the directory an import writes into.
     */
    fun importFiles(files: List<File>) {
        val graph = graph ?: return
        if (files.isEmpty()) return
        importFailure = null
        scope.launch {
            importing.withLock {
                for (file in files) {
                    DisconnectGate.startBlocker()?.let {
                        status = it
                        return@withLock
                    }
                    val result = runCatching {
                        val started = importStartMillis(createdMillis(file), file.lastModified())
                        graph.core.importAudio(file.path, file.name, Instant.fromEpochMilliseconds(started), FfmpegImporter(graph.core.deps.io))
                    }.getOrElse {
                        graph.core.deps.logger.log(Logger.Level.ERROR, "rec.import.failed", error = it)
                        ImportResult.Failed(recly.core.message.CoreMessage.IMPORT_UNREADABLE.code(detail = it.message))
                    }
                    when (result) {
                        is ImportResult.Imported -> {
                            waveforms?.enqueue(result.recordingId)
                            runner?.jobsDue()
                        }
                        is ImportResult.Failed -> {
                            importFailure = coreMessage(result.reason)
                            status = Str.IMPORT_FAILED.message()
                        }
                    }
                }
            }
        }
    }

    /** The file's creation time, or null where it cannot be read. */
    private fun createdMillis(file: File): Long? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrNull()

    // --- the speech model (docs/05) ------------------------------------------------------------

    /** Not while a capture is running or coming up: the download is for later, the recording is now. */
    val modelDownloadAllowed: Boolean get() = !recording && transition == null

    /** The first-run card at the top of the popup ([showsModelCard]). */
    val modelCardShown: Boolean
        get() = showsModelCard(
            mode = processing?.summary?.mode,
            installed = processing?.localInstalled == true,
            status = modelDownload?.info?.status,
            dismissed = modelPromptDismissed,
            capturing = !modelDownloadAllowed,
            waiting = alerts.any { it.reason == AlertReason.LOCAL_MODEL_REQUIRED },
        )

    /** A waiting row's own language, or — from the banner and the card — the saved settings' one. */
    fun downloadModel(language: String? = null) {
        if (modelDownloadAllowed) modelDownload?.start(language)
    }

    /** The card's "Not now": this PC does not offer it again, and Settings still has the download. */
    fun dismissModelPrompt() {
        settings.modelPromptDismissed = true
        modelPromptDismissed = true
    }

    // --- settings -------------------------------------------------------------------------------

    fun toggleLaunchAtLogin(enabled: Boolean) {
        launchAtLogin = launcher.set(enabled)
    }

    fun toggleConsentReminder(enabled: Boolean) {
        settings.consentReminder = enabled
        consentReminder = enabled
    }

    /**
     * docs/07 rule 3: the tray menu, the open windows and the next notification are all in the new
     * language the moment this returns — the tables behind them are one [Localization] state.
     */
    fun selectLanguage(choice: AppLanguage) {
        localization.language = choice
        language = choice
        // Notes, Ask's presets and its Translate follow the app's language without a restart (2026-10-10) —
        // the same tag the core was opened with ([AppGraph]) — and so do the headings of the notes as text.
        graph?.core?.summaries?.setLocale(choice.tag.ifEmpty { Host.language() })
        graph?.core?.notes?.setHeadings(notesHeadings(localization.current))
    }

    fun selectTheme(choice: AppTheme) {
        settings.theme = choice
        theme = choice
    }

    // --- storage (docs/03 "Storage location") ---------------------------------------------------

    /**
     * A chip, saved the moment it is tapped — as the next revision of the processing settings, which
     * an open processing form then takes under its draft ([ProcessingViewModel.storageChanged]). The
     * choice is read back rather than assumed, so the chips say what the core holds.
     */
    fun selectStorage(kind: StorageKind) {
        val graph = graph ?: return
        // Taken here, on the thread the tap arrived on: two chips tapped in quick succession would
        // otherwise race each other's read-back and leave the wrong one marked.
        if (kind == storage || storageBusy) return
        storageBusy = true
        storageMessage = null
        scope.launch {
            try {
                storageMessage = when (val result = graph.core.processingSettings.setStorage(kind)) {
                    is ProcessingSaveResult.Saved -> {
                        processing?.storageChanged()
                        null
                    }
                    is ProcessingSaveResult.Invalid -> coreMessage(recly.core.message.CoreMessage.STEP_FAILED, result.errors.joinToString("\n"))
                    // The phone's mapping (`SettingsViewModel.selectStorage`): anything else is settings this build cannot read.
                    else -> Str.PROCESSING_UNREADABLE.message()
                }
                readStorage(graph)
            } catch (e: Exception) {
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.storage.failed", error = e)
                storageMessage = coreMessage(recly.core.message.CoreMessage.STEP_FAILED, e.message.orEmpty())
            } finally {
                storageBusy = false
            }
        }
    }

    /** The settings window has come to the front: the choice, and whether the folder is still there. */
    fun refreshStorage() {
        val graph = graph ?: return
        scope.launch { readStorage(graph) }
    }

    /** The folder row's button: the picker, then [useLocalFolder] on what was chosen. */
    fun chooseLocalFolder() {
        if (graph == null) return
        scope.launch {
            val chosen = folderDialog(localFolder) ?: return@launch
            useLocalFolder(chosen.absolutePath)
        }
    }

    /**
     * The folder the user picked: kept on this PC (never in the exported settings), and the uploads
     * that were waiting for one let go now rather than at their next look, five minutes out. Apart
     * from the picker so a test can pick without a dialog.
     */
    internal suspend fun useLocalFolder(path: String) {
        val graph = graph ?: return
        // In the store before anything is let go: the core reads the folder from it on every call,
        // so the jobs released below must find it there.
        settings.localFolder = path
        localFolder = path
        localFolderAvailable = graph.core.deps.localFolder?.available() == true
        try {
            graph.core.resumeFolderWaits()
        } finally {
            // docs/14 "Runner": the pass runs now, not at the timer the waits were parked on.
            runner?.jobsDue()
        }
    }

    private suspend fun readStorage(graph: AppGraph) {
        runCatching { graph.core.processingSettings.storage() }
            .onSuccess { storage = it }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.storage.failed", error = it) }
        localFolderAvailable = graph.core.deps.localFolder?.available() == true
    }

    /** About's Privacy Policy: the canonical page the phones link (recly.dev), in Korean for the Korean app. */
    fun openPrivacyPolicy(language: String) = open(privacyPolicyUrl(language))

    /**
     * docs/05: the file itself. AWT's own dialog rather than Swing's chooser — it is the Windows
     * shell dialog, which is the one a user of this app has seen before, and this app already draws
     * everything else itself. It is modal and runs its own event pump, so it is called off the UI
     * thread's coroutine like every other blocking call here.
     *
     * False when the user closed it without choosing: that asked for nothing and reports nothing.
     */
    private suspend fun saveSettingsFile(name: String, contents: String): Boolean {
        val chosen = fileDialog(FileDialog.SAVE, name) ?: return false
        withContext(Dispatchers.IO) { chosen.writeText(contents) }
        return true
    }

    /** Null both when the user closed the dialog and when the file would not open — see above. */
    private suspend fun openSettingsFile(): String? {
        val chosen = fileDialog(FileDialog.LOAD, null) ?: return null
        return withContext(Dispatchers.IO) {
            if (chosen.length() > SETTINGS_FILE_MAX_BYTES) throw SettingsFileTooLarge()
            chosen.readText()
        }
    }

    /** The import picker: any number of files, of any type — ffmpeg is what decides whether one has audio. */
    private suspend fun filesDialog(): List<File> = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, APP_NAME, FileDialog.LOAD).apply { isMultipleMode = true }
        dialog.isVisible = true
        dialog.files.toList()
    }

    private suspend fun fileDialog(mode: Int, name: String?): File? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, APP_NAME, mode)
        name?.let { dialog.file = it }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory ?: "", it) }
    }

    /**
     * docs/03 "Storage location": a folder rather than a file. AWT's dialog cannot pick a directory on
     * Windows, so this is Swing's chooser held to directories, run the way [fileDialog] runs its own:
     * modal, on the UI thread's dispatcher. It opens on [current] when there is one. Null when the user
     * closed it without choosing.
     */
    private suspend fun folderDialog(current: String?): File? = withContext(Dispatchers.Main) {
        val chooser = JFileChooser(current).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = APP_NAME
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    /**
     * Runs [block] with [action] following it, and [settle]s on whether it did what the user asked.
     * Whatever it threw carries on to the scope exactly as it did before — this only makes sure the
     * button is not left saying "…" for ever.
     */
    private suspend fun runTracked(block: suspend () -> Boolean) {
        action = ProcessingState.PROCESSING
        try {
            settle(if (block()) ProcessingState.DONE else ProcessingState.FAILED)
        } catch (e: Throwable) {
            settle(ProcessingState.FAILED)
            throw e
        }
    }

    private fun open(target: String) {
        runCatching {
            // A URL and a `ms-settings:` deep link are both for whoever owns the scheme; only a
            // path is a folder for the file manager.
            if (target.startsWith("http") || target.startsWith(SETTINGS_SCHEME)) {
                Desktop.getDesktop().browse(URI(target))
            } else {
                Desktop.getDesktop().open(File(target))
            }
        }
    }

    /**
     * A recording the consent question is standing in front of (docs/12 M8). It carries the mode it
     * was asked about: the answer starts *that* recording, and the chips may have moved while the
     * dialog stood.
     */
    data class ConsentRequest(val mode: RecordingMode)

    /**
     * What the detector is allowed to do to this shell. Deliberately four narrow calls rather than
     * the model itself: the rule and the routing are tested against fakes, and this is the whole of
     * what they can reach.
     */
    private inner class DetectActions : MeetingDetector.Actions {
        /**
         * A reading, and only a reading: it decides whether an offer is worth making at all. What
         * makes a detection-driven start safe against a disconnect is that [start] is the one below,
         * so the capture opens inside [DisconnectGate.ifOpen] like every other start on this PC.
         */
        override fun canStart(): Boolean =
            ready && !helperMissing && titlePrompt == null && consentRequest == null && organizeConsentStart == null &&
                // A microphone-only recording is not one [isRecording] reports, so this is what
                // keeps the offer off a PC that is already recording (the Mac's `isIdle` guard).
                !recording && !DisconnectGate.busy

        /**
         * Whether a **meeting** is in flight, which is what the rule's `Signals.isRecording` is
         * about: docs/12 "End detection" is about this recording, and a microphone-only memo's own idle
         * microphone is not a meeting that has ended (the Mac's `recordingChanged(mode == .meeting)`).
         */
        override fun isRecording(): Boolean = recording && captureMode.detectsEnd

        /** ADR-011: a meeting Recly found by hearing it is a meeting, whatever the chips say. */
        override fun start() = this@ShellModel.start()

        override fun stop() = this@ShellModel.stop()

        override fun offered(prompt: MeetingDetectionRule.Prompt?, token: Long) {
            meetingOffer = prompt
            meetingOfferToken = token
        }
    }

    companion object {
        /** docs/14 deliverable 5: the same words Settings says (2026-10-10). */
        val HELPER_MISSING = Str.HELPER_UNAVAILABLE
        val MIC_DENIED = Str.STATUS_MIC_DENIED
        val NEEDS_AUTH_NOTICE = Str.STATUS_SIGN_IN_NEEDED
        val TITLE_PROMPT = Str.STATUS_NAMING

        /** The job states that are over: anything else may still write the transcript. */
        private val SETTLED = setOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.SKIPPED_SHORT)

        /** How long the tray's line says a highlight was marked. */
        private const val HIGHLIGHT_LINE_MS = 2_000L
        /** How long the detail says why Transcribe again did not start. */
        private const val NOTICE_MS = 4_000L

        /** docs/10 "Search": the most recordings one search lists. */
        private const val SEARCH_LIMIT = 50

        /** docs/03: Google's own page, which is the only place a failed revoke can be finished. */
        const val GOOGLE_PERMISSIONS_URL = "https://myaccount.google.com/permissions"

        /** docs/14 "Permissions": Settings → Privacy → Microphone, the page and not directions to it. */
        const val MICROPHONE_SETTINGS_URL = "ms-settings:privacy-microphone"

        /** Windows' own settings scheme — a deep link, not a path (see `open`). */
        private const val SETTINGS_SCHEME = "ms-settings:"

        /**
         * The background waveform decode's one thread: at the lowest priority, so it never competes
         * with the capture, the upload or a window for the CPU.
         */
        private val WAVEFORM_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "recly-waveform-precompute").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()
    }
}
