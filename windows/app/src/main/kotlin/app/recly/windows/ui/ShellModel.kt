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
import app.recly.windows.core.Host
import app.recly.windows.detect.MeetingDetectionRule
import app.recly.windows.detect.MeetingDetector
import app.recly.windows.detect.MeetingNotifier
import app.recly.windows.detect.MicAccess
import app.recly.windows.detect.MicrophoneAccess
import app.recly.windows.detect.NoDetection
import app.recly.windows.detect.RunningApps
import app.recly.windows.helper.CaptureHelper
import app.recly.windows.helper.HelperClient
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Localization
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.APP_NAME
import app.recly.windows.jobs.CoreJobQueue
import app.recly.windows.jobs.JobRunner
import app.recly.windows.jobs.RecentItem
import app.recly.windows.jobs.Recents
import app.recly.windows.record.RecordingOutcome
import app.recly.windows.record.RecordingRecovery
import app.recly.windows.record.StopResult
import app.recly.windows.record.WindowsRecorder
import app.recly.windows.record.completeRecording
import app.recly.windows.settings.AppTheme
import app.recly.windows.settings.LaunchAtLogin
import app.recly.windows.settings.LaunchAtLogins
import app.recly.windows.settings.RecordingMode
import app.recly.windows.settings.Settings
import app.recly.windows.ui.theme.Motion
import app.recly.windows.ui.theme.ProcessingState
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.prefs.BackingStoreException
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import recly.core.DisconnectResult
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.model.RecordingStatus
import recly.core.model.WorkflowsDocument
import recly.core.platform.Logger
import recly.core.recording.DeleteResult
import recly.core.recording.RecordingRecord
import recly.core.sync.WorkflowRepository
import recly.core.sync.WorkflowSummary
import recly.core.transcribe.Transcript
import recly.core.workflow.WorkflowDocuments

/**
 * docs/09 화면 원칙 1: the two state codes that are a move rather than a state — the Mac's
 * `RecorderState.starting` and `.stopping`, which is where its node reads them from.
 */
enum class Transition { STARTING, STOPPING }

