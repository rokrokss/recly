package app.recly.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.recly.android.R
import app.recly.android.ui.component.BlueprintNavBar
import app.recly.android.ui.component.NavGlyph
import app.recly.android.ui.component.NavItem
import app.recly.android.ui.theme.ReclyTheme
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.dotGrid
import app.recly.android.work.WorkScheduler
import app.recly.recording.RecorderState
import kotlinx.coroutines.launch

/**
 * What the phone app is in M2: record, watch the queue, define what a recording is for, and the
 * account (docs/11 A3·A4·A6·A10). The editor is its own tab rather than a page inside Settings — it
 * is the screen a user opens on purpose, not one they go looking for behind a setting.
 */
private enum class Tab(@param:StringRes val label: Int, val glyph: NavGlyph) {
    RECORD(R.string.tab_record, NavGlyph.RECORD),
    JOBS(R.string.tab_jobs, NavGlyph.LIST),
    WORKFLOWS(R.string.tab_workflows, NavGlyph.WORKFLOWS),
    SETTINGS(R.string.tab_settings, NavGlyph.SETTINGS),
}

class MainActivity : ComponentActivity() {

    private val model: MainViewModel by viewModels()
    private val recordingModel: RecordingViewModel by viewModels()
    private val jobsModel: JobsViewModel by viewModels()
    private val workflowsModel: WorkflowsViewModel by viewModels()
    private val settingsModel: SettingsViewModel by viewModels()

