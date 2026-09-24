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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.imePadding
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
 * What the phone app is: record, watch the queue, and the settings — the account and what happens
 * to a recording (docs/11 A3·A4·A10).
 */
private enum class Tab(@param:StringRes val label: Int, val glyph: NavGlyph) {
    RECORD(R.string.tab_record, NavGlyph.RECORD),
    JOBS(R.string.tab_jobs, NavGlyph.LIST),
    SETTINGS(R.string.tab_settings, NavGlyph.SETTINGS),
}

class MainActivity : ComponentActivity() {

    private val model: MainViewModel by viewModels()
    private val recordingModel: RecordingViewModel by viewModels()
    private val jobsModel: JobsViewModel by viewModels()
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
     * docs/10: which fix screen a tapped job notification asked for, until the composition has
     * taken it. It is state and not a flag on the intent for the same reason as the auto-start —
     * the intent is redelivered on every recreation and the tap happened once.
     */
    private val fixRequest = mutableStateOf<AlertReason?>(null)

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
            val dark = settings.theme.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
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
                var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
                // docs/11 A9: a tile, widget or shortcut tap is a request to record *now*, so it
                // brings the Record tab with it. The tab is remembered across a restore, and a
                // request left lying behind another tab used to be spent on whatever switch to
                // Record came next — a recording the user had asked for minutes or hours earlier.
                LaunchedEffect(recording.autoStart) {
                    if (recording.autoStart != null) tab = Tab.RECORD
                }
                LaunchedEffect(tab) {
                    // Leaving the Record tab forgets an auto-start the user has navigated away from —
                    // it must not start a recording behind another screen later.
                    if (tab != Tab.RECORD) recordingModel.dropAutoStart()
                }

                // docs/10 "탭하면 고칠 수 있는 화면으로 간다": the one mapping, shared by the list's
                // banner and by the notification that says the same thing.
                // docs/06 Android: signed in, the fix is the Drive consent itself, right here;
                // signed out, it is the sign-in button, which lives in Settings.
                val fixAuth = {
                    if (state.email == null) tab = Tab.SETTINGS else model.reauthorizeDrive(this)
                }
                val goFix: (AlertReason) -> Unit = { reason ->
                    when (reason.fix) {
                        FixSurface.SIGN_IN -> fixAuth()
                        FixSurface.DRIVE_STORAGE -> startActivity(
                            Intent(Intent.ACTION_VIEW, DRIVE_STORAGE_URL.toUri()),
                        )

                        FixSurface.SECRETS, FixSurface.PROCESSING -> { tab = Tab.SETTINGS }
                    }
                }

                LaunchedEffect(fixRequest.value) {
                    val reason = fixRequest.value ?: return@LaunchedEffect
                    fixRequest.value = null
                    goFix(reason)
                }
                BackHandler(enabled = tab != Tab.RECORD) { tab = Tab.RECORD }


                // The detail screen is a page inside the jobs tab, so Back has to leave it.
                BackHandler(enabled = tab == Tab.JOBS && jobs.detail != null) { jobsModel.closeDetail() }

                val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Scaffold(
                    modifier = Modifier.dotGrid(blueprint).imePadding(),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (!keyboardVisible) BlueprintNavBar(
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
                        Tab.RECORD -> RecordTab(recording, recorder, jobs, recordingModel, content, onOpenProcessing = { tab = Tab.SETTINGS })

                        Tab.JOBS -> JobsTab(
                            state = jobs,
                            model = jobsModel,
                            onRecord = { tab = Tab.RECORD },
                            // docs/08 AUTH_REJECTED: the key is kept in the processing settings, so
                            // that is where "check the key" has to land.
                            onCheckKey = { tab = Tab.SETTINGS },
                            onFix = { alert -> goFix(alert.reason) },
                            modifier = content,
                        )

                        Tab.SETTINGS -> SettingsTab(
                            main = state,
                            settings = settings,
                            model = model,
                            settingsModel = settingsModel,
                            activity = this@MainActivity,
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
        intent.removeExtra(EXTRA_FIX)
        fixRequest.value = AlertReason.entries.firstOrNull { it.name == name }
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
    }
}

@Composable
private fun RecordTab(
    state: RecordingUiState,
    recorder: RecorderState,
    jobs: JobsUiState,
    model: RecordingViewModel,
    modifier: Modifier,
    onOpenProcessing: () -> Unit,
) {
    RecordingSection(
        state = state,
        recorder = recorder,
        // docs/09 화면 원칙 1: the state node borrows the ledger while the recorder is idle, and the
        // ledger is the same live list the jobs tab draws.
        ledger = ledgerCode(jobs.items),
        onOpenProcessing = onOpenProcessing,
        onStart = model::start,
        onStop = model::stop,
        onMicDenied = model::micDenied,
        onMicGranted = model::micGranted,
        onConsumeAutoStart = model::consumeAutoStart,
        onSaveTitle = model::saveTitle,
        onCancelTitle = model::cancelTitle,
        onConsentAnswered = model::consentAnswered,
        modifier = modifier,
    )
}

/** The detail screen is a page inside this tab, not a tab of its own — see the [BackHandler]. */
@Composable
private fun JobsTab(
    state: JobsUiState,
    model: JobsViewModel,
    onRecord: () -> Unit,
    onCheckKey: () -> Unit,
    onFix: (JobAlert) -> Unit,
    modifier: Modifier,
) {
    val detail = state.detail
    if (detail != null) {
        RecordingDetailScreen(
            detail = detail,
            onClose = model::closeDetail,
            onRename = { title -> model.rename(detail.recordingId, title) },
            onReload = model::reloadDetail,
            modifier = modifier,
        )
    } else {
        JobsScreen(
            state = state,
            onRetry = model::retry,
            onConfirmDelete = model::confirmDelete,
            onCancelDelete = model::cancelDelete,
            onDelete = model::delete,
            onRecord = onRecord,
            onOpenDetail = model::openDetail,
            onCheckKey = { onCheckKey() },
            onFix = onFix,
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
        onAskToDisconnect = model::askToDisconnect,
        onCancelDisconnect = model::cancelDisconnect,
        onDisconnect = model::disconnect,
        onRevokeDebtSettled = model::revokeDebtSettled,
        modifier = modifier,
    )
}
