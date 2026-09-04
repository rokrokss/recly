@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import recly.core.ReclyCore
import recly.core.ids.Ulid
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Range
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import kotlin.time.Clock as TimeClock

/** What [SegmentedRecorder.stop] hands back once the recording is on disk and finalized. */
data class RecordingOutcome(
    val recordingId: String,
    val durationSec: Double,
    val parts: Int,
    val silenced: List<Range>,
)

/**
 * One recording as a chain of `.m4a` segments (ADR-006). `MediaRecorder` only writes the MPEG-4
 * header at `stop()`, so an unsegmented three-hour recording is three hours lost to one crash;
 * segments cap the loss at one boundary and keep every part under the 25 MB transcription limit.
 *
 * The boundary itself is gapless because the platform, not this class, switches the file:
 * `setNextOutputFile` is armed one segment ahead and `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`
 * says the previous one is closed and safe to hash. Tearing the recorder down and building a new
 * one would cost a `gaps` entry every fifteen minutes.
 *
 * Not thread-safe by accident: `start`/`stop` and the segment callback all mutate the same session
 * under [mutex], because the callback arrives on the platform's thread and the caller's is a
 * coroutine.
 */
class SegmentedRecorder(
    private val context: Context,
    private val core: ReclyCore,
    private val scope: CoroutineScope,
    private val segmentSec: Int = DEFAULT_SEGMENT_SEC,
    private val source: Source = Source.PHONE,
    private val onError: (RecorderError) -> Unit,
) : Capture {
    private class Session(
        val recorder: MediaRecorder,
        val recordingId: String,
        val startedAt: Instant,
        val dir: Path,
        val ledger: SegmentLedger,
        val closer: SegmentCloser,
        val timer: SegmentTimer,
        val silence: SilenceMonitor,
    ) {
        /** The highest part number handed to `setNextOutputFile`; part 1 is the initial output. */
        var armedPart: Int = 1
    }

    private val mutex = Mutex()
    private var session: Session? = null

    val isRecording: Boolean get() = session != null

    /**
     * The loudest sample since this was last asked, 0..1 — `MediaRecorder` counts the peak for us,
     * so asking every 0.1 s *is* the tenth-of-a-second window the live strip draws (docs/09 화면
     * 원칙 6). Null when there is nothing recording, and when the platform recorder refuses to
     * answer — it throws while it is between segments or on its way down, and a window nobody can
     * read is not a silent one.
     *
     * Outside [mutex] on purpose: this is asked ten times a second from the screen's own loop, and
     * it may never wait behind a segment boundary.
     */
    fun peakSinceLastAsk(): Float? = try {
        session?.recorder?.maxAmplitude?.let { it / MAX_AMPLITUDE }
    } catch (_: IllegalStateException) {
        null
    }

    /**
     * Creates the recording row and `meta.json`, then starts the first segment. Returns the id the
     * rest of the pipeline uses; the caller keeps it to `stop` and to enqueue.
     *
     * The recorder starts before the row exists so a device that cannot do 16 kHz is discovered
     * first — the fallback rate has to be the one written into the meta (ADR-006).
     */
    suspend fun start(workflowId: String?, title: String?): String = mutex.withLock {
        check(session == null) { "already recording" }

        val startedAt = core.deps.clock.now()
        // The id's own timestamp is the recording's start, not "whenever this ran".
        val recordingId = Ulid.generate(object : TimeClock { override fun now(): Instant = startedAt })
        val draft = meta(recordingId, startedAt, workflowId, title, PREFERRED_SAMPLE_RATE_HZ)
        val base = MetaWriter.baseName(draft)
        val dir = core.deps.dataDir / "recordings" / base
        withContext(core.deps.io) { core.deps.fileSystem.createDirectories(dir) }

        val ledger = SegmentLedger(base)
        val started = startRecorder(File(dir.toFile(), ledger.openFileName()))

        // Past this point the microphone is live and files exist: anything that throws has to give
        // both back, or the next start finds a half-open recording and a recorder nobody owns.
        val silence = SilenceMonitor { SystemClock.elapsedRealtime().minus(started.startedElapsedMs) / 1000.0 }
        try {
            silence.start(audioManager())
            core.recordings.create(
                meta(recordingId, startedAt, workflowId, title, started.sampleRateHz),
                dir,
            )
            session = Session(
                recorder = started.recorder,
                recordingId = recordingId,
                startedAt = startedAt,
                dir = dir,
                ledger = ledger,
                closer = SegmentCloser(core.deps.fileSystem, dir, ledger, BYTES_PER_SEC),
                timer = SegmentTimer { SystemClock.elapsedRealtime() },
                silence = silence,
            ).also { armNext(it) }
        } catch (e: Exception) {
            abandon(started.recorder, silence, recordingId, dir, e)
            throw RecorderError("could not open the recording", e)
        }

        core.deps.logger.log(
            Logger.Level.INFO,
            "rec.recorder.start",
            mapOf("recordingId" to recordingId, "sampleRateHz" to started.sampleRateHz, "segmentSec" to segmentSec),
        )
        recordingId
    }

    /**
     * Stops the platform recorder, drains every segment file it may have opened and closes the
     * meta. [StopResult.NotRecording] for a second stop — the button and the notification action
     * can both land, and neither finalize nor enqueue may happen twice.
     *
     * The microphone is given back before any of the bookkeeping, so a recording that cannot be
     * filed still stops being a recording.
     */
    override suspend fun stop(title: String?): StopResult = mutex.withLock {
        val open = session ?: return@withLock StopResult.NotRecording
        session = null

        val silenced = open.silence.stop(audioManager())
        // Read the clock before stopping: it is the fallback length of the segment being closed.
        val hintSec = open.timer.advance()
        runCatching { open.recorder.stop() }
        runCatching { open.recorder.release() }

        // Up to `armedPart`, not just the open one: a boundary that fired between the last callback
        // and this stop leaves real audio in the file that was armed. `drain` stops at the first
        // empty file and deletes it, which is the usual fate of the arming.
        runCatching {
            open.closer.drain(open.armedPart, hintSec) { part ->
                registerPart(open, part)
            }
        }.onFailure { onError(RecorderError("could not close the last segment", it, fatal = false)) }

        // Retries anything a boundary could not file, and refuses to finalize while any of it is
        // still unfiled: once the row says `finalized` nothing looks at the directory again, and
        // the part would be uploaded away.
        PartReconciler(core).closeOut(open.recordingId, open.ledger.recordedSec, title, silenced)
    }

    private class Started(val recorder: MediaRecorder, val sampleRateHz: Int, val startedElapsedMs: Long)

    /** 16 kHz first (ADR-006); a device that refuses it records at 44.1 kHz and says so in the meta. */
    private suspend fun startRecorder(first: File): Started = withContext(core.deps.io) {
        for (rate in SAMPLE_RATES_HZ) {
            // Only prepare/start can reject a rate; a configuration error is not something another
            // rate fixes, so it is not retried behind a misleading "fallback".
            val recorder = runCatching { configure(first, rate) }
                .getOrElse { throw RecorderError("could not configure the recorder", it) }
            try {
                recorder.prepare()
                recorder.start()
                return@withContext Started(recorder, rate, SystemClock.elapsedRealtime())
            } catch (e: Exception) {
                runCatching { recorder.release() }
                first.delete()
                if (rate == SAMPLE_RATES_HZ.last()) throw RecorderError("start failed at $rate Hz", e)
                core.deps.logger.log(
                    Logger.Level.WARN,
                    "rec.recorder.sampleRateFallback",
                    mapOf("rejected" to rate, "reason" to (e.message ?: e::class.simpleName.orEmpty())),
                )
            }
        }
        error("unreachable")
    }

    /** Gives back the microphone and leaves no half-open recording behind (docs/lanes M2-L2). */
    private suspend fun abandon(
        recorder: MediaRecorder,
        silence: SilenceMonitor,
        recordingId: String,
        dir: Path,
        cause: Exception,
    ) {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        runCatching { silence.stop(audioManager()) }
        // Drops the row and the directory together; the directory alone if the row never landed.
        runCatching { core.recordings.delete(recordingId) }
        runCatching { withContext(core.deps.io) { core.deps.fileSystem.deleteRecursively(dir, mustExist = false) } }
        core.deps.logger.log(
            Logger.Level.ERROR,
            "rec.recorder.startAborted",
            mapOf("recordingId" to recordingId),
            cause,
        )
    }

    private fun configure(first: File, sampleRateHz: Int): MediaRecorder =
        MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            // Keeps a concurrent capture (an assistant, a call screener) from silently sharing the
            // microphone with us — the recording is the user's, not the platform's. It has to sit
            // here: after `setOutputFormat` the platform throws `IllegalStateException` (API 36).
            setPrivacySensitive(true)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(sampleRateHz)
            setAudioChannels(1)
            setAudioEncodingBitRate(BITRATE_KBPS * 1000)
            // Not setMaxDuration: the platform writer switches to the armed next file when the
            // *size* limit is hit and simply stops the recorder when the *duration* limit is
            // (verified on API 36 — `MEDIA_RECORDER_INFO_MAX_DURATION_REACHED` with the next file
            // armed and untouched). Only the size limit keeps the boundary lossless (ADR-006), so
            // the segment length is expressed as the bytes a segment's worth of audio takes.
            setMaxFileSize(segmentBytes())
            setOutputFile(first)
            setOnInfoListener { _, what, _ -> onInfo(what) }
            setOnErrorListener { _, what, extra ->
                onError(RecorderError("MediaRecorder error what=$what extra=$extra"))
            }
        }

    /**
     * ADR-006's 900 s at 32 kbps, plus what MPEG-4 framing costs on top of the audio — measured at
     * 3-7% for AAC-LC 16 kHz mono on API 36, and the limit counts the container, not the audio.
     * The result is a segment within a few percent of [segmentSec], which is what the meta reports;
     * the exact length of each part is in the part itself.
     */
    private fun segmentBytes(): Long =
        segmentSec.toLong() * BYTES_PER_SEC * CONTAINER_OVERHEAD_PERCENT / 100

    private fun onInfo(what: Int) {
        when (what) {
            // The previous file is closed and complete: hash it, register it, arm the one after next.
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                // Read before anything suspends: the coroutine may not run for a while, and this is
                // where the segment being closed actually ended.
                val boundaryMs = SystemClock.elapsedRealtime()
                scope.launch { closeBoundary(boundaryMs) }
            }

            // The limit was hit with no next file to switch into: the recorder has stopped itself
            // and what is on disk is all there is.
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
            -> onError(RecorderError("recorder stopped at its limit (what=$what)"))
        }
    }

    /**
     * Nothing in here may throw out: this runs on the recorder's own coroutine and an escape would
     * reach the uncaught handler and take the process — and the recording — with it. A segment that
     * cannot be filed is marked and left for [RecordingRecovery]; the encoder keeps running.
     */
    private suspend fun closeBoundary(boundaryMs: Long) {
        try {
            mutex.withLock {
                val open = session ?: return@withLock
                armNext(open)
                val hintSec = open.timer.advance(boundaryMs)
                open.closer.drain(open.ledger.openPart, hintSec) { part -> registerPart(open, part) }
            }
        } catch (e: Throwable) {
            onError(RecorderError("segment boundary failed", e, fatal = false))
        }
    }

    /**
     * A part that cannot reach the database is not a lost part: the audio is on disk and a sidecar
     * says so, so the next start files it (docs/03 "크래시 시 마지막 경계까지는 복구 가능").
     */
    private suspend fun registerPart(open: Session, part: Part) {
        try {
            core.recordings.addPart(open.recordingId, part)
        } catch (e: Exception) {
            runCatching {
                withContext(core.deps.io) {
                    core.deps.fileSystem.write(open.dir / "${part.file}$PENDING_SUFFIX") { writeUtf8("") }
                }
            }
            onError(RecorderError("could not register part ${part.part}", e, fatal = false))
        }
    }

    private fun armNext(open: Session) {
        val part = open.armedPart + 1
        try {
            open.recorder.setNextOutputFile(File(open.dir.toFile(), open.ledger.fileName(part)))
            open.armedPart = part
        } catch (e: Exception) {
            onError(RecorderError("could not arm segment $part", e))
        }
    }

    private fun meta(
        recordingId: String,
        startedAt: Instant,
        workflowId: String?,
        title: String?,
        sampleRateHz: Int,
    ): RecordingMeta = RecordingMeta(
        schema = 1,
        recordingId = recordingId,
        source = source,
        platform = core.deps.device.platform,
        deviceId = core.deps.device.deviceId,
        deviceName = core.deps.device.name,
        workflowId = workflowId,
        title = title,
        startedAt = startedAt.isoUtc(),
        timezone = java.util.TimeZone.getDefault().id,
        audio = AudioSettings(
            codec = Codec.AAC_LC,
            container = Container.M4A,
            sampleRateHz = sampleRateHz,
            channels = 1,
            bitrateKbps = BITRATE_KBPS,
            segmentSec = segmentSec,
        ),
        tracks = listOf(Track.MONO),
        parts = emptyList(),
        status = RecordingStatus.RECORDING,
    )

    private fun audioManager(): AudioManager = context.getSystemService(AudioManager::class.java)

    companion object {
        /** ADR-006. Overridable so a smoke test does not have to run for fifteen minutes. */
        const val DEFAULT_SEGMENT_SEC: Int = 900
        const val BITRATE_KBPS: Int = 32
        const val PREFERRED_SAMPLE_RATE_HZ: Int = 16_000

        /** Marks a part whose audio is on disk but whose row is not; [RecordingRecovery] clears it. */
        const val PENDING_SUFFIX: String = ".pending"

        /** Full scale as `MediaRecorder.getMaxAmplitude` reports it: 16-bit, signed. */
        private const val MAX_AMPLITUDE = 32_767f

        internal const val BYTES_PER_SEC: Int = BITRATE_KBPS * 1000 / 8
        private const val CONTAINER_OVERHEAD_PERCENT = 107
        private val SAMPLE_RATES_HZ = listOf(PREFERRED_SAMPLE_RATE_HZ, 44_100)
    }
}