    /**
     * One launcher, registered before `onStart` as the contract requires. It is the only thing the
     * activity contributes to the consent flow: the request and the suspended authorization both
     * live in the ViewModel, so an activity recreated while the consent screen is up simply picks
     * the result up here and forwards it.
     */
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.onConsentResult(result.resultCode, result.data)
    }

    /**
     * The system "add a Google account" screen, for a device with no account at all — Credential Manager
     * has nothing to offer there and only Settings can fix it. The result code says nothing useful
     * (it is `RESULT_CANCELED` even after an account is added), so coming back at all is the signal.
     */
    private val addAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onAddAccountResult(this)
    }

    /**
     * docs/05 "워크플로우 내보내기 · 가져오기". The two SAF contracts and nothing else: where the file
     * goes and which file it is are the platform's to ask, and the bytes are the ViewModel's.
     * Cancelling either picker hands back a null uri, which is not a failure and says nothing.
     */
    private val exportWorkflows =
        registerForActivityResult(ActivityResultContracts.CreateDocument(WORKFLOWS_MIME)) { uri ->
            uri?.let(settingsModel::exportWorkflows)
        }

    private val importWorkflows =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(settingsModel::pickImport)
        }

    /**
     * docs/10: which fix screen a tapped job notification asked for, until the composition has
     * taken it. It is state and not a flag on the intent for the same reason as the auto-start —
     * the intent is redelivered on every recreation and the tap happened once.
     */
    private val fixRequest = mutableStateOf<FixRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch: a rotation or a restore redelivers the same intent, and one tap on a
        // tile must not become two recordings.
        if (savedInstanceState == null) consumeAutoStart(intent)
        // The fix is consumed on every launch, restored or not: the tap happened once, and a
        // process killed between it and this line would otherwise leave the user with the app open
        // and nothing having happened. The notification itself stays up until the queue says the
        // reason is gone (docs/10 rule 3), so the tap is never the last chance to act on it.
        consumeFix(intent)
        setContent {
            val settings by settingsModel.state.collectAsState()
            // docs/09 "접근성": the system decides the font scale and reduce motion, and the theme is
            // the only place that knows about either. Dark is the system's too until the setting
            // says otherwise, which is the one thing the theme is told.
            ReclyTheme(theme = settings.theme) {
                val state by model.state.collectAsState()
                val consentRequest by model.consentRequest.collectAsState()

                LaunchedEffect(consentRequest) {
                    val pending = consentRequest ?: return@LaunchedEffect
                    // consumeLaunch() is false for a recreated activity, so the consent screen is
                    // never shown twice for one request.
                    if (model.consumeConsentLaunch()) {
                        consent.launch(IntentSenderRequest.Builder(pending).build())
                    }
                }

                val addAccountRequest by model.addAccountRequest.collectAsState()

                LaunchedEffect(addAccountRequest) {
                    if (!addAccountRequest) return@LaunchedEffect
                    if (model.consumeAddAccountLaunch()) {
                        addAccount.launch(
                            Intent(Settings.ACTION_ADD_ACCOUNT)
                                .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")),
                        )
                    }
                }

                val recording by recordingModel.state.collectAsState()
                val recorder by recordingModel.recorder.collectAsState()
                val jobs by jobsModel.state.collectAsState()
                val workflows by workflowsModel.state.collectAsState()
                var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
                // docs/11 A9: a tile, widget or shortcut tap is a request to record *now*, so it
                // brings the Record tab with it. The tab is remembered across a restore, and a
                // request left lying behind another tab used to be spent on whatever switch to
                // Record came next — a recording the user had asked for minutes or hours earlier.
                LaunchedEffect(recording.autoStart) {
                    if (recording.autoStart != null) tab = Tab.RECORD
                }
                LaunchedEffect(tab) {
                    // Leaving the Workflows tab forgets an editor a notification asked for but the
                    // document could not answer yet — it must not pop up over another screen later.
                    if (tab != Tab.WORKFLOWS) workflowsModel.dismissPending()
                    // The same for an auto-start the user has navigated away from.
                    if (tab != Tab.RECORD) recordingModel.dropAutoStart()
                }

                // docs/10 "탭하면 고칠 수 있는 화면으로 간다": the one mapping, shared by the list's
                // banner and by the notification that says the same thing.
                // docs/06 Android: signed in, the fix is the Drive consent itself, right here;
                // signed out, it is the sign-in button, which lives in Settings.
                val fixAuth = {
                    if (state.email == null) tab = Tab.SETTINGS else model.reauthorizeDrive(this)
                }
                val goFix: (AlertReason, String?) -> Unit = { reason, workflowId ->
                    when (reason.fix) {
                        FixSurface.SIGN_IN -> fixAuth()
                        FixSurface.DRIVE_STORAGE -> startActivity(
                            Intent(Intent.ACTION_VIEW, DRIVE_STORAGE_URL.toUri()),
                        )

                        FixSurface.SECRETS -> {
                            tab = Tab.WORKFLOWS
                            workflowsModel.openSecrets()
                        }

                        // docs/10:124-135: a quota or a webhook is fixed in the definition that
                        // holds the key or the URL, so the editor of *that* workflow is the screen
                        // — the tab alone would leave the user to find it. Several workflows on one
                        // reason open the first; the banner behind them still counts them all.
                        FixSurface.EDITOR -> {
                            tab = Tab.WORKFLOWS
                            workflowId?.let(workflowsModel::edit)
                        }
                    }
                }

                LaunchedEffect(fixRequest.value) {
                    val request = fixRequest.value ?: return@LaunchedEffect
                    fixRequest.value = null
                    goFix(request.reason, request.workflowId)
                }
                BackHandler(enabled = workflows.secretsOpen != null || workflows.editor != null) {
                    if (workflows.secretsOpen != null) workflowsModel.closeSecrets() else workflowsModel.cancel()
                }

                // The detail screen is a page inside the jobs tab, so Back has to leave it.
                BackHandler(enabled = tab == Tab.JOBS && jobs.detail != null) { jobsModel.closeDetail() }

                Scaffold(
                    modifier = Modifier.dotGrid(blueprint),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        BlueprintNavBar(
                            Tab.entries.map { entry ->
                                NavItem(
                                    glyph = entry.glyph,
                                    label = stringResource(entry.label),
                                    selected = tab == entry,
                                    onClick = { tab = entry },
                                )
                            },
                        )
                    },
                ) { insets ->
                    val content = Modifier.fillMaxSize().padding(insets)
                    when (tab) {
                        Tab.RECORD -> RecordTab(recording, recorder, jobs, recordingModel, content)

                        Tab.JOBS -> JobsTab(
                            state = jobs,
                            model = jobsModel,
                            // docs/10: NEEDS_AUTH is unblocked by signing in, not waiting.
                            onSignIn = fixAuth,
                            // docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is
                            // where "check the key" has to land.
                            onCheckKey = { workflowId ->
                                tab = Tab.WORKFLOWS
                                workflowId?.let(workflowsModel::edit)
                            },
                            onFix = { alert -> goFix(alert.reason, alert.workflowId) },
                            modifier = content,
                        )

                        Tab.WORKFLOWS -> WorkflowsTab(workflows, workflowsModel, content)

                        Tab.SETTINGS -> SettingsTab(
                            main = state,
                            settings = settings,
                            model = model,
                            settingsModel = settingsModel,
                            activity = this@MainActivity,
                            onExportWorkflows = { exportWorkflows.launch(WORKFLOWS_FILE_NAME) },
                            onImportWorkflows = { importWorkflows.launch(WORKFLOWS_MIME_FILTER) },
                            modifier = content,
                        )
                    }
                }
            }
        }
    }

    /** A tile or widget tap while this activity is already on top (see [startRecording]'s flags). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoStart(intent)
        consumeFix(intent)
    }

    /** docs/11 A5 trigger (b): coming back to the app is as good a reason to run the queue as any. */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { WorkScheduler(applicationContext).runNow() }
        // docs/06 Android: and the activity a NEEDS_AUTH job has been waiting for.
        model.resumeParked(this)
        // docs/03 "다른 기기의 녹음": and as good a reason to ask Drive what the other devices have
        // uploaded since — the ledger is on screen again.
        jobsModel.refresh()
    }

    private fun consumeAutoStart(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_AUTO_START, false)) return
        // An entry point that could not stamp the tap (see [EXTRA_REQUESTED_AT]) leaves the extra
        // off, and getting here is then the closest this app has to the moment it was asked.
        val requestedAt = intent.getLongExtra(EXTRA_REQUESTED_AT, SystemClock.elapsedRealtime())
        intent.removeExtra(EXTRA_AUTO_START)
        intent.removeExtra(EXTRA_REQUESTED_AT)
        recordingModel.requestAutoStart(requestedAt)
    }

    private fun consumeFix(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_FIX) ?: return
        val workflowId = intent.getStringExtra(EXTRA_FIX_WORKFLOW)
        intent.removeExtra(EXTRA_FIX)
        intent.removeExtra(EXTRA_FIX_WORKFLOW)
        fixRequest.value = AlertReason.entries.firstOrNull { it.name == name }
            ?.let { FixRequest(it, workflowId) }
    }

    companion object {
        /** docs/11 A9: what the tile, the widget and the launcher shortcut all ask for. */
        const val EXTRA_AUTO_START: String = "app.recly.android.extra.AUTO_START"

        /**
         * docs/11 A9 "spend or drop": when the tap behind [EXTRA_AUTO_START] happened, on
         * `SystemClock.elapsedRealtime()`. The age is what decides whether the request is still
         * worth acting on (see [autoStartStillWanted]), and taking it in [consumeAutoStart] instead
         * measured from the wrong end — the cold start the user was waiting through was free.
         *
         * It is optional because not every entry point has a tap to stamp: the launcher shortcut is
         * static XML, and the widget's `PendingIntent` is built when the widget is *rendered* rather
         * than when it is tapped, so a stamp taken there would be the last redraw — minutes or hours
         * old — and would drop every tap. Those two leave it off, and a missing stamp is read as
         * "now": no worse than the behaviour this replaced.
         */
        const val EXTRA_REQUESTED_AT: String = "app.recly.android.extra.REQUESTED_AT"

        /** docs/10: which [AlertReason]'s fix screen a tapped job notification wants. */
        const val EXTRA_FIX: String = "app.recly.android.extra.FIX"

        /** …and, for the reasons a workflow holds the fix for, which workflow's editor. */
        const val EXTRA_FIX_WORKFLOW: String = "app.recly.android.extra.FIX_WORKFLOW"

        /**
         * `SINGLE_TOP` so a second tap reaches [onNewIntent] instead of stacking another copy of
         * the app; `NEW_TASK` because a tile and a widget both launch from outside a task.
         *
         * @param requestedAt the tap, on `SystemClock.elapsedRealtime()`, for an entry point that
         * builds this intent when the user taps — the tile does. Null for one that cannot; see
         * [EXTRA_REQUESTED_AT].
         */
        fun startRecording(context: Context, requestedAt: Long? = null): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_AUTO_START, true)
                .apply { requestedAt?.let { putExtra(EXTRA_REQUESTED_AT, it) } }

        /** What a job notification opens (docs/10) — the screen that can undo the reason. */
        fun fix(context: Context, alert: JobAlert): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_FIX, alert.reason.name)
                .putExtra(EXTRA_FIX_WORKFLOW, alert.workflowId)
    }
}

