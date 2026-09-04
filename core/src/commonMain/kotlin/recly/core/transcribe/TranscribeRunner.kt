@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.DriveUploadRunner
import recly.core.drive.string
import recly.core.job.StepContext
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.job.StepRunner
import recly.core.job.priorOutput
import recly.core.message.CoreMessage
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.Step
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.model.wire
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.MetaWriter

/**
 * `transcribe` (docs/08). One pass does one thing: the first one picks the track, joins its parts
 * and submits them, every later one polls — and both end in [StepOutcome.Waiting], which costs no
 * retry attempt, until the provider answers.
 *
 * The submission ref lives in `state_json`, so a process that dies while a 40-minute job is
 * running comes back and polls the same job instead of paying to transcribe the audio twice.
 */
class TranscribeRunner(
    private val api: DriveApi,
    private val deps: CoreDeps,
    private val providers: (String) -> SttProvider? = SttProviders::create,
) : StepRunner {
    override val type: String = TYPE

    private val results = ResultFiles(api, deps)

    override suspend fun run(ctx: StepContext): StepOutcome {
        val step = ctx.step as? Step.Transcribe
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.STEP_FAILED.code("$TYPE runner got a ${ctx.step::class.simpleName}"),
            )
        val provider = resolveProvider(providers, step.provider)
        val key = apiKey(deps, step.secretRef)
        val state = TranscribeState.from(ctx.state)
        val sttCtx = SttContext(
            step = step,
            apiKey = key,
            speakersExpected = speakersExpected(step, ctx.recording.meta),
            audioDurationSec = audioDurationSec(ctx.recording.meta),
            deps = deps,
            providerState = state.providerState,
        )
        return if (state.ref == null) submit(ctx, step, provider, sttCtx) else poll(ctx, step, provider, sttCtx, state)
    }

    private suspend fun submit(
        ctx: StepContext,
        step: Step.Transcribe,
        provider: SttProvider,
        sttCtx: SttContext,
    ): StepOutcome {
        val track = track(ctx.recording.meta)
        val parts = partsOf(ctx.recording.meta, track)
        val file = joined(ctx, step, parts)
        val submitted = try {
            checkLimits(provider, sttCtx, file)
            provider.submit(sttCtx, file)
        } finally {
            if (parts.size > 1) deps.fileSystem.delete(file, mustExist = false)
        }
        deps.logger.log(
            Logger.Level.INFO,
            "transcribe.submit",
            mapOf(
                "stepId" to step.id,
                "provider" to provider.name,
                "track" to track.wire,
                "ref" to (submitted as? Submitted.Polling)?.ref,
            ),
        )
        // A synchronous provider answered with the transcript itself: there is no ref, and parking
        // the job to poll for a result already in hand would only cost another pass (docs/08).
        if (submitted is Submitted.Finished) {
            val state = TranscribeState(track = track, providerState = sttCtx.providerState)
            ctx.saveState(state.toJson())
            return StepOutcome.Done(finish(ctx, step, provider, state, submitted.result))
        }
        val state = TranscribeState(
            ref = (submitted as Submitted.Polling).ref,
            submittedAt = deps.clock.now().isoUtc(),
            track = track,
            providerState = sttCtx.providerState,
        )
        ctx.saveState(state.toJson())
        return StepOutcome.Waiting(POLL_SEC, state.toJson())
    }

    private suspend fun poll(
        ctx: StepContext,
        step: Step.Transcribe,
        provider: SttProvider,
        sttCtx: SttContext,
        state: TranscribeState,
    ): StepOutcome {
        // A job that never finishes is not a job to keep waiting for: retrying submits a new one.
        val submittedAt = state.submittedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (submittedAt == null || deps.clock.now() - submittedAt > provider.resultTimeout) {
            throw dead(
                ctx,
                sttCtx,
                CoreMessage.RESULT_TIMEOUT.code(
                    detail = "'${state.ref}' did not finish within ${provider.resultTimeout}",
                ),
            )
        }
        // Anything the provider throws from here — a 5xx, a dropped connection, a 429 — leaves the
        // ref in place: the submission is still alive and the next pass goes on polling it.
        return when (val result = provider.poll(sttCtx, state.ref!!)) {
            PollResult.Pending ->
                StepOutcome.Waiting(POLL_SEC, state.copy(providerState = sttCtx.providerState).toJson())

            is PollResult.Done ->
                StepOutcome.Done(finish(ctx, step, provider, state, result.result))

            is PollResult.Failed ->
                throw dead(ctx, sttCtx, CoreMessage.PROVIDER_ERROR.code(detail = "${provider.name} ${result.reason}"))
        }
    }

    /**
     * The submission is gone, so the state that points at it has to go with it — otherwise every
     * remaining attempt polls the same dead ref and the step spends its budget without ever
     * re-submitting (docs/08: "재시도는 새로 제출"). The write lands before the throw, and the
     * executor's failure path never touches `state_json`, so it survives the parked attempt.
     *
     * The provider's own scratch stays: a cached access token outlives the submission it was
     * bought for, and the retry can spend it instead of asking for another.
     */
    private suspend fun dead(ctx: StepContext, sttCtx: SttContext, reason: String): StepFailure {
        ctx.saveState(TranscribeState(providerState = sttCtx.providerState).toJson())
        return StepFailure(retryable = true, reason = reason)
    }

    private suspend fun finish(
        ctx: StepContext,
        step: Step.Transcribe,
        provider: SttProvider,
        state: TranscribeState,
        result: SttResult,
    ): StepOutput {
        val meta = ctx.recording.meta
        val track = state.track ?: track(meta)
        val transcript = TranscriptNormalizer.normalize(
            recordingId = meta.recordingId,
            track = track,
            parts = partsOf(meta, track),
            result = result,
            diarize = step.diarize,
            provider = TranscriptProvider(provider.name, result.model ?: step.model, state.ref),
            createdAt = deps.clock.now().isoUtc(),
            language = result.language ?: step.language.wire,
        )
        val base = MetaWriter.baseName(meta)
        val folderId = folderId(ctx)
        val json = results.write(
            dir = ctx.recording.dir,
            folderId = folderId,
            name = jsonFileName(base),
            content = recJson.encodeToString(transcript).encodeToByteArray(),
            mimeType = JSON_MIME,
        )
        val text = results.write(
            dir = ctx.recording.dir,
            folderId = folderId,
            name = textFileName(base),
            content = TranscriptNormalizer.text(transcript).encodeToByteArray(),
            mimeType = TEXT_MIME,
        )
        deps.logger.log(
            Logger.Level.INFO,
            "transcribe.done",
            mapOf(
                "stepId" to step.id,
                "provider" to provider.name,
                "segments" to transcript.segments.size,
                "speakers" to transcript.speakers.size,
            ),
        )
        return StepOutput(
            buildJsonObject {
                putJsonObject("transcript") {
                    put("jsonFileId", json.fileId)
                    put("txtFileId", text.fileId)
                    put("language", transcript.language)
                    put("speakerCount", transcript.speakers.size)
                    put("durationSec", transcript.durationSec)
                    put("provider", provider.name)
                    transcript.provider.model?.let { put("model", it) }
                }
                // The same shape `drive.upload` writes, so the webhook payload builder can read
                // both with one code path (docs/04 `files[]`).
                putJsonArray("files") {
                    add(json.toJson(TRACK))
                    add(text.toJson(TRACK))
                }
            },
        )
    }

    /** docs/08: `mono` if the recording has one, else `mix`; anything else is not transcribable. */
    private fun track(meta: RecordingMeta): Track = trackOrNull(meta)
        ?: throw StepFailure(
            retryable = false,
            reason = CoreMessage.NO_INPUT_TRACK.code(detail = "recording has ${meta.tracks.map { it.wire }}"),
        )

    private fun trackOrNull(meta: RecordingMeta): Track? = when {
        Track.MONO in meta.tracks -> Track.MONO
        Track.MIX in meta.tracks -> Track.MIX
        else -> null
    }

    /**
     * The length of the file the provider is handed: the parts that get joined, not the recording,
     * which also counts the gaps between them. Asked before the track is chosen for real, so a
     * recording with nothing to transcribe answers null here and fails where it always did.
     */
    private fun audioDurationSec(meta: RecordingMeta): Double? {
        val parts = trackOrNull(meta)?.let { track -> meta.parts.filter { it.track == track } }.orEmpty()
        return parts.takeIf { it.isNotEmpty() }?.sumOf { it.durationSec } ?: meta.durationSec
    }

    private fun partsOf(meta: RecordingMeta, track: Track): List<Part> {
        val parts = meta.parts.filter { it.track == track }.sortedBy { it.part }
        if (parts.isEmpty()) {
            throw StepFailure(
                retryable = false,
                reason = CoreMessage.NO_INPUT_TRACK.code(detail = "no '${track.wire}' parts"),
            )
        }
        return parts
    }

    /**
     * One part is already the file the provider needs — joining it would only copy bytes. More
     * than one goes through the shell's muxer into a temp file the caller deletes.
     */
    private suspend fun joined(ctx: StepContext, step: Step.Transcribe, parts: List<Part>): Path {
        val paths = parts.map { ctx.recording.dir / it.file }
        if (paths.size == 1) return paths.single()
        val out = ctx.recording.dir / "${MetaWriter.baseName(ctx.recording.meta)}.${step.id}$CONCAT_SUFFIX"
        deps.fileSystem.delete(out, mustExist = false)
        deps.audio.concat(paths, out)
        return out
    }

    /**
     * The ceilings the provider publishes, checked on the joined file before a byte leaves the
     * device (docs/08 "길이·크기 한도"). The provider would answer the same 4xx — but only after
     * the upload, and on a phone that upload is the expensive part. A limit the provider does not
     * declare is still learned the old way, from its own rejection.
     */
    private fun checkLimits(provider: SttProvider, sttCtx: SttContext, file: Path) {
        val limits = provider.limits
        val bytes = limits.maxBytes
        if (bytes != null) {
            val size = deps.fileSystem.metadata(file).size
            if (size != null && size > bytes) {
                throw tooBig("${megabytes(size)} exceeds ${provider.name}'s ${megabytes(bytes)}")
            }
        }
        val seconds = limits.maxDurationSec
        val length = sttCtx.audioDurationSec
        if (seconds != null && length != null && length > seconds) {
            throw tooBig("${hoursMinutes(length)} exceeds ${provider.name}'s ${hoursMinutes(seconds)}")
        }
    }

    private fun tooBig(detail: String): StepFailure = StepFailure(
        retryable = false,
        reason = CoreMessage.UNSUPPORTED_AUDIO.code(detail = detail),
    )

    /** The folder the results go in is the preceding upload's output — validation guarantees one. */
    private fun folderId(ctx: StepContext): String =
        ctx.priorOutput(DriveUploadRunner.TYPE)?.string("folderId")
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.STEP_FAILED.code("no successful 'drive.upload' step to write into"),
            )

    /** The recording knew how many people were in the room; the workflow only guessed (docs/08). */
    private fun speakersExpected(step: Step.Transcribe, meta: RecordingMeta): Int? {
        val participants = meta.context?.participants
        if (participants != null) return participants
        return step.speakers.min.takeIf { it == step.speakers.max }
    }

    companion object {
        const val TYPE = "transcribe"

        /** The `files[]` track name both result files carry (docs/04). */
        const val TRACK = "transcript"

        fun jsonFileName(base: String): String = "$base.transcript.json"

        fun textFileName(base: String): String = "$base.transcript.txt"

        fun create(deps: CoreDeps): TranscribeRunner = TranscribeRunner(DriveApi(deps), deps)

        internal const val JSON_MIME = "application/json"
        internal const val TEXT_MIME = "text/plain"
        private const val POLL_SEC = 30
        private const val CONCAT_SUFFIX = ".concat.m4a"
        private const val MIB = 1024L * 1024L

        /** One decimal at most, so a limit written as a round number reads as one: "25 MB". */
        private fun megabytes(bytes: Long): String {
            val tenths = (bytes * 10 + MIB / 2) / MIB
            return if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
        }

        /** Same idea as [megabytes]: a limit written as whole hours reads as "2h", not "2h 0m". */
        private fun hoursMinutes(sec: Double): String {
            val minutes = (sec / 60).toLong()
            return when {
                minutes < 60 -> "${minutes}m"
                minutes % 60 == 0L -> "${minutes / 60}h"
                else -> "${minutes / 60}h ${minutes % 60}m"
            }
        }
    }
}

/** `step_run.state_json` for `transcribe`: what was submitted, when, and from which track. */
@Serializable
internal data class TranscribeState(
    val ref: String? = null,
    val submittedAt: String? = null,
    val track: Track? = null,
    /**
     * The provider's own scratch — RTZR's cached access token and its expiry. Opaque here on
     * purpose: `ref` is the provider's job id and nothing else, because it is what
     * `transcript.json` publishes as `provider.jobRef`.
     */
    val providerState: JsonObject? = null,
) {
    fun toJson(): JsonObject = recJson.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        fun from(json: JsonObject?): TranscribeState =
            if (json == null) TranscribeState() else recJson.decodeFromJsonElement(serializer(), json)
    }
}
