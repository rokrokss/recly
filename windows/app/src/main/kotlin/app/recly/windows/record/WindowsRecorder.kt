@file:OptIn(ExperimentalTime::class)

package app.recly.windows.record

import app.recly.windows.core.isoUtc
import app.recly.windows.detect.Detection
import app.recly.windows.detect.NoDetection
import app.recly.windows.helper.HelperClient
import app.recly.windows.helper.HelperCommand
import app.recly.windows.helper.HelperEvent
import app.recly.windows.settings.RecordingMode
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path
import recly.core.ReclyCore
import recly.core.ids.Ulid
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import kotlin.time.Clock as TimeClock

/** What a finished recording leaves behind — the shell enqueues it (docs/14 "실행기" (a)). */
data class RecordingOutcome(
    val recordingId: String,
    val parts: Int,
    val durationSec: Double,
)

/** What a stop made of the recording. The Mac's `StopResult` has the same three answers. */
sealed interface StopResult {
    data object NotRecording : StopResult

    data class Finalized(val outcome: RecordingOutcome) : StopResult

    /**
     * Parts are on disk and marked ([PartMarker]) but not in the database, so nothing is finalized:
     * once a row says `finalized` nothing looks at the directory again and the audio would be
     * uploaded away. [RecordingRecovery] files them and finishes the job at the next launch.
     */
    data class Deferred(val recordingId: String, val pending: Int) : StopResult
}

/**
 * One recording, driven by the capture helper (docs/14). The app owns the database row, `meta.json`
 * and the naming; the helper owns the audio.
 *
 * The two ways a recording ends are the same code path: the tray's stop, and the helper dying under
 * it. docs/14 "헬퍼가 죽으면 앱이 마지막 파트까지를 finalize한다" — [Session.recordedSec] is exactly
 * "the last `part_done` that reached the database", so a helper killed mid-segment costs the segment
 * in flight and nothing else, and a part the database refused holds the finalize back
 * ([StopResult.Deferred]) instead of disappearing from it.
 */