/** docs/10: one tapped notification — the reason, and the workflow whose editor undoes it. */
private data class FixRequest(val reason: AlertReason, val workflowId: String?)

/** docs/05 "워크플로우 내보내기": the name SAF suggests, which every shell offers the same. */
private const val WORKFLOWS_FILE_NAME = "recly-workflows.json"

private const val WORKFLOWS_MIME = "application/json"

/**
 * What the open picker will let the user through. `application/json` alone hides the very file this
 * app writes on a device whose provider typed it as text or as a plain byte stream — a file another
 * shell's share sheet handed over, say — so all three are accepted and the parser is what refuses.
 */
private val WORKFLOWS_MIME_FILTER =
    arrayOf(WORKFLOWS_MIME, "text/plain", "application/octet-stream")

@Composable
private fun RecordTab(
    state: RecordingUiState,
    recorder: RecorderState,
    jobs: JobsUiState,
    model: RecordingViewModel,
    modifier: Modifier,
) {
    RecordingSection(
        state = state,
        recorder = recorder,
        // docs/09 화면 원칙 1: the state node borrows the ledger while the recorder is idle, and the
        // ledger is the same live list the jobs tab draws.
        ledger = ledgerCode(jobs.items),
        onSelectWorkflow = model::selectWorkflow,
        onStart = model::start,
        onStop = model::stop,
        onMicDenied = model::micDenied,
        onMicGranted = model::micGranted,
        onConsumeAutoStart = model::consumeAutoStart,
        onSaveTitle = model::saveTitle,
        onSkipTitle = model::skipTitle,
        onConsentAnswered = model::consentAnswered,
        modifier = modifier,
    )
}

