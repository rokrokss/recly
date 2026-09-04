package recly.core.transcribe

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path
import recly.core.drive.string
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan

/**
 * Rev AI asynchronous transcription (docs/08 provider table): `POST /speechtotext/v1/jobs` as
 * `multipart/form-data` (`media` + an `options` JSON string) → an id, polled at
 * `GET /speechtotext/v1/jobs/{id}`, and the transcript is a second GET once the job says
 * `transcribed` — done inside the same poll, because a finished ref has nothing to come back for.
 *
 * Diarization is the default here and is turned *off* by a flag, which is why the step's `diarize`
 * arrives inverted; the machine transcriber is the only one this app can wait on, so `transcriber`
 * is pinned rather than taken from the step's `model`.
 */
class RevProvider : SttProvider {
    override val name: String = NAME

    /**
     * Rev documents up to six hours of turnaround for a non-English job. The default two hours
     * would give up on a job that is still coming, re-submit, and bill the same audio again.
     */
    override val resultTimeout: Duration = 8.hours

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val result = Reasons.send(
            ctx.deps,
            "rev.submit",
            HttpPlan(
                method = "POST",
                url = "$BASE/jobs",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "media",
                            contentType = AUDIO_TYPE,
                            source = HttpBody.Multipart.Source.File(file),
                            filename = file.name,
                        ),
                        HttpBody.Multipart.Part(
                            name = "options",
                            contentType = JSON_TYPE,
                            source = HttpBody.Multipart.Source.Bytes(options(ctx).toString().encodeToByteArray()),
                        ),
                    ),
                ),
                timeoutSec = UPLOAD_TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("rev.submit", result, CoreMessage.UNSUPPORTED_AUDIO)
        val id = result.jsonBody()?.string("id")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rev submit gave no id"),
            )
        return Submitted.Polling(id)
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        val result = Reasons.send(
            ctx.deps,
            "rev.poll",
            HttpPlan(
                method = "GET",
                url = "$BASE/jobs/$ref",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("rev.poll", result, CoreMessage.PROVIDER_ERROR)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rev poll gave no JSON"),
            )
        return when (val status = json.string("status")) {
            "transcribed" -> PollResult.Done(transcript(ctx, ref))
            "in_progress" -> PollResult.Pending
            // `failure` is the machine-readable token and `failure_detail` the sentence for a human.
            "failed" -> PollResult.Failed(json.string("failure_detail") ?: json.string("failure") ?: "failed")

            else -> throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rev unknown status '$status'"),
            )
        }
    }

    private suspend fun transcript(ctx: SttContext, ref: String): SttResult {
        val result = Reasons.send(
            ctx.deps,
            "rev.transcript",
            HttpPlan(
                method = "GET",
                url = "$BASE/jobs/$ref/transcript",
                headers = mapOf(
                    "Authorization" to "Bearer ${ctx.apiKey}",
                    // Without this the same URL answers with plain text instead of JSON.
                    "Accept" to TRANSCRIPT_TYPE,
                ),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("rev.transcript", result, CoreMessage.PROVIDER_ERROR)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rev transcript gave no JSON"),
            )
        return read(ctx, json)
    }

    private fun options(ctx: SttContext): JsonObject = buildJsonObject {
        put("transcriber", MACHINE)
        // Always: leaving the field out does not ask this API to detect anything, it falls back
        // to English.
        put("language", languageCode(ctx.step.language))
        // This API takes no speaker count, and it diarizes unless it is told not to.
        put("skip_diarization", !ctx.step.diarize)
    }

    /**
     * docs/08: `ko-en` has no mixed-language code here, and Korean is the half that matters. This
     * provider has no detection mode either, so `auto` is Korean — what it is used for.
     */
    private fun languageCode(language: Language): String = when (language) {
        Language.EN -> "en"
        else -> "ko"
    }

    /**
     * A monologue is already a speaker turn, so it maps onto a segment one for one. Its elements
     * are words and the punctuation between them; only the words carry timestamps, which is why
     * the turn's own start and end are the first and last ones that have any.
     */
    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val segments = json["monologues"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .map { monologue ->
                val elements = monologue["elements"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                val words = elements.filter { it.string("type") == TEXT }
                SttSegment(
                    start = elements.firstNotNullOfOrNull { seconds(it, "ts") } ?: 0.0,
                    end = elements.mapNotNull { seconds(it, "end_ts") }.lastOrNull() ?: 0.0,
                    // An integer index, which the normalizer turns into `S1`, `S2`, …
                    speaker = monologue.string("speaker"),
                    text = text(elements),
                    words = words
                        .map { SttWord(seconds(it, "ts") ?: 0.0, seconds(it, "end_ts") ?: 0.0, value(it)) }
                        .takeIf { it.isNotEmpty() },
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

    /** Punctuation is written against the word before it, so it takes no space of its own. */
    private fun text(elements: List<JsonObject>): String = elements.fold(StringBuilder()) { text, element ->
        if (text.isNotEmpty() && element.string("type") != PUNCT) text.append(' ')
        text.append(value(element))
    }.toString()

    private fun value(element: JsonObject): String = element.string("value").orEmpty()

    private fun seconds(owner: JsonObject, key: String): Double? = owner[key]?.jsonPrimitive?.doubleOrNull

    companion object {
        const val NAME = "rev"
        internal const val BASE = "https://api.rev.ai/speechtotext/v1"
        internal const val TRANSCRIPT_TYPE = "application/vnd.rev.transcript.v1.0+json"

        /** The other transcribers are human ones, and no polling loop can wait for those. */
        private const val MACHINE = "machine"
        private const val TEXT = "text"
        private const val PUNCT = "punct"
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"
        private const val TIMEOUT_SEC = 60
        private const val UPLOAD_TIMEOUT_SEC = 900
    }
}