class WindowsRecorder(
    private val core: ReclyCore,
    private val scope: CoroutineScope,
    /** Null when there is no helper binary — recording is disabled, not attempted (deliverable 5). */
    private val helper: () -> HelperClient?,
    /** Called for both endings, so the shell enqueues a recording it did not stop itself. */
    private val onFinalized: suspend (RecordingOutcome) -> Unit,
    private val onState: (Boolean) -> Unit = {},
    /**
     * docs/14 "감지". While a recording is running this helper is the only one that may report the
     * microphone — it is the one process the helper's session enumeration leaves out — so detection
     * is handed over before this one is spawned and handed back when its stdout has ended.
     */
    private val detection: Detection = NoDetection,
    /** The helper died under a running recording — the shell offers a restart (deliverable 3). */
    private val onHelperDied: () -> Unit = {},
    private val segmentSec: Int = DEFAULT_SEGMENT_SEC,
    /** How long a stop waits for the helper to close its last segment before killing it. */
    private val stopTimeout: Duration = STOP_TIMEOUT,
    /** And how long it then waits for the reader to file what the dying helper flushed. */
    private val drainTimeout: Duration = DRAIN_TIMEOUT,
) {
    private class Session(
        val recordingId: String,
        val startedAt: Instant,
        val dir: Path,
        val helper: HelperClient,
        /**
         * What this session's claim on detection is called (`Detection`). A deferred stop leaves
         * this consumer running after [session] has been cleared, and by the time it reaches EOF a
         * later recording may own detection — the token is what makes its `resume` a no-op.
         */
        val detectionToken: Long,
    ) {
        /**
         * The end of the furthest part filed so far. A maximum and not a sum: the three tracks
         * cover the same fifteen minutes, and adding them up would report an hour as three.
         */
        @Volatile var recordedSec: Double = 0.0

        /** Set by [stop] so the reader's end-of-stream is not mistaken for the helper dying. */
        @Volatile var stopping: Boolean = false

        /** docs/09 화면 원칙 6: the last thirty seconds of levels, for the popup's strip. */
        val live = LiveWaveform()

        /**
         * Parts the helper reported and the database refused. Kept whole, not counted: the stop
         * retries each one before deciding, and a part that is neither in the meta nor durably
         * marked is a part no later pass could find.
         */
        val unfiled = ConcurrentLinkedQueue<Part>()

        /** Completed when the reader has consumed the helper's last line — see [stop]. */
        val drained = CompletableDeferred<Unit>()
    }

    private val mutex = Mutex()
    private var session: Session? = null

    val isRecording: Boolean get() = session != null

    /**
     * docs/09 화면 원칙 6: the levels of the recording that is running, oldest first — empty when
     * there is none. Read on every tick of the strip rather than pushed into the model: three
     * hundred floats ten times a second through a Compose state would redraw the whole popup.
     */
    fun livePeaks(): List<Float> = session?.live?.peaks ?: emptyList()

    /**
     * Creates the row and `meta.json`, then tells the helper where to write. Null when there is
     * nothing to record with, or a recording is already running.
     *
     * [mode] is the whole of what the two recording modes are: the tracks it names are the ones the
     * helper is asked for and the ones the meta records, and the helper opens the render endpoint
     * only for a track that needs it — so a microphone-only recording never touches the speakers.
     * It is a parameter and not a field because it is fixed at the start and cannot change under a
     * running recording (docs/14 "캡처", the Mac's `session.start(workflowId:mode:)`).
     */
    suspend fun start(
        workflowId: String?,
        title: String? = null,
        mode: RecordingMode = RecordingMode.MEETING,
    ): String? = mutex.withLock {
        if (session != null) return@withLock null
        val client = helper() ?: return@withLock null

        val tracks = mode.tracks
        val startedAt = core.deps.clock.now()
        val recordingId = Ulid.generate(object : TimeClock { override fun now(): Instant = startedAt })
        val meta = meta(recordingId, startedAt, workflowId, title, tracks)
        val base = MetaWriter.baseName(meta)
        val dir = core.deps.dataDir / "recordings" / base
        withContext(core.deps.io) { core.deps.fileSystem.createDirectories(dir) }
        core.recordings.create(meta, dir)

        // Before the process exists, and awaited: two helpers must never be alive at once
        // (`Detection`). Whatever happens below, detection is handed back — under this token.
        val open = Session(recordingId, startedAt, dir, client, detection.yieldToRecorder())
        try {
            client.open(scope)
            client.send(HelperCommand.Start(dir.toString(), base, segmentSec, tracks))
            // docs/14 "감지": the microphone monitor belongs to whichever helper is running, and
            // for the length of this recording that is this one.
            client.send(HelperCommand.Detect(on = true))
        } catch (e: Exception) {
            // The helper never came up: give back the row and the directory rather than leave a
            // recording nothing will ever finalize.
            runCatching { client.close() }
            detection.resume(open.detectionToken)
            runCatching { core.recordings.delete(recordingId) }
            core.deps.logger.log(Logger.Level.ERROR, "rec.start.failed", mapOf("recordingId" to recordingId), e)
            return@withLock null
        }
        session = open
        onState(true)
        scope.launch { consume(open) }
        core.deps.logger.log(
            Logger.Level.INFO,
            "rec.recorder.start",
            mapOf(
                "recordingId" to recordingId,
                "segmentSec" to segmentSec,
                "mode" to mode.key,
                "tracks" to tracks.size,
            ),
        )
        recordingId
    }

    /**
     * Asks the helper to stop, waits for the parts still coming, then finalizes. The wait is what
     * makes the last segment part of the recording rather than an orphan on disk.
     */
    suspend fun stop(title: String? = null): StopResult {
        val open = mutex.withLock { session?.also { it.stopping = true } } ?: return StopResult.NotRecording
        onState(false)
        open.helper.send(HelperCommand.Stop)
        // Waiting for the reader rather than reading here: there is one consumer of the event
        // channel and a second would take half the trailing parts away from it.
        val drained = withTimeoutOrNull(stopTimeout) { open.drained.await() } != null
        // A helper that did not answer the stop is killed here; the reader then ends on the closed
        // pipe. Waiting for it again is the point: the events it flushed on its way out are still
        // being filed, and finalizing over the consumer would leave the last parts out of the meta.
        open.helper.close()
        val settled = drained || withTimeoutOrNull(drainTimeout) { open.drained.await() } != null
        if (!settled) {
            // The consumer is still running. Nothing may be finalized over it — the row stays
            // `recording` and `RecordingRecovery` finishes the job at the next launch, which is the
            // one path that gets to look at the directory again.
            mutex.withLock { if (session === open) session = null }
            core.deps.logger.log(
                Logger.Level.ERROR,
                "rec.recorder.stopDeferred",
                mapOf("recordingId" to open.recordingId, "reason" to "drain_timeout"),
            )
            return StopResult.Deferred(open.recordingId, pending = 0)
        }
        return finish(open, title)
    }

    /** The reader: events until the helper's stdout ends, and that end is the helper's death. */
    private suspend fun consume(open: Session) {
        for (event in open.helper.events) apply(open, event)
        // The helper's stdout has ended, so it will report no more microphones: detection may have
        // one of its own again (`Detection`). Before the finalize, which can take a database write.
        detection.resume(open.detectionToken)
        open.drained.complete(Unit)
        if (open.stopping) return
        core.deps.logger.log(
            Logger.Level.ERROR,
            "rec.helper.died",
            mapOf("recordingId" to open.recordingId, "recordedSec" to open.recordedSec),
        )
        onState(false)
        onHelperDied()
        finish(open, title = null)
    }

    private suspend fun apply(open: Session, event: HelperEvent) {
        when (event) {
            is HelperEvent.PartDone -> {
                val part = Part(
                    part = event.part,
                    track = event.track,
                    file = event.file,
                    bytes = event.bytes,
                    sha256 = event.sha256,
                    startOffsetSec = event.startOffsetSec,
                    durationSec = event.durationSec,
                )
                runCatching { core.recordings.addPart(open.recordingId, part) }
                    .onSuccess {
                        // Only a part that is actually in the meta counts towards the length: the
                        // duration handed to `finalize` has to be one the parts can account for.
                        open.recordedSec = maxOf(open.recordedSec, part.startOffsetSec + part.durationSec)
                    }
                    .onFailure { failure ->
                        // The audio is on disk; a marker beside it carries everything the row would
                        // have said, so the next launch files it instead of quarantining it
                        // (docs/03 "크래시 시 마지막 경계까지는 복구 가능"). Whether or not that
                        // marker lands, the part is unfiled and the stop may not finalize over it.
                        open.unfiled += part
                        mark(open, part, failure)
                    }
            }

            is HelperEvent.Level -> open.live.add(event.peaks)

            is HelperEvent.MicInUse ->
                detection.micInUse(open.detectionToken, event.app, event.inUse)

            is HelperEvent.Failed ->
                core.deps.logger.log(
                    Logger.Level.ERROR,
                    "rec.helper.error",
                    mapOf("message" to event.message, "fatal" to event.fatal),
                )
        }
    }

    /** Writes the marker for a part the database refused. True when it is on disk to be found. */
    private suspend fun mark(open: Session, part: Part, cause: Throwable?): Boolean {
        val marked = runCatching {
            withContext(core.deps.io) { PartMarker.write(core.deps.fileSystem, open.dir, part) }
        }.isSuccess
        core.deps.logger.log(
            Logger.Level.ERROR,
            "rec.part.failed",
            mapOf("recordingId" to open.recordingId, "part" to part.part, "marked" to marked),
            cause,
        )
        return marked
    }

    /**
     * One retry of every refused part, at the stop. A transient database error (a locked file, a
     * moment of disk pressure) is over by now, and a part that goes in here is one the recording
     * does not have to be deferred for.
     */
    private suspend fun settle(open: Session): Int {
        var unresolved = 0
        while (true) {
            val part = open.unfiled.poll() ?: break
            val filed = runCatching { core.recordings.addPart(open.recordingId, part) }.isSuccess
            if (filed) {
                open.recordedSec = maxOf(open.recordedSec, part.startOffsetSec + part.durationSec)
                // The marker has done its job; leaving it would have recovery file the part twice.
                runCatching {
                    withContext(core.deps.io) {
                        core.deps.fileSystem.delete(PartMarker.path(open.dir, part), mustExist = false)
                    }
                }
            } else {
                // Still not in the meta, so the recording is deferred either way; the marker is what
                // decides whether the next launch can file it or has to quarantine the audio.
                mark(open, part, cause = null)
                unresolved++
            }
        }
        return unresolved
    }

    /** Finalizes once, whichever ending got here first. */
    private suspend fun finish(open: Session, title: String?): StopResult {
        val mine = mutex.withLock { (session === open).also { if (it) session = null } }
        if (!mine) return StopResult.NotRecording
        val pending = settle(open)
        if (pending > 0) {
            // Deliberately not finalized: the marked parts are not in the meta yet, and a row that
            // says `finalized` is one nothing will look at the directory for again.
            core.deps.logger.log(
                Logger.Level.ERROR,
                "rec.recorder.stopDeferred",
                mapOf("recordingId" to open.recordingId, "pending" to pending),
            )
            return StopResult.Deferred(open.recordingId, pending)
        }
        val finalized = core.recordings.finalize(
            recordingId = open.recordingId,
            endedAt = core.deps.clock.now(),
            // The parts are the truth about how much audio there is; the wall clock includes the
            // segment the helper never closed.
            durationSec = open.recordedSec,
            title = title,
        )
        val outcome = RecordingOutcome(open.recordingId, finalized.meta.parts.size, open.recordedSec)
        onFinalized(outcome)
        return StopResult.Finalized(outcome)
    }

    private fun meta(
        recordingId: String,
        startedAt: Instant,
        workflowId: String?,
        title: String?,
        tracks: List<Track>,
    ): RecordingMeta = RecordingMeta(
        schema = 1,
        recordingId = recordingId,
        source = Source.DESKTOP,
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
            sampleRateHz = SAMPLE_RATE_HZ,
            channels = 1,
            bitrateKbps = BITRATE_KBPS,
            segmentSec = segmentSec,
        ),
        tracks = tracks,
        parts = emptyList(),
        status = RecordingStatus.RECORDING,
    )

    companion object {
        /** ADR-006. */
        const val DEFAULT_SEGMENT_SEC: Int = 900
        const val BITRATE_KBPS: Int = 32
        const val SAMPLE_RATE_HZ: Int = 16_000

        internal val STOP_TIMEOUT: Duration = 30.seconds

        internal val DRAIN_TIMEOUT: Duration = 5.seconds
    }
}
