package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.auth.OAuthConfig
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.settings.Settings
import app.recly.windows.update.AvailableUpdate
import app.recly.windows.update.UpdateException
import app.recly.windows.update.UpdateFailure
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recly.core.platform.Logger

/** docs/14 "Updates": where the Updates row in Settings, and the popup's banner, stand. */
sealed interface UpdateState {
    /** Nothing checked yet: the row names the version this is. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState

    /** The MSI is coming down or being verified; the app quits into the installer once it is. */
    data class Installing(val update: AvailableUpdate) : UpdateState
}

/**
 * docs/14 "Updates": the one updater of an installed copy. It looks at the signed feed by itself about ten seconds
 * after launch and every six hours after while the setting is on, and when asked from Settings; a look it made by
 * itself that fails is only logged. The setting is off until the user has answered the question the popup asks once
 * ([asks]): the check is a new way for something to leave the PC, so it is not made by itself before a yes. It installs only when the user asks, and only while [idle] — nothing recording,
 * starting or being saved — so no recording is ever lost to it: the MSI is fetched and verified, the installer is
 * started, and [quit] takes the same way out the tray's Quit does.
 *
 * [checkFeed], [download] and [launch] are [app.recly.windows.update.UpdateFeed], `UpdateDownload` and `UpdateInstaller`
 * off the UI thread; they throw [UpdateException].
 */