/** What the detail window shows for one recording (docs/08 "결과 파일"). */
data class RecordingDetail(
    val recordingId: String,
    /** A name rather than a sentence: the window is open while the language can change under it. */
    val title: UiMessage,
    val loading: Boolean = true,
    val transcript: Transcript? = null,
    /** docs/08 "결과 파일": the audio beside the transcript, when this PC still has it. */
    val audio: RecordingPlaylist.Selection = RecordingPlaylist.Selection.EMPTY,
    /** A take still being written to has nothing whole to play yet, so the detail offers nothing. */
    val writing: Boolean = false,
    /** docs/03 ADR-017: how the trip to Drive for the parts the retention sweep took is going. */
    val driveFetch: DriveFetch = DriveFetch.DECIDING,
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
 * docs/03: the recording the rename dialog is asking about, and the name the field opened on. Plain
 * text rather than a [UiMessage], because it is what the user is editing rather than what the app
 * is saying.
 */
data class RenameRequest(val recordingId: String, val title: String)

/**
 * Everything the tray shows and everything its items do — the Mac's `MenuModel` (docs/12 "메뉴바"),
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
     * docs/09 화면 원칙 1: the two codes between `IDLE` and `REC` — a capture whose helper is coming
     * up, and one whose last segment is being closed. Null the rest of the time, when the state the
     * node says is a state rather than a move between two of them.
     *
     * Its own field and not [action]: that one is the *button's* window (docs/09 트렌드 2) and every
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
     * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the whole queue's blocked jobs, folded one entry
     * per reason. The popup's banner draws it, the tray icon wears it, and [JobAlertBalloons] turns
     * a *change* in it into a balloon — never a reading that said the same thing as the last one.
     */
    var alerts: List<JobAlert> by mutableStateOf(emptyList())
        private set
    var signedIn: Boolean by mutableStateOf(false)
        private set
    /** Every workflow the document has — ADR-016 left nothing in one that could exclude a PC. */
    var workflows: List<WorkflowSummary> by mutableStateOf(emptyList())
        private set
    /**
     * ADR-016: the workflow every recording on this PC runs — this PC's own pointer, resolved
     * against [workflows] and refreshed with it. The popup's picker shows it as the one that is
     * chosen, and picking another one is what moves the pointer ([selectWorkflow]). Null when this
     * PC has no pointer — or has one the document no longer resolves, which is the same thing to
     * say and the same thing to do about it.
     */
    var selectedWorkflow: WorkflowSummary? by mutableStateOf(null)
        private set
    var recents: List<RecentItem> by mutableStateOf(emptyList())
        private set
    /**
     * docs/03: a recording that has just ended and is waiting for its name. The window the tray
     * shows for it is the only thing standing between the stop and the job (see [completeRecording]),
     * and [TitleGate] is what keeps a new start from walking past one.
     */
    private val titles = TitleGate()

    val titlePrompt: RecordingOutcome? get() = titles.pending

    /**
     * docs/09 화면 원칙 6: the tray's own window. The AWT menu can only carry words, so everything
     * with a shape — the state nodes, the ledger, the workflow picker — lives in a Compose popup
     * and this is whether it is up.
     */
    var popupOpen: Boolean by mutableStateOf(false)
    var editorOpen: Boolean by mutableStateOf(false)

    /** docs/08 결과 파일: the window that shows what the transcribe step wrote. */
    var recordingsOpen: Boolean by mutableStateOf(false)

    /** The recording that window is showing, once one has been picked. */
    var detail: RecordingDetail? by mutableStateOf(null)
        private set
    var settingsOpen: Boolean by mutableStateOf(false)
    var launchAtLogin: Boolean by mutableStateOf(false)
        private set

    /** docs/03 "앱에서 지우기": the recording the delete dialog is asking about, while it is up. */
    var deleteRequest: DeleteRequest? by mutableStateOf(null)
        private set

    /** docs/03: the recording the rename dialog is asking about, while it is up. */
    var renameRequest: RenameRequest? by mutableStateOf(null)
        private set

    /** docs/03 "연결 해제": the warning dialog, and the counts it has to name, while it is up. */
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
     * docs/09 트렌드 2: where the last high-risk action the user asked for is — a sign-in, an upload,
     * an export. Only what the button draws depends on it (`ProcessingButton`); nothing about what
     * any of those actions *do* does. The Mac's `MenuModel.action` is the same field.
     */
    var action: ProcessingState by mutableStateOf(ProcessingState.IDLE)
        private set

    /**
     * Puts [state] up and takes it down again once the button has had its window (docs/09 "모션").
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
     * docs/09 화면 원칙 6: the levels of the recording that is running, for the popup's strip —
     * empty when there is none. A function rather than state: the recorder's ring changes ten times
     * a second on the helper's reader, and the strip is the one thing that wants to know.
     */
    fun livePeaks(): List<Float> = recorder?.livePeaks() ?: emptyList()

    // --- detection (docs/14 "감지", ADR-011) -----------------------------------------------------

    /** docs/12 M8: asked once before the first meeting recording. On until the user turns it off. */
    var consentReminder: Boolean by mutableStateOf(true)
        private set

    /**
     * docs/14 "캡처": what the next recording on this PC is made of — the microphone alone or the
     * whole meeting. The settings window's chips are where it moves, and the Mac's popover has the
     * same pair (`MenuModel.mode`). Read at construction like the theme, because the settings
     * window can be up before [load] has finished.
     */
    var recordingMode: RecordingMode by mutableStateOf(settings.recordingMode)
        private set

    /**
     * The mode the recording that is *running* was started with. Not [recordingMode]: a recording
     * the detection started is a meeting whatever the chips say (`MenuModel.start(mode:)`), and the
     * end-of-meeting offer is about this recording rather than about the next one.
     */
    private var captureMode: RecordingMode = RecordingMode.MEETING

    /**
     * A recording that is waiting for the consent question to be answered ([Consent]). Non-null is
     * the dialog the tray shows, and nothing is recorded until it is answered.
     */
    var consentRequest: ConsentRequest? by mutableStateOf(null)
        private set

    /**
     * The detection offer that is outstanding. An AWT balloon has no buttons (`TrayNotifier`), so
     * the tray carries the offer as a menu item for as long as it stands.
     */
    var meetingOffer: MeetingDetectionRule.Prompt? by mutableStateOf(null)
        private set

    /** Which offer that is. The tray item carries it back so a stale click does nothing. */
    private var meetingOfferToken: Long = MeetingDetector.NO_OFFER

    /** docs/14 "권한": desktop apps and the microphone. [MicAccess.DENIED] is a recording of silence. */
    var micAccess: MicAccess by mutableStateOf(MicAccess.UNKNOWN)
        private set

    /** deliverable 3: what the bundled helper says it is, or null when it would not run. */
    var helperVersion: String? by mutableStateOf(null)
        private set

    /** The helper died under a running recording; the recording was finalized and a restart offered. */
    var helperCrashed: Boolean by mutableStateOf(false)
        private set

    /** The last `--self-test` report, shown in the settings window. */
    var selfTest: UiMessage? by mutableStateOf(null)
        private set

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

    var workflowsModel: WorkflowsModel? by mutableStateOf(null)
        private set

    /**
     * Whether the recording that is ending gets to be named. False only on the way out: the user
     * asked to quit, and a dialog that keeps the app alive to ask for a title is the opposite of
     * that (the Mac's `MenuModel.finish(askingForTitle:)` draws the same line).
     */
    @Volatile private var askTitle: Boolean = true

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
     */
    private suspend fun stopPlayback(): Boolean {
        val player = player ?: return true
        val stopped = withContext(Dispatchers.IO) { player.stop() }
        if (!stopped) graph?.core?.deps?.logger?.log(Logger.Level.ERROR, "shell.play.stop.timeout")
        return stopped
    }

    private var runner: JobRunner? = null
    private var recorder: WindowsRecorder? = null
    private var detector: MeetingDetector? = null
    private var helperCommand: List<String>? = null
    private var balloon: AlertBalloon? = null
    private lateinit var launcher: LaunchAtLogin

    /** The same store [localization] keeps the language in — one `java.util.prefs` node, docs/14. */
    private val settings: Settings get() = localization.settings

    /**
     * Where the data is and which install this is — the two facts a support question starts with.
     * Compose state rather than a read through [graph], which is a plain field: a window composed
     * before [load] finished would otherwise show a blank path for ever, because nothing invalidates
     * it when the core opens.
     */
    var dataDir: String by mutableStateOf("")
        private set

    /** docs/09 트렌드 6: the settings window's about block says what this install actually is. */
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
     * [dataDir] for the same reason.
     */
    suspend fun load(
        dataDirectory: Path = Host.dataDir(),
        helperCommand: List<String>? = CaptureHelper.command(),
    ) {
        val graph = runCatching {
            AppModule.build(dataDir = dataDirectory, localization = localization)
        }.getOrElse {
            status = Str.STATUS_CORE_ERROR.message()
            it.printStackTrace()
            return
        }
        this.graph = graph
        dataDir = graph.dataDir.toString()
        deviceId = graph.core.deps.device.deviceId
        val logger = graph.core.deps.logger
        balloon = TrayAlertBalloon(logger, localization::current)
        launcher = LaunchAtLogins.create(logger)
        launchAtLogin = launcher.isEnabled()
        consentReminder = settings.consentReminder
        recordingMode = settings.recordingMode
        micAccess = MicrophoneAccess.create(logger).state()

        val command = helperCommand
        this.helperCommand = command
        helperMissing = command == null
        // deliverable 3: the path check above only says a file is there; this is the one that says
        // it runs, and what it is.
        helperVersion = command?.let { withContext(graph.core.deps.io) { CaptureHelper.version(it) } }
        // docs/14 "감지": before the recorder, because the recorder hands detection back and forth
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
                // for the answer. A quit does not ask.
                // A second recording finishing while the first is still unnamed does not take the
                // prompt from it: that one is queued as it stands rather than losing its job.
                if (askTitle && titles.publish(outcome)) {
                    status = TITLE_PROMPT.message()
                } else {
                    complete(outcome, title = null)
                }
            },
            onState = { isRecording ->
                recording = isRecording
                // The popup's timer counts from here rather than from when the popup was opened.
                recordingSince = if (isRecording) System.currentTimeMillis() else null
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
            onHelperDied = {
                helperCrashed = true
                status = HELPER_DIED.message()
            },
        )

        workflowsModel = WorkflowsModel(
            documents = object : WorkflowDocuments {
                override suspend fun current() = graph.core.workflows.current()
                override suspend fun save(document: WorkflowsDocument) =
                    graph.core.workflows.save(document)
            },
            secrets = graph.secrets,
            clock = graph.core.deps.clock,
            exportJson = { graph.core.workflows.exportJson() },
            importJson = { json -> graph.core.workflows.importJson(json) },
            saveFile = ::saveWorkflowsFile,
            openFile = ::openWorkflowsFile,
            deviceDefault = { graph.core.workflows.deviceDefault() },
            setDeviceDefault = { id ->
                graph.core.workflows.setDeviceDefault(id)
                refreshWorkflows()
            },
            clipboard = ::copyToClipboard,
            onDocumentChanged = ::refreshWorkflows,
        )

        // docs/03 "복구", before the tray can start anything: a recording the last run left open is
        // finished here, and one whose job never got made is queued — both before the first pass.
        val recovered = runCatching { RecordingRecovery(graph.core).reconcile() }
            .onFailure { logger.log(Logger.Level.ERROR, "rec.recovery.failed", error = it) }
            .getOrDefault(0)

        signedIn = graph.auth.isSignedIn()
        // ADR-016: this PC's own default is 메모, the one starter a fresh install has.
        runCatching { graph.core.workflows.seed(WorkflowRepository.MEMO_ID) }
            .onFailure { logger.log(Logger.Level.ERROR, "shell.workflows.seed.failed", error = it) }
        refreshWorkflows()

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
                "workflows" to workflows.size,
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
     * [mode] is this PC's own pick unless the caller has one of its own — which the detection path
     * does: a meeting Recly found by hearing it is a meeting whatever the chips were last set to
     * (the Mac's `MenuModel.start(workflowId:mode:)`). The setting is read here, never written.
     */
    fun start(workflowId: String?, mode: RecordingMode = recordingMode) {
        if (recorder == null || titlePrompt != null || consentRequest != null) return
        // docs/03: the clean-up half of a disconnect walks the recording directory, so a capture
        // started inside it would be one its own delete pass is writing over. Say what is in the
        // way rather than starting something that is about to be taken away.
        DisconnectGate.startBlocker()?.let {
            status = it
            return
        }
        if (mode.remindsConsent && consentReminder) {
            consentRequest = ConsentRequest(workflowId, mode)
            return
        }
        begin(workflowId, mode)
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
        begin(request.workflowId, request.mode)
    }

    /**
     * The consent reminder put on screen to be photographed (`--show-consent`), whatever the setting
     * says. Not [start]: that one is a recording that stops in front of the question, so with the
     * reminder switched off it opens a real capture and never raises the dialog at all.
     */
    fun previewConsent() {
        dialogMode = DialogMode.PREVIEW
        consentRequest = ConsentRequest(null, RecordingMode.MEETING)
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

    /** Cancel — the question has to mean something, so this is the end of it. */
    fun consentCancelled() {
        consentRequest = null
    }

    private fun begin(workflowId: String?, mode: RecordingMode) {
        val recorder = recorder ?: return
        helperCrashed = false
        // The tray menu is a snapshot: the recording that just ended may still be waiting for its
        // name, and a second one would take the prompt's place and lose the first one's job.
        if (titlePrompt != null) return
        // ADR-006: this PC's capture takes the speakers with it, so what is playing would be *in*
        // the recording. Up here, before anything is asked of the recorder — `onState(true)` is
        // published only after the helper's Start has gone out, so a gate that waited for it would
        // be going up over a capture that had already begun. It stays up until that capture ends.
        val capture = PlaybackGate.Reason.CAPTURE
        playbackGate.raise(capture)
        // docs/09 화면 원칙 1: `STARTING` for as long as the helper is coming up — from here, where
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
                // The menu was drawn from a list that may be minutes old — another device could have
                // deleted this workflow since (docs/05).
                refreshWorkflows()
                if (workflowId != null && workflows.none { it.id == workflowId }) {
                    status = Str.STATUS_WORKFLOW_GONE.message()
                    return@launch
                }
                // docs/03: the gate is held across the last look at it *and* the start itself. The
                // read above suspends, so the check [start] made before it says nothing about the
                // moment the capture actually opens — a disconnect that took the gate inside that
                // wait would be walking the recording directory while this capture wrote into it. A
                // start that finds the gate held is refused rather than queued behind the disconnect.
                status = DisconnectGate.ifOpen {
                    // The recording that ended a moment ago may have published its prompt while the
                    // summaries were being read; the prompt is published under this same lock.
                    val id = titles.ifIdle { recorder.start(workflowId, mode = mode) }
                    started = id != null
                    // What the recording that is running is made of, for as long as it runs — the
                    // detector reads it to tell a meeting's end from a memo's silence.
                    if (started) captureMode = mode
                    when {
                        id != null -> Str.STATUS_RECORDING
                        titlePrompt != null -> TITLE_PROMPT
                        else -> Str.STATUS_CANNOT_START
                    }.message()
                }
                    // A gate held by the disconnect says so; one held by another start that is
                    // already opening is the same answer the recorder itself would have given this.
                    ?: DisconnectGate.startBlocker() ?: Str.STATUS_CANNOT_START.message()
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

    fun stop() {
        val recorder = recorder ?: return
        // docs/09 화면 원칙 1: `STOPPING` until the last segment is closed and the parts are filed —
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
                if (result is StopResult.Deferred) status = Str.STATUS_DEFERRED.message(result.pending)
            } finally {
                transition = null
            }
        }
    }

    /**
     * ADR-016: picking in the popup *is* the pointer — the same write the workflows window's row
     * makes ([WorkflowsModel.setDefault]). There is no pick that lasts only as long as the popup:
     * what the picker shows is what every recording on this PC runs until someone picks another.
     */
    fun selectWorkflow(id: String) {
        val graph = graph ?: return
        scope.launch {
            graph.core.workflows.setDeviceDefault(id)
            refreshWorkflows()
            // A workflows window open beside the popup marks the same workflow and holds its own
            // reading of the pointer, so it is told to take another one.
            workflowsModel?.reload()
        }
    }

    /**
     * ADR-016: what the tray may offer, which is every workflow the document has, and which of them
     * a start runs, which is this PC's own local pointer resolved against it. The pointer is not in
     * the document, so both are read together whenever either could have moved.
     */
    private suspend fun refreshWorkflows() {
        val graph = graph ?: return
        runCatching { graph.core.workflows.current() to graph.core.workflows.deviceDefault() }
            .onSuccess { (document, selected) ->
                workflows = document.workflows.map { WorkflowSummary(it.id, it.name) }
                selectedWorkflow = workflows.firstOrNull { it.id == selected }
            }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.workflows.failed", error = it) }
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

    /** The title dialog's two buttons; skipping and a closed window are the same answer. */
    fun saveTitle(title: String, participants: Int? = null) = answerTitle(title, participants)

    fun skipTitle() = answerTitle(null, null)

    private fun answerTitle(title: String?, participants: Int?) {
        scope.launch {
            val outcome = titles.take() ?: return@launch
            complete(outcome, title, participants)
        }
    }

    /** docs/03: name it, then queue it — never the other way round (see [completeRecording]). */
    private suspend fun complete(outcome: RecordingOutcome, title: String?, participants: Int? = null) {
        val graph = graph ?: return
        runCatching { completeRecording(graph.core, outcome.recordingId, title, participants) }
            .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "rec.enqueue.failed", error = it) }
        // docs/12/14 "실행기" (a): the job exists now, so a pass runs immediately rather than
        // waiting for the five-minute timer.
        runner?.jobsDue()
        status = Str.STATUS_WAITING.message()
    }

    // --- executor (docs/14 "실행기") --------------------------------------------------------------

    private fun adopt(jobs: List<Job>) {
        needsAuth = jobs.any { it.status == JobStatus.NEEDS_AUTH }
        if (needsAuth) status = NEEDS_AUTH_NOTICE.message()
        scope.launch {
            refreshRecents()
            refreshAlerts(jobs)
            // A pass pulls when the five-minute gate is open (docs/05), so the definitions the tray
            // offers may have just been replaced by another device's.
            refreshWorkflows()
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
     * docs/10: "탭하면 고칠 수 있는 화면으로 간다 — '앱 열기'로 끝내지 않는다." Four surfaces, and the
     * only one that leaves the app is the storage page, because the space is Google's to give back.
     */
    fun fix(alert: JobAlert) = when (alert.reason.fix) {
        FixSurface.SIGN_IN -> signIn()
        FixSurface.DRIVE_STORAGE -> open(DRIVE_STORAGE_URL)
        // The key the step named, in the form under that step — not the workflow's list of keys.
        FixSurface.SECRETS -> openEditor(alert.workflowId) { model ->
            model.openSecrets(alert.secret, alert.stepId)
        }

        FixSurface.EDITOR -> openEditor(alert.workflowId) {}
    }

    private fun openEditor(workflowId: String?, then: (WorkflowsModel) -> Unit) {
        editorOpen = true
        val model = workflowsModel ?: return
        scope.launch {
            model.reload()
            workflowId?.let { model.edit(it) }
            then(model)
        }
    }

    private suspend fun refreshRecents() {
        val graph = graph ?: return
        runCatching { Recents.load(graph.core) }
            .onSuccess { recents = it }
            .onFailure {
                graph.core.deps.logger.log(Logger.Level.ERROR, "shell.recents.failed", error = it)
            }
    }

    /**
     * docs/03 "다른 기기의 녹음": what other devices have uploaded since, for a list that has just
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

    /** Retry: the job the row failed on is made due now. Only a failure offers it (docs/09). */
    fun retry(item: RecentItem) {
        val graph = graph ?: return
        val jobId = item.jobId ?: return
        scope.launch {
            runTracked {
                graph.core.jobs.retry(jobId)
                runner?.jobsDue()
                true
            }
        }
    }

    fun openInDrive(item: RecentItem) {
        item.link?.let { open(it) }
    }

    /**
     * docs/08 "결과 파일": the local copy if the step ran on this PC, and Drive's if it ran
     * elsewhere — the core decides which, and keeps what it downloads. The audio beside it is read
     * the same way: what is on this PC first, and Drive for what the sweep took ([fetchFromDrive]).
     */
    fun openDetail(item: RecentItem) {
        val graph = graph ?: return
        detail = RecordingDetail(item.id, item.title)
        scope.launch {
            val result = runCatching { graph.core.results(item.id) }
                .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.detail.failed", error = it) }
                .getOrNull()
            val record = runCatching { graph.core.recordings.get(item.id) }
                .onFailure { graph.core.deps.logger.log(Logger.Level.ERROR, "shell.detail.failed", error = it) }
                .getOrNull()
            val local = record?.let { rec ->
                RecordingPlaylist.select(rec.meta.parts, rec.dir) { path ->
                    runCatching { graph.core.deps.fileSystem.exists(path) }.getOrDefault(false)
                }
            } ?: RecordingPlaylist.Selection.EMPTY
            // The user may have picked another recording while Drive was answering.
            if (detail?.recordingId != item.id) return@launch
            // Out of `loading` before the fetch, because the player bar is where the fetch is said —
            // and the bar stays on `DECIDING` until the trip has been decided, so the seconds it
            // spends asking Drive are not seconds in which Play is offered.
            detail = RecordingDetail(
                recordingId = item.id,
                title = item.title,
                loading = false,
                transcript = result?.transcript,
                audio = local,
                writing = record?.meta?.status == RecordingStatus.RECORDING,
            )
            fetchFromDrive(graph, item.id, record, local)
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
        updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.FETCHING) }
        runCatching { graph.core.audio(recordingId) }.fold(
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

    /** docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where "check" lands. */
    fun editWorkflowOf(item: RecentItem) {
        val workflowId = item.workflowId ?: return
        editorOpen = true
        scope.launch {
            workflowsModel?.reload()
            workflowsModel?.edit(workflowId)
        }
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

    // --- deleting a recording (docs/03 "앱에서 지우기") -------------------------------------------

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
        scope.launch {
            // Before the files go, not after, and with Play off the bar for the whole of it: the
            // recording being deleted is the one the detail is showing, and the detail is what plays.
            val deleted = playbackGate.cleaning(::stopPlayback) {
                // And the detail goes with them: a recordings window left open on the recording that
                // is about to stop existing is a detail with nothing behind it (the Mac clears the
                // same one — `MenuModel.delete`). Inside the gate, so a decoder that outlived the
                // bound leaves the detail alone along with the files.
                if (detail?.recordingId == request.recordingId) detail = null
                status = runCatching { graph.core.recordings.delete(request.recordingId, deleteDrive) }
                    .fold(
                        onSuccess = { result ->
                            when (result) {
                                is DeleteResult.Deleted -> result.driveError
                                    ?.let { Str.DELETE_DRIVE_FAILED.message(it) }
                                    ?: Str.DELETE_DONE.message()

                                DeleteResult.Busy -> Str.DELETE_BUSY.message()
                                // Already gone — by a previous attempt whose row this window had not
                                // caught up with.
                                DeleteResult.NotFound -> Str.DELETE_DONE.message()
                            }
                        },
                        onFailure = { error ->
                            graph.core.deps.logger.log(Logger.Level.ERROR, "rec.delete.failed", error = error)
                            Str.CORE_STEP_FAILED.message(error.message ?: error::class.simpleName.orEmpty())
                        },
                    )
                refreshRecents()
                runner?.jobsDue()
                true
            }
            // Nothing was deleted: the decoder outlived the bound and the row is still here to try
            // again with, which is the whole point of not having deleted it.
            if (deleted == null) status = Str.PLAYER_STOP_FAILED.message()
        }
    }

    // --- disconnecting (docs/03 "로그아웃 vs 연결 해제" · docs/06) ---------------------------------

    /**
     * Opens the docs/03 warning. The count is read first because the dialog has to state it: a user
     * about to lose the queue deserves to know what is still only on this PC.
     */
    fun askToDisconnect() {
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
     * docs/03 "연결 해제", both halves and in this order: the Google grant, which is what makes the
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
        if (disconnectChecking) return
        disconnectChecking = true
        scope.launch {
            // The dialog may have stood while the editor window saved an edit whose push failed —
            // every window is its own surface. What it promised is read again before it is acted
            // on; a warning it never showed re-asks instead of destroying quietly.
            val fresh = DisconnectPrompt(
                unuploaded = Retention.unuploadedRecordings(graph.core),
                recording = recording,
            )
            if (fresh.warnsMore(shown)) {
                disconnectPrompt = fresh
                disconnectChecking = false
                return@launch
            }
            disconnectPrompt = null
            disconnectChecking = false
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
            if (ran == null) status = Str.PLAYER_STOP_FAILED.message()
        }
    }

    /** True from a confirm until its re-read decides — see [disconnect]. */
    private var disconnectChecking = false

    private suspend fun runDisconnect(graph: AppGraph, alsoDeleteRecordings: Boolean): Boolean {
        // The recorder is read again here and not only when the warning opened: the dialog may have
        // stood while something started a capture, and the gate cannot refuse one already running.
        DisconnectGuard.liveBlocker(recording)?.let {
            status = it
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
                status = DisconnectGuard.saveFailed()
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
            status = if (owed) racing else DisconnectGuard.saveFailed()
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
                status = DisconnectGuard.saveFailed()
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
        status = DisconnectGuard.completion(revoked, cleanupFailure, result, revokeDebt)
        // The queue is gone with everything else, so nothing is blocked on anything any more.
        needsAuth = false
        alerts = emptyList()
        refreshRecents()
        // A clean-up that had to keep a busy recording is still owed (the phase says so), and the
        // button must not say done over it.
        return revoked !is RevokeResult.Failed && cleanupFailure == null &&
            result?.busyRecordings.isNullOrEmpty()
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
        if (!persistRevokeDebt(false)) status = DisconnectGuard.saveFailed()
    }

    /** The consent dialog's link (docs/12 M8) — a browser, so it does not dismiss the question. */
    fun openConsentGuidance() = open(Consent.LINK)

    /** docs/03: the page that takes this app's access away by hand, when the revoke could not. */
    fun openAccountPermissions() = open(GOOGLE_PERMISSIONS_URL)

    /**
     * docs/14 "권한": there is no prompt to answer, so a microphone that is switched off for desktop
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
                        status = Str.STATUS_SIGN_IN_FAILED.message(result.reason)
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
        graph.core.jobs.list().filter { it.status == JobStatus.NEEDS_AUTH }
            .forEach { graph.core.jobs.retry(it.id) }
        needsAuth = false
        runner?.jobsDue()
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
     * docs/14 "캡처": the mode the *next* recording runs in. A recording already in flight keeps the
     * one it started with — its track set is in `meta.json` — which is why the chips are off while
     * one is running, exactly as the Mac's are.
     */
    fun selectRecordingMode(choice: RecordingMode) {
        settings.recordingMode = choice
        recordingMode = choice
    }

    /**
     * docs/07 rule 3: the tray menu, the open windows and the next notification are all in the new
     * language the moment this returns — the tables behind them are one [Localization] state.
     */
    fun selectLanguage(choice: AppLanguage) {
        localization.language = choice
        language = choice
    }

    fun selectTheme(choice: AppTheme) {
        settings.theme = choice
        theme = choice
    }

    /** deliverable 3: the helper's own report, from the settings window, on the machine it runs on. */
    fun runSelfTest() {
        val command = helperCommand ?: return
        val io = graph?.core?.deps?.io ?: return
        selfTest = Str.SELF_TEST_RUNNING.message()
        scope.launch { selfTest = withContext(io) { CaptureHelper.selfTest(command) } }
    }

    fun openDataDir() {
        graph?.dataDir?.let { open(it.toString()) }
    }

    // --- docs/05 "워크플로우 내보내기 · 가져오기" -------------------------------------------------

    fun exportWorkflows() = transferring { it.exportWorkflows() }

    fun importWorkflows() = transferring { it.pickImport() }

    fun confirmImport() = transferring { it.confirmImport() }

    fun cancelImport() {
        workflowsModel?.cancelImport()
    }

    /**
     * The settings window's buttons drive [WorkflowsModel], which is where the state and the words
     * live; this only lends them [action] and takes its banner. Set before the suspension, or an
     * operation slower than the button's own window re-enables it mid-flight and the second press
     * is a duplicate (as [runTracked] does for the rest).
     */
    private fun transferring(block: suspend (WorkflowsModel) -> Unit) {
        val model = workflowsModel ?: return
        scope.launch {
            action = ProcessingState.PROCESSING
            try {
                block(model)
            } catch (e: Throwable) {
                settle(ProcessingState.FAILED)
                throw e
            }
            adopt(model)
            // The model knows whether it worked; the button here is only showing what it decided.
            settle(model.action)
        }
    }

    /**
     * docs/05: the file itself. AWT's own dialog rather than Swing's chooser — it is the Windows
     * shell dialog, which is the one a user of this app has seen before, and this app already draws
     * everything else itself. It is modal and runs its own event pump, so it is called off the UI
     * thread's coroutine like every other blocking call here.
     *
     * False when the user closed it without choosing: that asked for nothing and reports nothing.
     * A write that failed is the shell's own complaint, not the core's ([WorkflowsModel.fileFailed]).
     */
    private suspend fun saveWorkflowsFile(name: String, contents: String): Boolean {
        val chosen = fileDialog(FileDialog.SAVE, name) ?: return false
        return runCatching { withContext(Dispatchers.IO) { chosen.writeText(contents) } }
            .onFailure { workflowsModel?.fileFailed(it.reason()) }
            .isSuccess
    }

    /** Null both when the user closed the dialog and when the file would not open — see above. */
    private suspend fun openWorkflowsFile(): String? {
        val chosen = fileDialog(FileDialog.LOAD, null) ?: return null
        return runCatching { withContext(Dispatchers.IO) { chosen.readText() } }
            .onFailure { workflowsModel?.fileFailed(it.reason()) }
            .getOrNull()
    }

    private suspend fun fileDialog(mode: Int, name: String?): File? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, APP_NAME, mode)
        name?.let { dialog.file = it }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory ?: "", it) }
    }

    /** A file-system failure is a diagnostic, never a sentence — it is shown under one. */
    private fun Throwable.reason(): String = message ?: this::class.simpleName.orEmpty()

    /**
     * Runs [block] with [action] following it, and [settle]s on whether it did what the user asked.
     * Whatever it threw carries on to the scope exactly as it did before — this only makes sure the
     * button is not left saying "…" for ever. `WorkflowsModel.working` is the same window without
     * the settle, because that button's own operation decides what it lands on.
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

    /**
     * The tray takes both halves of the editor's banner, not only the sentence: an import started
     * from the settings window can fail with a diagnostic the editor would have shown underneath,
     * and that window may not even be open (Sol I18N-L3 #2).
     */
    internal fun adopt(model: WorkflowsModel) {
        statusLine.say(model.message ?: return, model.messageDetail)
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

    private fun copyToClipboard(value: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        }
    }

    /**
     * A recording the consent question is standing in front of (docs/12 M8). It carries the mode it
     * was asked about: the answer starts *that* recording, and the chips may have moved while the
     * dialog stood.
     */
    data class ConsentRequest(val workflowId: String?, val mode: RecordingMode)

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
            ready && !helperMissing && titlePrompt == null && consentRequest == null &&
                // A microphone-only recording is not one [isRecording] reports, so this is what
                // keeps the offer off a PC that is already recording (the Mac's `isIdle` guard).
                !recording && !DisconnectGate.busy

        /**
         * Whether a **meeting** is in flight, which is what the rule's `Signals.isRecording` is
         * about: docs/12 "종료 감지" is about this recording, and a microphone-only memo's own idle
         * microphone is not a meeting that has ended (the Mac's `recordingChanged(mode == .meeting)`).
         */
        override fun isRecording(): Boolean = recording && captureMode.detectsEnd

        /** ADR-011: a meeting Recly found by hearing it is a meeting, whatever the chips say. */
        override fun start() = this@ShellModel.start(null, RecordingMode.MEETING)

        override fun stop() = this@ShellModel.stop()

        override fun offered(prompt: MeetingDetectionRule.Prompt?, token: Long) {
            meetingOffer = prompt
            meetingOfferToken = token
        }
    }

    companion object {
        val HELPER_MISSING = Str.STATUS_HELPER_MISSING
        val HELPER_DIED = Str.STATUS_HELPER_DIED
        val MIC_DENIED = Str.STATUS_MIC_DENIED
        val NEEDS_AUTH_NOTICE = Str.STATUS_SIGN_IN_NEEDED
        val TITLE_PROMPT = Str.STATUS_NAMING

        /** docs/03: Google's own page, which is the only place a failed revoke can be finished. */
        const val GOOGLE_PERMISSIONS_URL = "https://myaccount.google.com/permissions"

        /** docs/14 "권한": 설정 → 개인정보 → 마이크, the page and not directions to it. */
        const val MICROPHONE_SETTINGS_URL = "ms-settings:privacy-microphone"

        /** Windows' own settings scheme — a deep link, not a path (see `open`). */
        private const val SETTINGS_SCHEME = "ms-settings:"
    }
}
