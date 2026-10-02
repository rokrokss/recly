package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.helper.NetworkCost
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.coreMessage
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.processing.TranscriptionMode
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus

/**
 * docs/05 "Fixed processing settings": the one download of the on-device speech model in this process.
 * Settings, the banner, a waiting row and the first-run card all read this and start or cancel
 * through it, so two surfaces never start two downloads. The download is always
 * `ReclyCore.prepareLocalEngine`, which is also what carries on the recordings waiting for the model.
 *
 * It runs in the app process — the tray app keeps running — and asks the capture helper first
 * whether the connection is metered: a metered one is a question ([meteredPrompt]), an unknown one is
 * not.
 */
class ModelDownload(
    private val scope: CoroutineScope,
    /** `ReclyCore.localEngineInfo`: the engine's reading, with the size and what is already on disk. */
    private val read: suspend (language: String) -> LocalEngineInfo,
    /** `ReclyCore.prepareLocalEngine`: the download, and the recordings it releases. */
    private val prepare: suspend (language: String) -> LocalEngineInfo,
    private val networkCost: suspend () -> NetworkCost,
    /** The model is here: the released recordings are due now, and settings reads the engine again. */
    private val onPrepared: () -> Unit,
    private val pollMs: Long = POLL_MS,
) {
    /** The engine's last reading — status, size, the share already here, whether it is downloading. */
    var info: LocalEngineInfo? by mutableStateOf(null)
        private set

    /** A download is running in this process. */
    var running: Boolean by mutableStateOf(false)
        private set

    /** Why the last download stopped short, until the next one starts. A cancel is not a failure. */
    var failure: UiMessage? by mutableStateOf(null)
        private set

    /** The language a start is waiting to download in, while the metered question is up. */
    var meteredPrompt: String? by mutableStateOf(null)
        private set

    /** The saved settings' language: what [info] is read for, and what a start without one downloads. */
    @Volatile private var language: String? = null

    private var job: Job? = null

    fun track(language: String) {
        this.language = language
        scope.launch { refresh() }
    }

    /** Reads the engine again. A reading that fails leaves the last one standing. */
    suspend fun refresh() {
        language?.let { reread(it) }
    }

    /**
     * The waiting recording's own language when started from its row, and otherwise the saved
     * settings' one. Nothing while a download is running or the metered question is up.
     */
    @Synchronized
    fun start(language: String? = null) {
        val target = language ?: this.language ?: return
        if (job?.isActive == true || meteredPrompt != null) return
        failure = null
        job = launchJob {
            if (networkCost() == NetworkCost.METERED) meteredPrompt = target else download(target)
        }
    }

    /** The metered question's Download. */
    @Synchronized
    fun confirmMetered() {
        val target = meteredPrompt ?: return
        meteredPrompt = null
        if (job?.isActive == true) return
        job = launchJob { download(target) }
    }

    /** The metered question's Cancel: nothing is downloaded. */
    fun dismissMetered() {
        meteredPrompt = null
    }

    /** Stops the download. What is already on disk stays, so the next start resumes from it. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * [running] clears when the job has completed, not in [download]'s `finally`: the job is still
     * active while its children wind down, and a start in that gap — the surfaces offer it as soon
     * as the flag clears — would be dropped by [start]'s own guard.
     */
    private fun launchJob(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block).also { it.invokeOnCompletion { running = false } }

    private suspend fun download(language: String) = coroutineScope<Unit> {
        running = true
        // The engine counts what is on disk, so the percentage is read back about twice a second.
        val poll = launch {
            while (true) {
                reread(this@ModelDownload.language ?: language)
                delay(pollMs)
            }
        }
        try {
            prepare(language)
            onPrepared()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cancel can surface as the transport's own error; it is still a cancel.
            ensureActive()
            failure = coreMessage(CoreMessage.STEP_FAILED, e.message ?: e::class.simpleName.orEmpty())
        } finally {
            poll.cancel()
            // The last reading before the flag clears: a surface that sees it stop sees where it stopped.
            withContext(NonCancellable) { reread(this@ModelDownload.language ?: language) }
        }
    }

    private suspend fun reread(language: String) {
        runCatching { read(language) }.onSuccess { info = it }
    }

    companion object {
        const val POLL_MS = 500L
    }
}

/**
 * The first-run card: only when the model is actually missing — on-device transcription saved, an
 * engine this PC can run, its model not here, "Not now" not said, and no capture running. The engine
 * reads `READY` once the download is done, and the card goes with it.
 *
 * And only while nothing is [waiting] for the model yet: from the first recording that waits, the
 * banner — with its count — is the one prompt, so the two never ask the same thing at once.
 */
fun showsModelCard(
    mode: TranscriptionMode?,
    installed: Boolean,
    status: LocalEngineStatus?,
    dismissed: Boolean,
    capturing: Boolean,
    waiting: Boolean,
): Boolean = mode == TranscriptionMode.LOCAL && installed && status == LocalEngineStatus.MODEL_REQUIRED &&
    !dismissed && !capturing && !waiting

/**
 * A recording waiting for the model offers the download as its first action — but not while one
 * runs: the banner above carries the progress then, and a row that repeated it would be a third copy.
 */
fun rowOffersDownload(running: Boolean): Boolean = !running

/** "Resume download" once part of the model is here, "Download model" before any of it is. */
fun downloadLabel(info: LocalEngineInfo?): Str =
    if (info?.progress != null) Str.PROCESSING_MODEL_RESUME else Str.PROCESSING_PREPARE

/** "Downloading model… 42%": rounded down, so 100 is never said before the download is over. */
fun downloadingText(strings: Strings, progress: Double?): String =
    strings[Str.PROCESSING_MODEL_DOWNLOADING, ((progress ?: 0.0) * 100).toInt().coerceIn(0, 100)]

/** "412 MB of 988 MB" while the model is partly here; null when there is no share to state. */
fun downloadedBytesText(strings: Strings, info: LocalEngineInfo?): String? {
    val total = info?.modelBytes ?: return null
    val progress = info.progress ?: return null
    val locale = Locale.forLanguageTag(strings.language)
    return strings[
        Str.PROCESSING_MODEL_BYTES,
        ByteFormat.format((total * progress).toLong(), locale),
        ByteFormat.format(total, locale),
    ]
}

/** A spoken language as the engine takes it — the core's own `Language.wire` is internal to it. */
fun engineLanguage(language: Language): String = language.name.lowercase(Locale.ROOT).replace('_', '-')

/**
 * A model size, written the same way on every shell: decimal units, whole megabytes under 1,000 MB
 * ("988 MB", "412 MB") and gigabytes with one decimal from there ("1.2 GB"), a space before the unit.
 */
object ByteFormat {
    fun format(bytes: Long, locale: Locale): String {
        val megabytes = Math.round(bytes.coerceAtLeast(0) / 1e6)
        // Rounded first, so 999.6 MB is "1.0 GB" and never "1000 MB".
        return if (megabytes < 1_000) {
            "${String.format(locale, "%d", megabytes)} MB"
        } else {
            "${String.format(locale, "%.1f", bytes / 1e9)} GB"
        }
    }
}