/** The detail screen is a page inside this tab, not a tab of its own — see the [BackHandler]. */
@Composable
private fun JobsTab(
    state: JobsUiState,
    model: JobsViewModel,
    onSignIn: () -> Unit,
    onCheckKey: (String?) -> Unit,
    onFix: (JobAlert) -> Unit,
    modifier: Modifier,
) {
    val detail = state.detail
    if (detail != null) {
        RecordingDetailScreen(
            detail = detail,
            onClose = model::closeDetail,
            onRename = { title -> model.rename(detail.recordingId, title) },
            modifier = modifier,
        )
    } else {
        JobsScreen(
            state = state,
            onRetry = model::retry,
            onConfirmDelete = model::confirmDelete,
            onCancelDelete = model::cancelDelete,
            onDelete = model::delete,
            onSignIn = onSignIn,
            onOpenDetail = model::openDetail,
            onCheckKey = { item -> onCheckKey(item.workflowId) },
            onFix = onFix,
            modifier = modifier,
        )
    }
}

/** The secrets form and the editor are pages inside this tab, in that order of precedence. */
@Composable
private fun WorkflowsTab(
    state: WorkflowsUiState,
    model: WorkflowsViewModel,
    modifier: Modifier,
) {
    val secretsForm = state.secretsOpen
    val editor = state.editor
    when {
        secretsForm != null -> SecretsScreen(
            names = state.secrets,
            form = secretsForm,
            onName = model::secretName,
            onValue = model::secretValue,
            onGenerate = model::generateSecret,
            onSave = model::saveSecret,
            onDelete = model::deleteSecret,
            onClose = model::closeSecrets,
            modifier = modifier,
        )

        editor != null -> WorkflowEditorScreen(
            editor = editor,
            secrets = state.secrets,
            onEdit = model::update,
            onAddStep = model::addStep,
            onRemoveStep = model::removeStep,
            onMoveStep = model::moveStep,
            onOpenStep = model::openStep,
            onEditStep = model::updateStep,
            onNewSecret = model::openSecrets,
            onSave = model::save,
            onReopen = model::reopen,
            onCancel = model::cancel,
            modifier = modifier,
        )

        else -> WorkflowsScreen(
            state = state,
            onAdd = model::add,
            onOpen = { model.edit(it.id) },
            onSetDefault = model::setDeviceDefault,
            onConfirmDelete = model::confirmDelete,
            onDelete = model::delete,
            onSecrets = model::openSecrets,
            onDismissMessage = model::dismissMessage,
            modifier = modifier,
        )
    }
}

/** Two models: the account is [MainViewModel]'s, which already owns sign-in, and the rest is not. */
@Composable
private fun SettingsTab(
    main: MainUiState,
    settings: SettingsUiState,
    model: MainViewModel,
    settingsModel: SettingsViewModel,
    activity: Activity,
    onExportWorkflows: () -> Unit,
    onImportWorkflows: () -> Unit,
    modifier: Modifier,
) {
    SettingsScreen(
        main = main,
        settings = settings,
        onWifiOnly = settingsModel::setWifiOnly,
        onLanguage = settingsModel::setLanguage,
        onTheme = settingsModel::setTheme,
        onConsentReminder = settingsModel::setConsentReminder,
        onSignIn = { model.signIn(activity) },
        onSignOut = model::signOut,
        onAskToDisconnect = model::askToDisconnect,
        onCancelDisconnect = model::cancelDisconnect,
        onDisconnect = model::disconnect,
        onRevokeDebtSettled = model::revokeDebtSettled,
        onExportWorkflows = onExportWorkflows,
        onImportWorkflows = onImportWorkflows,
        onCancelImport = settingsModel::cancelImport,
        onConfirmImport = settingsModel::confirmImport,
        modifier = modifier,
    )
}
