@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.transcribe

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import recly.core.db.RecDatabase
import recly.core.job.*
import recly.core.message.CoreMessage
import recly.core.model.*
import recly.core.platform.CoreDeps
import recly.core.recording.DeleteResult
import recly.core.recording.MetaWriter

/**
 * Native work outlives one short queue pass, never a platform execution opportunity. The caller
 * awaits it outside Executor's gate and cancels it on expiration. One native job per device.
 * Final native segments are durable checkpoints; audio parts are losslessly joined into one input.
 */
class LocalTranscriptionService(private val db: RecDatabase, private val deps: CoreDeps) : StepRunner {
    override val type = "local.transcribe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val compute = Mutex()
    private val gate = Mutex()
    private val active = mutableMapOf<String, Entry>()
    private val errors = mutableMapOf<String, Throwable>()
    private val deleting = mutableSetOf<String>()
    private val recording = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private val jobs = JobStore(db, deps)
    internal val changes = MutableStateFlow(0L)

    private class Entry(val recordingId: String, val jobId: String) {
        lateinit var task: kotlinx.coroutines.Job
        var running = false
    }

    @Throws(Throwable::class)
    suspend fun isRunning(recordingId: String): Boolean = gate.withLock {
        active.values.any { it.recordingId == recordingId && it.running }
    }

