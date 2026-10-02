package app.recly.windows.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okio.Path
import recly.core.platform.Logger
import recly.core.recording.RecordingRecord

/**
 * docs/09 screen principle 2: a recording made on this PC gets its waveform worked out once it is finalized,
 * in the background, so even its first open in the detail draws at once rather than decoding every
 * part while the user waits. A recording from another device gets its own the first time its audio
 * is fetched and opened (the detail keeps what it decodes).
 *
 * One recording at a time, behind everything else: it only reads the parts, so the upload and the
 * transcription go on beside it, and it gives way — the decode stopped, the recording skipped — the
 * moment anything raises the playback gate: a capture starting, or a delete or a disconnect that is
 * about to remove the very files it reads ([halt]).
 */
class WaveformPrecompute(
    scope: CoroutineScope,
    /** A thread of its own, off the core's dispatchers: a decode is minutes of blocking reads. */
    private val worker: CoroutineDispatcher,
    /** `core.recordings.get`. */
    private val load: suspend (String) -> RecordingRecord?,
    /** `core.recordings.waveform`. */
    private val kept: suspend (String) -> List<Float>?,
    /** `core.recordings.saveWaveform`. */
    private val keep: suspend (String, List<Float>) -> Unit,
    /** The detail's own decoder ([RecordingPlayer.decoder]). */
    private val spawn: (Path, Double) -> Process,
    private val exists: (Path) -> Boolean,
    /** Whether to give way now — the playback gate is up. */
    private val busy: () -> Boolean,
    private val logger: Logger,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)

    @Volatile private var halted = false

    @Volatile private var process: Process? = null

    init {
        scope.launch(worker) {
            for (recordingId in queue) {
                try {
                    precompute(recordingId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A part ffmpeg could not read: the detail decodes it again when it is opened.
                    logger.log(Logger.Level.WARN, "shell.waveform.precompute.failed", mapOf("recordingId" to recordingId), e)
                }
            }
        }
    }

    /** A recording that has just been finalized here, for its waveform to be worked out. */
    fun enqueue(recordingId: String) {
        queue.trySend(recordingId)
    }

    /**
     * Stops the decode that is reading parts now and waits for its ffmpeg to be gone — on Windows a
     * file that is open cannot be deleted. False when it outlived the wait.
     */
    fun halt(): Boolean {
        halted = true
        process?.destroyForcibly()
        val until = System.currentTimeMillis() + HALT_WAIT_MS
        while (process != null && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
        return process == null
    }

    private suspend fun precompute(recordingId: String) {
        // Before the reads, so a halt that lands during them stops the decode before it starts.
        halted = false
        if (busy()) return
        val record = load(recordingId) ?: return
        val selection = RecordingPlaylist.select(record.meta.parts, record.dir, exists)
        if (selection.isEmpty) return
        val windows = RecordingWaveform.windows(selection)
        if (kept(recordingId)?.size == windows) return
        val stopped = { halted || busy() }
        // On [worker], where every item runs: the blocking reads stay off the core's dispatchers.
        val peaks = RecordingWaveform.peaks(selection, spawn, cancelled = stopped, onProcess = { process = it })
        if (stopped() || peaks.size != windows) return
        keep(recordingId, peaks.toList())
        logger.log(Logger.Level.INFO, "shell.waveform.precompute.kept", mapOf("recordingId" to recordingId, "windows" to windows))
    }

    private companion object {
        const val HALT_WAIT_MS = 2_000L
        const val POLL_MS = 10L
    }
}