class UpdateViewModel(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val logger: Logger,
    private val checkFeed: suspend () -> AvailableUpdate?,
    private val download: suspend (AvailableUpdate) -> Path,
    private val launch: suspend (Path) -> Unit,
    private val idle: () -> Boolean,
    private val quit: () -> Unit,
    private val firstCheckMs: Long = FIRST_CHECK_MS,
    private val intervalMs: Long = INTERVAL_MS,
) {
    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    /** Why the last check the user asked for, or the last install, stopped — until the next one starts. */
    var failure: UpdateFailure? by mutableStateOf(null)
        private set

    /** "Check for updates automatically", this PC's own: off until the user has answered ([asks]). */
    var automatic: Boolean by mutableStateOf(settings.autoUpdateCheck == true)
        private set

    /** The popup asks once whether to check by itself; the Settings switch is an answer too. */
    var asks: Boolean by mutableStateOf(settings.autoUpdateCheck == null)
        private set

    /** A check is running. Not the job's own `isActive`, which outlives [finish] by a moment. */
    private var checking = false

    /** The running check is one the user asked for: it shows, and so does its failure. */
    private var shown = false

    /** What the row said before the check the user asked for, which it says again if the check fails. */
    private var before: UpdateState = UpdateState.Idle

    /** The automatic checks, for as long as the app runs. */
    fun start(): Job = scope.launch {
        delay(firstCheckMs)
        while (true) {
            if (automatic) check(manual = false)
            delay(intervalMs)
        }
    }

    /** The popup's answer or the Settings switch. A yes looks at once rather than at the next six-hourly turn. */
    fun answer(yes: Boolean) {
        if (!asks && automatic == yes) return
        settings.autoUpdateCheck = yes
        automatic = yes
        asks = false
        logger.log(Logger.Level.INFO, "shell.update.automatic", mapOf("on" to yes))
        if (yes) check(manual = false)
    }

    /**
     * Looks at the feed. One the user asks for while an automatic one is running takes that one over rather than
     * starting a second. An automatic check changes nothing on screen until it has an answer, so an offer already on
     * screen stays up while it runs.
     */
    @Synchronized
    fun check(manual: Boolean = true) {
        if (state is UpdateState.Installing) return
        if (manual) {
            if (state != UpdateState.Checking) before = state
            state = UpdateState.Checking
            failure = null
            shown = true
        }
        if (checking) return
        checking = true
        scope.launch {
            val result = try {
                Result.success(checkFeed())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            finish(result)
        }
    }

    @Synchronized
    private fun finish(result: Result<AvailableUpdate?>) {
        val manual = shown
        checking = false
        shown = false
        result.onSuccess { update ->
            logger.log(
                Logger.Level.INFO,
                "shell.update.check",
                if (update == null) {
                    mapOf("outcome" to "up_to_date", "manual" to manual)
                } else {
                    mapOf("outcome" to "available", "version" to update.version, "installerVersion" to update.installerVersion, "manual" to manual)
                },
            )
            if (state is UpdateState.Installing) return
            val next = update?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            // The same answer again leaves a failed install's reason where it is.
            if (manual || next != state) {
                state = next
                failure = null
            }
        }.onFailure { error ->
            logger.log(
                Logger.Level.WARN,
                "shell.update.check",
                mapOf("outcome" to "failed", "reason" to UpdateFailure.CHECK.reason, "detail" to detail(error), "manual" to manual),
            )
            if (manual && state == UpdateState.Checking) {
                state = before
                failure = UpdateFailure.CHECK
            }
        }
    }

    /**
     * Restart and install: the offer on screen, and only while [idle]. A recording started while the MSI comes down
     * keeps it from being installed — the offer is back, and a later click finds the verified MSI where it was left.
     */
    @Synchronized
    fun install() {
        val update = (state as? UpdateState.Available)?.update ?: return
        if (!idle()) return
        state = UpdateState.Installing(update)
        failure = null
        logger.log(Logger.Level.INFO, "shell.update.install", mapOf("version" to update.version, "installerVersion" to update.installerVersion))
        scope.launch {
            val msi = attempt(update, PHASE_DOWNLOAD, UpdateFailure.DOWNLOAD) { download(update) } ?: return@launch
            synchronized(this@UpdateViewModel) {
                if (!idle()) {
                    state = UpdateState.Available(update)
                    return@launch
                }
            }
            attempt(update, PHASE_LAUNCH, UpdateFailure.INSTALL) { launch(msi) } ?: return@launch
            quit()
        }
    }

    /** [block], or null once its failure is logged and on screen; one that is not an [UpdateException] is [otherwise]. */
    private suspend fun <T : Any> attempt(update: AvailableUpdate, phase: String, otherwise: UpdateFailure, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = (e as? UpdateException)?.failure ?: otherwise
        // The detail and the cause's kind, never the message: a file system's names the user's folders.
        logger.log(
            Logger.Level.ERROR,
            "shell.update.fail",
            mapOf("phase" to phase, "reason" to reason.reason, "detail" to detail(e), "cause" to e.cause?.let { it::class.simpleName }),
        )
        synchronized(this) {
            state = UpdateState.Available(update)
            failure = reason
        }
        null
    }

    private fun detail(error: Throwable): String? = (error as? UpdateException)?.detail ?: error::class.simpleName

    companion object {
        const val FIRST_CHECK_MS = 10_000L
        const val INTERVAL_MS = 6 * 60 * 60 * 1_000L
        private const val PHASE_DOWNLOAD = "download"
        private const val PHASE_LAUNCH = "launch"
    }
}

/** What the Updates row in Settings shows next to its title: the way to act, or the loader while it works. */
enum class UpdateAction { CHECK, CHECKING, INSTALL, INSTALLING }

/**
 * docs/14 "Updates": the row's title — the news — its action, the failure under it, and whether it is [waiting] for
 * a recording to be saved before it can install. The popup's banner is the same row while there is an update ([offers]).
 */
data class UpdateRow(val title: UiMessage, val action: UpdateAction, val failure: Str?, val waiting: Boolean) {
    val offers: Boolean get() = action == UpdateAction.INSTALL || action == UpdateAction.INSTALLING
}

fun updateRow(state: UpdateState, failure: UpdateFailure?, idle: Boolean, appVersion: String = OAuthConfig.APP_VERSION): UpdateRow =
    when (state) {
        UpdateState.Idle -> UpdateRow(Str.UPDATE_CURRENT.message(appVersion), UpdateAction.CHECK, failure?.line, waiting = false)
        UpdateState.Checking -> UpdateRow(Str.UPDATE_CURRENT.message(appVersion), UpdateAction.CHECKING, null, waiting = false)
        UpdateState.UpToDate -> UpdateRow(Str.UPDATE_UP_TO_DATE.message(), UpdateAction.CHECK, failure?.line, waiting = false)
        is UpdateState.Available ->
            UpdateRow(Str.UPDATE_AVAILABLE.message(state.update.version), UpdateAction.INSTALL, failure?.line, waiting = !idle)
        is UpdateState.Installing ->
            UpdateRow(Str.UPDATE_AVAILABLE.message(state.update.version), UpdateAction.INSTALLING, null, waiting = false)
    }

/** The sentence for a check or an install that stopped. */
val UpdateFailure.line: Str
    get() = when (this) {
        UpdateFailure.CHECK -> Str.UPDATE_CHECK_FAILED
        UpdateFailure.DOWNLOAD -> Str.UPDATE_DOWNLOAD_FAILED
        UpdateFailure.SIGNATURE -> Str.UPDATE_SIGNATURE_FAILED
        UpdateFailure.INSTALL -> Str.UPDATE_INSTALL_FAILED
    }
