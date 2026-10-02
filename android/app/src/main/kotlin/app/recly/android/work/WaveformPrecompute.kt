package app.recly.android.work

import android.os.Process
import app.recly.android.ui.RecordingPlaylist
import app.recly.android.ui.RecordingWaveform
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import recly.core.ReclyCore
import recly.core.model.RecordingStatus
import recly.core.platform.Logger

/**
 * docs/09 screen principle 2: a recording's waveform, decoded once it is whole — a take this phone just
 * finalized, or one the watch just finished handing over — and kept beside its parts
 * (`core.recordings.saveWaveform`), so that even its first open draws at once. What another device
 * recorded gets its own the first time its audio comes back and is opened.
 *
 * One recording at a time, on one low-priority thread, and never while the microphone is taken: a
 * capture that starts stops the decode where it is, and that recording is left to its first open.
 * It only reads the parts, so the upload and the transcription never wait on it.
 */
object WaveformPrecompute {

    private val background = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "waveform-precompute")
    }.asCoroutineDispatcher()

    private class Request(val core: ReclyCore, val recordingId: String)

    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + background).launch {
            for (request in requests) run(request.core, request.recordingId)
        }
    }

    /** Queues [recordingId]; returns at once. */
    fun request(core: ReclyCore, recordingId: String) {
        requests.trySend(Request(core, recordingId))
    }

    private suspend fun run(core: ReclyCore, recordingId: String) {
        try {
            val done = WaveformPrecomputer(
                selection = { id ->
                    core.recordings.get(id)?.takeIf { it.meta.status != RecordingStatus.RECORDING }?.let { record ->
                        RecordingPlaylist.select(record.meta.parts, record.dir) { core.deps.fileSystem.exists(it) }
                    }
                },
                cached = { id -> core.recordings.waveform(id) },
                save = { id, peaks -> core.recordings.saveWaveform(id, peaks.asList()) },
                decode = { audio -> RecordingWaveform.peaks(audio, dispatcher = background) },
                capturing = RecorderService.state.map { it != RecorderState.Idle },
            ).run(recordingId)
            core.deps.logger.log(
                Logger.Level.INFO,
                if (done) "waveform.precomputed" else "waveform.precompute.skipped",
                mapOf("recordingId" to recordingId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            core.deps.logger.log(Logger.Level.WARN, "waveform.precompute.failed", mapOf("recordingId" to recordingId), e)
        }
    }
}

/**
 * [WaveformPrecompute]'s decision, with the core, the decoder and the recorder handed in so a JVM
 * test can stand in for them.
 *
 * @param selection the recording's local parts, or null when there is nothing whole to decode.
 * @param capturing whether the microphone is taken right now.
 */
internal class WaveformPrecomputer(
    private val selection: suspend (String) -> RecordingPlaylist.Selection?,
    private val cached: suspend (String) -> List<Float>?,
    private val save: suspend (String, FloatArray) -> Unit,
    private val decode: suspend (RecordingPlaylist.Selection) -> FloatArray,
    private val capturing: Flow<Boolean>,
) {
    /** Whether the recording's peaks are kept now — false for nothing to decode, or a capture in the way. */
    suspend fun run(recordingId: String): Boolean {
        capturing.first { !it }
        val audio = selection(recordingId)?.takeIf { !it.isEmpty } ?: return false
        return unlessCapture {
            RecordingWaveform.load(
                expected = RecordingWaveform.windows(audio.durations),
                cached = { cached(recordingId) },
                decode = { decode(audio) },
                save = { save(recordingId, it) },
            )
        } != null
    }

    /**
     * [block], or null when a capture starts before it is done. The watch runs off the decode's own
     * thread, which does not come up for air between buffers.
     */
    private suspend fun <T> unlessCapture(block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val watch = launch(Dispatchers.Default) {
            capturing.first { it }
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            ensureActive()
            null
        } finally {
            watch.cancel()
        }
    }
}
