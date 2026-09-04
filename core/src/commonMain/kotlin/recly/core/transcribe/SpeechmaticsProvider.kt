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
 * Speechmatics batch transcription (docs/08 provider table): `POST /v2/jobs` as
 * `multipart/form-data` (`data_file` + a `config` JSON string) → an id, polled at
 * `GET /v2/jobs/{id}`, and the transcript is a second GET once the job says `done`.
 *
 * Both GETs happen inside the same poll, because a ref that has reached `done` has nothing left to
 * come back for — the runner would only park the job for another interval to fetch a finished file.
 *
 * The default host is the EU one; `invokeUrl` moves the job to another region (`us1`, `au1`) or a
 * self-hosted deployment.
 */
class SpeechmaticsProvider : SttProvider {
    override val name: String = NAME

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val result = Reasons.send(
            ctx.deps,
            "speechmatics.submit",
            HttpPlan(
                method = "POST",
                url = "${base(ctx)}/jobs",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "data_file",
                            contentType = AUDIO_TYPE,
                            source = HttpBody.Multipart.Source.File(file),
                            filename = file.name,
                        ),
                        HttpBody.Multipart.Part(
                            name = "config",
                            contentType = JSON_TYPE,
                            source = HttpBody.Multipart.Source.Bytes(config(ctx).toString().encodeToByteArray()),
                        ),
                    ),
                ),
                timeoutSec = UPLOAD_TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) {
            throw Reasons.failure("speechmatics.submit", result, CoreMessage.UNSUPPORTED_AUDIO)
        }
        val id = result.jsonBody()?.string("id")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "speechmatics submit gave no id"),
            )
        return Submitted.Polling(id)
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        val result = Reasons.send(
            ctx.deps,
            "speechmatics.poll",
            HttpPlan(
                method = "GET",
                url = "${base(ctx)}/jobs/$ref",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) {
            throw Reasons.failure("speechmatics.poll", result, CoreMessage.PROVIDER_ERROR)
        }
        val job = result.jsonBody()?.get("job")?.takeIf { it !is JsonNull }?.jsonObject
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "speechmatics poll gave no job"),
            )
        return when (val status = job.string("status")) {
            "done" -> PollResult.Done(transcript(ctx, ref))
            "running" -> PollResult.Pending
            // The provider's own message is the only thing that says why, so it is kept verbatim.
            "rejected" -> PollResult.Failed(errors(job))
            // A job that has been deleted or has aged out leaves nothing to fetch: the ref is
            // worthless, so it goes back as data and the retry submits the audio again.
            "deleted", "expired" -> PollResult.Failed(status)

            else -> throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "speechmatics unknown status '$status'"),
            )
        }
    }

    private suspend fun transcript(ctx: SttContext, ref: String): SttResult {
        val result = Reasons.send(
            ctx.deps,
            "speechmatics.transcript",
            HttpPlan(
                method = "GET",
                url = "${base(ctx)}/jobs/$ref/transcript?format=$TRANSCRIPT_FORMAT",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) {
            throw Reasons.failure("speechmatics.transcript", result, CoreMessage.PROVIDER_ERROR)
        }
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "speechmatics transcript gave no JSON"),
            )
        return read(ctx, json)
    }

    /** The default region, or the one the step names — a trailing slash would double up on `/jobs`. */
    private fun base(ctx: SttContext): String = ctx.step.invokeUrl?.trimEnd('/') ?: BASE

    private fun config(ctx: SttContext): JsonObject = buildJsonObject {
        put("type", "transcription")
        putJsonObject("transcription_config") {
            put("language", languageCode(ctx.step.language))
            put("operating_point", operatingPoint(ctx))
            // This API takes no speaker count at all: diarization is on or it is off.
            put("diarization", if (ctx.step.diarize) "speaker" else "none")
        }
    }

    private fun operatingPoint(ctx: SttContext): String = ctx.step.model ?: ENHANCED

    private fun languageCode(language: Language): String = when (language) {
        Language.KO -> "ko"
        Language.EN -> "en"
        // docs/08: no mixed-language code here, and Korean is the half that matters.
        Language.KO_EN -> "ko"
        // The documented value that turns language identification on; what it heard comes back
        // on the tokens themselves.
        Language.AUTO -> "auto"
    }

    /** A rejection carries a list of `{message}` objects; only the sentences are worth keeping. */
    private fun errors(job: JsonObject): String =
        job["errors"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("message") }
            .joinToString("; ")
            .ifEmpty { "rejected" }

    /**
     * One flat token list: words carry the speaker and punctuation attaches to the token before it.
     * A turn ends where the speaker changes — `UU` is this provider's own label for "unknown", and
     * it goes through like any other, because inventing a name for it is the normalizer's job.
     */
    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val tokens = json["results"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().map { it.jsonObject }
        val turns = mutableListOf<MutableList<JsonObject>>()
        tokens.forEach { token ->
            val current = turns.lastOrNull()
            if (current != null && speaker(current.last()) == speaker(token)) {
                current += token
            } else {
                turns += mutableListOf(token)
            }
        }
        val segments = turns.map { turn ->
            SttSegment(
                start = seconds(turn.first(), "start_time"),
                end = seconds(turn.last(), "end_time"),
                speaker = speaker(turn.first()),
                text = text(turn),
                words = turn.filter { it.string("type") != PUNCTUATION }
                    .map { SttWord(seconds(it, "start_time"), seconds(it, "end_time"), content(it)) }
                    .takeIf { it.isNotEmpty() },
            )
        }
        return SttResult(
            segments = segments,
            // With identification on, the language it settled on is written on the tokens; the
            // config it echoes back only ever repeats what was asked for.
            language = tokens.firstNotNullOfOrNull { alternative(it)?.string("language") }
                ?: json["metadata"]?.takeIf { it !is JsonNull }?.jsonObject
                    ?.get("transcription_config")?.takeIf { it !is JsonNull }?.jsonObject
                    ?.string("language")
                ?: languageCode(ctx.step.language),
            durationSec = segments.lastOrNull()?.end,
            model = operatingPoint(ctx),
        )
    }

    /** Punctuation is written against the word before it, so it takes no space of its own. */
    private fun text(turn: List<JsonObject>): String = turn.fold(StringBuilder()) { text, token ->
        if (text.isNotEmpty() && token.string("type") != PUNCTUATION) text.append(' ')
        text.append(content(token))
    }.toString()

    /** The alternatives are ranked; the first is the one this provider stands behind. */
    private fun alternative(token: JsonObject): JsonObject? =
        token["alternatives"]?.takeIf { it !is JsonNull }?.jsonArray?.firstOrNull() as? JsonObject

    private fun content(token: JsonObject): String = alternative(token)?.string("content").orEmpty()

    private fun speaker(token: JsonObject): String? = alternative(token)?.string("speaker")

    private fun seconds(owner: JsonObject, key: String): Double =
        owner[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    companion object {
        const val NAME = "speechmatics"
        internal const val BASE = "https://eu1.asr.api.speechmatics.com/v2"
        internal const val ENHANCED = "enhanced"

        /** The only JSON the transcript endpoint offers; `txt` and `srt` are the other two. */
        internal const val TRANSCRIPT_FORMAT = "json-v2"
        private const val PUNCTUATION = "punctuation"
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"
        private const val TIMEOUT_SEC = 60
        private const val UPLOAD_TIMEOUT_SEC = 900
    }
}
