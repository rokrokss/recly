package recly.core.transcribe

import kotlinx.serialization.json.JsonArray
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
 * Naver CLOVA Speech, long-sentence recognition (docs/08 provider table, verified against the API
 * reference on 2026-08-29): `POST {invokeUrl}/recognizer/upload`, `multipart/form-data` with the
 * audio in `media` and the options as a JSON string in `params`, keyed by `X-CLOVASPEECH-API-KEY`.
 *
 * The first **synchronous** provider we spoke to, and the pattern the later ones follow:
 * `completion: "sync"` means the response body is the transcript, so [submit] answers
 * [Submitted.Finished] and [poll] is never reached. The price is a request that can be up to
 * fifteen minutes long, which is why docs/08 recommends `rtzr` or `assemblyai` on a phone — a
 * `BGProcessingTask` does not get fifteen minutes.
 *
 * Cooperative cancellation in the middle of that request costs the whole call: there is no ref to
 * come back to, so the job simply has nothing in `state_json` and the next pass submits again.
 */
class ClovaProvider : SttProvider {
    override val name: String = NAME
    override val synchronous: Boolean = true

    /** docs/08: the `completion: "sync"` call takes two hours of audio and no more. */
    override val limits: SttLimits = SttLimits(maxDurationSec = 2 * 3600.0)

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        // The parser refuses a `clova` step without one, so this is a guard, not a path.
        val invokeUrl = ctx.step.invokeUrl?.trimEnd('/')
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "clova needs invokeUrl"),
            )
        val result = Reasons.send(
            ctx.deps,
            "clova.upload",
            HttpPlan(
                method = "POST",
                url = "$invokeUrl/recognizer/upload",
                headers = mapOf(KEY_HEADER to ctx.apiKey),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "media",
                            contentType = AUDIO_TYPE,
                            source = HttpBody.Multipart.Source.File(file),
                            filename = file.name,
                        ),
                        HttpBody.Multipart.Part(
                            name = "params",
                            contentType = JSON_TYPE,
                            source = HttpBody.Multipart.Source.Bytes(params(ctx).toString().encodeToByteArray()),
                        ),
                    ),
                ),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("clova.upload", result, CoreMessage.UNSUPPORTED_AUDIO)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "clova gave no JSON"),
            )
        // A 200 whose `result` is not COMPLETED is the provider reporting its own failure, and its
        // `message` is the only thing that says why.
        val status = json.string("result")
        if (status != COMPLETED) {
            throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "clova $status ${json.string("message").orEmpty()}"),
            )
        }
        return Submitted.Finished(read(ctx, json))
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = throw StepFailure(
        retryable = false,
        reason = CoreMessage.PROVIDER_ERROR.code(detail = "clova is synchronous and has no '$ref' to poll"),
    )

    private fun params(ctx: SttContext): JsonObject = buildJsonObject {
        put("language", languageCode(ctx.step.language))
        put("completion", "sync")
        put("wordAlignment", true)
        put("fullText", true)
        putJsonObject("diarization") {
            put("enable", ctx.step.diarize)
            if (ctx.step.diarize) {
                // `context.participants` collapses the range to one number (docs/08); without it
                // the workflow's own hint goes through, capped at what the provider accepts.
                put("speakerCountMin", (ctx.speakersExpected ?: ctx.step.speakers.min).coerceIn(1, MAX_SPEAKERS))
                put("speakerCountMax", (ctx.speakersExpected ?: ctx.step.speakers.max).coerceIn(1, MAX_SPEAKERS))
            }
        }
    }

    /** docs/08: this provider has no detection mode, so `auto` is Korean — what it is used for. */
    private fun languageCode(language: Language): String = when (language) {
        Language.KO -> "ko-KR"
        Language.EN -> "en-US"
        Language.KO_EN -> "enko"
        Language.AUTO -> "ko-KR"
    }

    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val segments = json["segments"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .map { segment ->
                SttSegment(
                    start = seconds(segment, "start"),
                    end = seconds(segment, "end"),
                    // Both are documented; `speaker.label` is the one that survives an edit.
                    speaker = segment["speaker"]?.jsonObject?.string("label")
                        ?: segment["diarization"]?.jsonObject?.string("label"),
                    text = segment.string("text").orEmpty(),
                    words = words(segment),
                )
            }
        return SttResult(
            segments = segments,
            // The response echoes no language, so what was asked for is what was heard.
            language = languageCode(ctx.step.language),
            durationSec = segments.lastOrNull()?.end,
            model = null,
        )
    }

    /** `wordAlignment` writes triples — `[startMs, endMs, text]` — rather than objects. */
    private fun words(segment: JsonObject): List<SttWord>? =
        segment["words"]?.takeIf { it !is JsonNull }?.jsonArray
            ?.mapNotNull { it as? JsonArray }
            ?.filter { it.size >= 3 }
            ?.map {
                SttWord(
                    start = (it[0].jsonPrimitive.doubleOrNull ?: 0.0) / MILLIS,
                    end = (it[1].jsonPrimitive.doubleOrNull ?: 0.0) / MILLIS,
                    text = it[2].jsonPrimitive.content,
                )
            }
            ?.takeIf { it.isNotEmpty() }

    /** Every timestamp in this API is milliseconds. */
    private fun seconds(owner: JsonObject, key: String): Double =
        (owner[key]?.jsonPrimitive?.doubleOrNull ?: 0.0) / MILLIS

    companion object {
        const val NAME = "clova"
        internal const val KEY_HEADER = "X-CLOVASPEECH-API-KEY"
        internal const val COMPLETED = "COMPLETED"

        /** docs/08: the speaker hint the provider accepts tops out here. */
        private const val MAX_SPEAKERS = 10
        private const val MILLIS = 1000.0
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"

        /** docs/08: fifteen minutes, because the transcript comes back on this same request. */
        private const val TIMEOUT_SEC = 900
    }
}