    override suspend fun run(ctx: StepContext): StepOutcome {
        val step = ctx.step as Step.LocalTranscribe
        val input = fingerprint(ctx)
        val checkpoint = withContext(deps.io) { checkpoint(ctx, input) }
        if (checkpoint?.complete == true && deps.fileSystem.exists(ctx.recording.dir / resultName(ctx.stepRunId))) return done(input, ctx)
        val info = deps.localTranscription.status(step.language.wire)
        when (info.status) {
            LocalEngineStatus.UNSUPPORTED -> throw failure(CoreMessage.LOCAL_TRANSCRIPTION_UNAVAILABLE)
            LocalEngineStatus.MODEL_REQUIRED -> throw failure(CoreMessage.LOCAL_MODEL_REQUIRED)
            LocalEngineStatus.WAITING -> return waiting(input)
            LocalEngineStatus.READY -> Unit
        }
        gate.withLock {
            errors.remove(ctx.stepRunId)?.let { throw it }
            if (recording.isNotEmpty() || ctx.recording.id in deleting) return waiting(input)
            if (ctx.stepRunId !in active) {
                val entry = Entry(ctx.recording.id, ctx.job.id)
                entry.task = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        compute.withLock {
                            gate.withLock { entry.running = true }
                            transcribe(ctx, input, info, entry)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        gate.withLock { if (active[ctx.stepRunId] === entry) errors[ctx.stepRunId] = e }
                    } finally {
                        withContext(NonCancellable) {
                            gate.withLock { if (active[ctx.stepRunId] === entry) active.remove(ctx.stepRunId) }
                        }
                    }
                }
                active[ctx.stepRunId] = entry
                entry.task.start()
            }
        }
        return waiting(input)
    }

    private suspend fun transcribe(ctx: StepContext, input: String, info: LocalEngineInfo, entry: Entry) {
        val step = ctx.step as Step.LocalTranscribe
        val diarize = step.diarize && info.supportsDiarization
        val track = if (Track.MONO in ctx.recording.meta.tracks) Track.MONO else Track.MIX
        val parts = ctx.recording.meta.parts.filter { it.track == track }.sortedBy { it.part }
        if (parts.isEmpty()) throw failure(CoreMessage.NO_INPUT_TRACK)
        var saved = withContext(deps.io) { checkpoint(ctx, input) }
            ?.takeIf { it.engine == info.revision } ?: LocalCheckpoint(input, info.revision)
        val paths = parts.map { ctx.recording.dir / it.file }
        val inputFile = if (paths.size == 1) paths.single() else ctx.recording.dir / ".local-${ctx.stepRunId}.m4a"
        try {
        if (paths.size > 1 && !deps.fileSystem.exists(inputFile)) deps.audio.concat(paths, inputFile)
        val result = deps.localTranscription.transcribe(
            LocalTranscriptionRequest(inputFile.toString(), step.language.wire, saved.completedThroughSec, diarize),
            object : LocalTranscriptionProgress {
                override suspend fun checkpoint(segment: SttSegment, completedThroughSec: Double) {
                    currentCoroutineContext().ensureActive()
                    require(segment.start.isFinite() && segment.end.isFinite() && segment.start >= 0 && segment.end >= segment.start)
                    require(completedThroughSec.isFinite() && completedThroughSec >= segment.end)
                    if (segment.end <= saved.completedThroughSec) return
                    saved = saved.copy(completedThroughSec = completedThroughSec, segments = saved.segments + segment)
                    publishCheckpoint(ctx, entry, saved)
                }
            },
        )
        currentCoroutineContext().ensureActive()
        if (!result.completed) return
        // Engines without progressive checkpoints may return their final segments here.
        val remaining = result.segments.filter { it.end > saved.completedThroughSec }.onEach {
            require(it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end >= it.start)
        }
        saved = saved.copy(segments = saved.segments + remaining)
        val normalized = TranscriptNormalizer.normalize(ctx.recording.id, track, parts,
            SttResult(saved.segments, step.language.wire, null, info.revision), diarize,
            TranscriptProvider(info.name, info.revision), deps.clock.now().isoUtc(), step.language.wire)
        val identified = diarize && saved.segments.isNotEmpty() && saved.segments.all { !it.speaker.isNullOrEmpty() }

        val transcript = Transcript(
            schema = Transcript.LOCAL_SCHEMA, recordingId = ctx.recording.id, track = track, language = step.language.wire,
            provider = TranscriptProvider(info.name, info.revision), createdAt = deps.clock.now().isoUtc(),
            durationSec = parts.maxOf { it.startOffsetSec + it.durationSec },
            speakers = if (identified) normalized.speakers else emptyList(),
            segments = normalized.segments.map { if (identified) it else it.copy(speaker = "") },
            speakerIdentification = if (identified) "identified" else "unavailable", timing = "segment",
        )
        publishCheckpoint(ctx, entry, saved.copy(complete = true), transcript)
        } finally {
            if (paths.size > 1) deps.fileSystem.delete(inputFile, mustExist = false)
        }
    }

    /** Generation identity and row existence are checked under the same gate deletion acquires. */
    private suspend fun publishCheckpoint(ctx: StepContext, entry: Entry, value: LocalCheckpoint, transcript: Transcript? = null) {
        gate.withLock {
            currentCoroutineContext().ensureActive()
            if (active[ctx.stepRunId] !== entry || ctx.recording.id in deleting) return
            withContext(deps.io) {
                if (db.recQueries.selectRecordingById(ctx.recording.id).executeAsOneOrNull() == null || jobs.get(ctx.job.id) == null) return@withContext
                resultFileMutex.withLock {
                    if (transcript != null) {
                        val base = MetaWriter.baseName(ctx.recording.meta)
                        atomic(ctx.recording.dir / resultName(ctx.stepRunId), recJson.encodeToString(transcript))
                        atomic(ctx.recording.dir / TranscribeRunner.jsonFileName(base), recJson.encodeToString(transcript))
                        atomic(ctx.recording.dir / TranscribeRunner.textFileName(base), TranscriptNormalizer.text(transcript))
                    }
                    atomic(checkpointPath(ctx), recJson.encodeToString(value))
                }
                if (transcript != null) completed.add(ctx.job.id)
                changes.value++
            }
        }
    }

    /** The platform caller stays alive until compute stops; it owns the execution budget. */
    internal suspend fun awaitCurrent() {
        val tasks = gate.withLock { active.values.map { it.task } }
        tasks.joinAll()
        val ids = gate.withLock { completed.toList().also { completed.clear() } }
        withContext(deps.io) { ids.forEach { jobs.clearBackoff(it, deps.clock.now()) } }
    }

    @Throws(Throwable::class)
    suspend fun cancelAll() {
        deps.localTranscription.cancel()
        val tasks = gate.withLock {
            active.values.map { it.task }.also { active.clear(); it.forEach { task -> task.cancel() } }
        }
        withTimeoutOrNull(5_000) { tasks.joinAll() }
    }

    internal suspend fun captureStarted(id: String) {
        gate.withLock { recording.add(id) }
        cancelAll()
    }

    internal suspend fun captureEnded(id: String) { gate.withLock { recording.remove(id) } }

    internal suspend fun deleting(id: String, body: suspend () -> DeleteResult): DeleteResult {
        val tasks = gate.withLock {
            deleting.add(id)
            active.filterValues { it.recordingId == id }.keys.mapNotNull { key -> active.remove(key)?.task }
                .also { it.forEach { task -> task.cancel() } }
        }
        try {
            if (tasks.isNotEmpty()) deps.localTranscription.cancel()
            if (withTimeoutOrNull(5_000) { tasks.joinAll(); true } != true) return DeleteResult.Busy
            return gate.withLock { body().also { if (it is DeleteResult.Deleted) recording.remove(id) } }
        } finally { gate.withLock { deleting.remove(id) } }
    }

    private fun checkpoint(ctx: StepContext, input: String): LocalCheckpoint? {
        val path = checkpointPath(ctx)
        if (!deps.fileSystem.exists(path)) return null
        return runCatching { recJson.decodeFromString<LocalCheckpoint>(deps.fileSystem.read(path) { readUtf8() }) }
            .getOrNull()?.takeIf { it.input == input }
    }

    private fun checkpointPath(ctx: StepContext) = ctx.recording.dir / ".local-${ctx.stepRunId}.json"
    private fun fingerprint(ctx: StepContext): String =
        (recJson.encodeToString(ctx.recording.meta.parts) + recJson.encodeToString(ctx.step)).encodeUtf8().sha256().hex()

    private fun atomic(path: Path, text: String) {
        val temp = path.parent!! / "${path.name}.tmp"
        deps.fileSystem.write(temp) { writeUtf8(text) }
        deps.fileSystem.atomicMove(temp, path)
    }
    private fun waiting(input: String) = StepOutcome.Waiting(30, buildJsonObject { put("input", input) })
    private fun done(input: String, ctx: StepContext) = StepOutcome.Done(StepOutput(buildJsonObject { put("input", input); put("resultFile", resultName(ctx.stepRunId)) }))
    internal companion object { fun resultName(stepRunId: String) = ".result-$stepRunId.json" }
    private fun failure(message: CoreMessage) = StepFailure(false, message.code())
}

@Serializable
private data class LocalCheckpoint(
    val input: String,
    val engine: String,
    val completedThroughSec: Double = 0.0,
    val segments: List<SttSegment> = emptyList(),
    val complete: Boolean = false,
)
