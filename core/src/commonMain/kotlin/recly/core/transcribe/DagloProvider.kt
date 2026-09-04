package recly.core.transcribe

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Path
import recly.core.drive.string
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan

/**
 * Daglo (다글로) asynchronous STT (docs/08 provider table): the audio goes to
 * `POST /stt/v1/async/transcripts` as `multipart/form-data` (`file` + an `sttConfig` JSON string)
 * and the job is polled at `GET /stt/v1/async/transcripts/{rid}`.
 *
 * The transcript comes back as one flat word list rather than turns, so the segments here are cut
 * wherever the provider's own `segmentId` or `speaker` changes.
 */
class DagloProvider : SttProvider {
    override val name: String = NAME

    /** docs/08: four hours of audio per request. */
    override val limits: SttLimits = SttLimits(maxDurationSec = 4 * 3600.0)

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val result = Reasons.send(
            ctx.deps,
            "daglo.submit",
            HttpPlan(
                method = "POST",
                url = "$BASE/stt/v1/async/transcripts",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "file",
                            contentType = AUDIO_TYPE,
                            source = HttpBody.Multipart.Source.File(file),
                            filename = file.name,
                        ),
                        HttpBody.Multipart.Part(
                            name = "sttConfig",
                            contentType = JSON_TYPE,
                            source = HttpBody.Multipart.Source.Bytes(config(ctx).toString().encodeToByteArray()),
                        ),
                    ),
                ),
                timeoutSec = UPLOAD_TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("daglo.submit", result, CoreMessage.UNSUPPORTED_AUDIO)
        val rid = result.jsonBody()?.string("rid")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "daglo submit gave no rid"),
            )
        return Submitted.Polling(rid)
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        val result = Reasons.send(
            ctx.deps,
            "daglo.poll",
            HttpPlan(
                method = "GET",
                url = "$BASE/stt/v1/async/transcripts/$ref",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("daglo.poll", result, CoreMessage.PROVIDER_ERROR)
        // A job that finished with nothing in it — silence, or audio with no speech — answers 204
        // and no body at all. That is a finished transcription, not a fault to retry.
        if (result.status == NO_CONTENT) return PollResult.Done(empty(ctx))
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "daglo poll gave no JSON"),
            )
        val status = json.string("status")
        return when {
            status == TRANSCRIBED -> PollResult.Done(read(ctx, json))
            // The provider's own message is the only thing that says why, so it is kept verbatim.
            status in ERROR_STATES -> PollResult.Failed("$status ${json.string("message").orEmpty()}".trim())
            // Everything short of those is a queue state, and this API keeps adding names for them.
            else -> PollResult.Pending
        }
    }

    private fun config(ctx: SttContext): JsonObject = buildJsonObject {
        put("model", model(ctx))
        put("language", languageCode(ctx.step.language))
        putJsonObject("speakerDiarization") {
            put("enable", ctx.step.diarize)
            // `speakerCountHint` is a single number: only honest when the hint is one number to
            // begin with, and a hint of one is diarization asked to find nobody.
            if (ctx.step.diarize) {
                speakerCount(ctx)?.takeIf { it >= MIN_SPEAKER_HINT }?.let { put("speakerCountHint", it) }
            }
        }
    }

    /** `context.participants` collapses the range (docs/08); a range of one number already is one. */
    private fun speakerCount(ctx: SttContext): Int? =
        ctx.speakersExpected ?: ctx.step.speakers.min.takeIf { it == ctx.step.speakers.max }

    private fun model(ctx: SttContext): String = ctx.step.model ?: GENERAL

    /** docs/08: this provider has no detection mode, so `auto` is Korean — what it is used for. */
    private fun languageCode(language: Language): String = when (language) {
        Language.KO -> "ko-KR"
        Language.EN -> "en-US"
        Language.KO_EN -> "mixed"
        Language.AUTO -> "ko-KR"
    }

    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val segments = mutableListOf<SttSegment>()
        json["sttResults"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .forEach { result ->
                val words = result["words"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().map { it.jsonObject }
                if (words.isEmpty()) {
                    // Only `transcript` is promised; without words there is nothing to cut a turn
                    // on and no clock to put it on, so it sits where the last turn ended.
                    val at = segments.lastOrNull()?.end ?: 0.0
                    segments += SttSegment(
                        start = at,
                        end = at,
                        speaker = null,
                        text = result.string("transcript").orEmpty(),
                        words = null,
                    )
                } else {
                    segments += turns(words).map { turn ->
                        SttSegment(
                            start = time(turn.first(), "startTime"),
                            end = time(turn.last(), "endTime"),
                            speaker = turn.first().string("speaker"),
                            // Words carry nothing but the word, so a turn's text is them with spaces.
                            text = turn.joinToString(" ") { it.string("word").orEmpty() },
                            words = turn.map {
                                SttWord(time(it, "startTime"), time(it, "endTime"), it.string("word").orEmpty())
                            },
                        )
                    }
                }
            }
        return empty(ctx).copy(segments = segments, durationSec = segments.lastOrNull()?.end)
    }

    /** A finished job with no transcript in it still knows what it was asked for. */
    private fun empty(ctx: SttContext): SttResult = SttResult(
        segments = emptyList(),
        // The response echoes no language, so what was asked for is what was heard.
        language = languageCode(ctx.step.language),
        durationSec = null,
        model = model(ctx),
    )

    /** The words in order, split wherever `segmentId` or `speaker` stops matching the one before. */
    private fun turns(words: List<JsonObject>): List<List<JsonObject>> {
        val turns = mutableListOf<MutableList<JsonObject>>()
        words.forEach { word ->
            val current = turns.lastOrNull()
            if (current != null && sameTurn(current.last(), word)) current += word else turns += mutableListOf(word)
        }
        return turns
    }

    private fun sameTurn(previous: JsonObject, word: JsonObject): Boolean =
        previous.string("segmentId") == word.string("segmentId") &&
            previous.string("speaker") == word.string("speaker")

    /** Timestamps are protobuf durations, and `seconds` arrives as a string often enough. */
    private fun time(owner: JsonObject, key: String): Double {
        val at = owner[key]?.takeIf { it !is JsonNull }?.jsonObject ?: return 0.0
        val seconds = at["seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val nanos = at["nanos"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        return seconds + nanos / NANOS
    }

    companion object {
        const val NAME = "daglo"
        internal const val BASE = "https://apis.daglo.ai"
        internal const val GENERAL = "general"
        internal const val TRANSCRIBED = "transcribed"

        /** The states this API calls terminal: the ref is worthless once one of them appears. */
        internal val ERROR_STATES = setOf("input_error", "transcript_error", "file_error")

        /** What this API answers when the finished job has no transcript to hand over. */
        internal const val NO_CONTENT = 204

        private const val MIN_SPEAKER_HINT = 2
        private const val NANOS = 1_000_000_000.0
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"
        private const val TIMEOUT_SEC = 60
        private const val UPLOAD_TIMEOUT_SEC = 900
    }
}
