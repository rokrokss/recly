package recly.core.transcribe

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.Path
import recly.core.drive.string
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan

/**
 * Gladia (docs/08 provider table): `POST /v2/upload` puts the audio somewhere the API can reach and
 * answers with an `audio_url`, `POST /v2/pre-recorded` starts the job, and `GET /v2/pre-recorded/{id}`
 * carries both the status and — once it says `done` — the transcript itself.
 *
 * The key rides in `x-gladia-key` on every call, including the upload.
 */
class GladiaProvider : SttProvider {
    override val name: String = NAME

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val upload = Reasons.send(
            ctx.deps,
            "gladia.upload",
            HttpPlan(
                method = "POST",
                url = "$BASE/upload",
                headers = mapOf(KEY_HEADER to ctx.apiKey),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "audio",
                            contentType = AUDIO_TYPE,
                            source = HttpBody.Multipart.Source.File(file),
                            filename = file.name,
                        ),
                    ),
                ),
                timeoutSec = UPLOAD_TIMEOUT_SEC,
            ),
        )
        if (upload.status !in 200..299) throw Reasons.failure("gladia.upload", upload, CoreMessage.UNSUPPORTED_AUDIO)
        val audioUrl = upload.jsonBody()?.string("audio_url")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "gladia upload gave no audio_url"),
            )

        val submit = Reasons.send(
            ctx.deps,
            "gladia.submit",
            HttpPlan(
                method = "POST",
                url = "$BASE/pre-recorded",
                headers = mapOf(KEY_HEADER to ctx.apiKey),
                body = HttpBody.Text(request(ctx, audioUrl).toString(), JSON_TYPE),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (submit.status !in 200..299) throw Reasons.failure("gladia.submit", submit, CoreMessage.UNSUPPORTED_AUDIO)
        val id = submit.jsonBody()?.string("id")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "gladia submit gave no id"),
            )
        return Submitted.Polling(id)
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        val result = Reasons.send(
            ctx.deps,
            "gladia.poll",
            HttpPlan(
                method = "GET",
                url = "$BASE/pre-recorded/$ref",
                headers = mapOf(KEY_HEADER to ctx.apiKey),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("gladia.poll", result, CoreMessage.PROVIDER_ERROR)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "gladia poll gave no JSON"),
            )
        return when (val status = json.string("status")) {
            // The finished job answers with the transcript in the same body, so there is no fetch.
            "done" -> PollResult.Done(read(ctx, json))
            "queued", "processing" -> PollResult.Pending
            // The provider's own message is the only thing that says why, so it is kept verbatim.
            "error" -> PollResult.Failed(
                listOfNotNull(json.string("error_code"), json.string("message"))
                    .joinToString(" ")
                    .ifEmpty { "error" },
            )

            else -> throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "gladia unknown status '$status'"),
            )
        }
    }

    private fun request(ctx: SttContext, audioUrl: String): JsonObject = buildJsonObject {
        put("audio_url", audioUrl)
        put("diarization", ctx.step.diarize)
        if (ctx.step.diarize) {
            putJsonObject("diarization_config") {
                // A single number is the stronger hint; the workflow's range is the fallback.
                val exact = speakerCount(ctx)
                if (exact != null) {
                    put("number_of_speakers", exact)
                } else {
                    put("min_speakers", ctx.step.speakers.min)
                    put("max_speakers", ctx.step.speakers.max)
                }
            }
        }
        val languages = languageCodes(ctx.step.language)
        // No `language_config` at all is what asks this API to detect the language.
        if (languages.isNotEmpty()) {
            putJsonObject("language_config") {
                putJsonArray("languages") { languages.forEach { add(it) } }
                // Only `ko-en` asks for a switch mid-recording; anything else is one language.
                put("code_switching", languages.size > 1)
            }
        }
        ctx.step.model?.let { put("model", it) }
    }

    /** `context.participants` collapses the range (docs/08); a range of one number already is one. */
    private fun speakerCount(ctx: SttContext): Int? =
        ctx.speakersExpected ?: ctx.step.speakers.min.takeIf { it == ctx.step.speakers.max }

    private fun languageCodes(language: Language): List<String> = when (language) {
        Language.KO -> listOf("ko")
        Language.EN -> listOf("en")
        Language.KO_EN -> listOf("ko", "en")
        Language.AUTO -> emptyList()
    }

    /** An utterance is already a speaker turn, so it maps onto a segment one for one. */
    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val result = json["result"]?.takeIf { it !is JsonNull }?.jsonObject
        val transcription = result?.get("transcription")?.takeIf { it !is JsonNull }?.jsonObject
        val utterances = transcription?.get("utterances")?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }
        val segments = utterances.map { utterance ->
            SttSegment(
                start = seconds(utterance, "start"),
                end = seconds(utterance, "end"),
                // An integer index, which the normalizer turns into `S1`, `S2`, …
                speaker = utterance.string("speaker"),
                text = utterance.string("text").orEmpty(),
                words = words(utterance),
            )
        }
        return SttResult(
            segments = segments,
            // Every utterance carries the language it was recognised in; detection makes that the
            // only place the answer appears.
            language = utterances.firstOrNull()?.string("language")
                ?: languageCodes(ctx.step.language).firstOrNull(),
            durationSec = result?.get("metadata")?.takeIf { it !is JsonNull }?.jsonObject
                ?.get("audio_duration")?.jsonPrimitive?.doubleOrNull
                ?: segments.lastOrNull()?.end,
            model = ctx.step.model,
        )
    }

    private fun words(utterance: JsonObject): List<SttWord>? =
        utterance["words"]?.takeIf { it !is JsonNull }?.jsonArray
            ?.map { it.jsonObject }
            ?.map { SttWord(seconds(it, "start"), seconds(it, "end"), it.string("word").orEmpty()) }
            ?.takeIf { it.isNotEmpty() }

    private fun seconds(owner: JsonObject, key: String): Double =
        owner[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    companion object {
        const val NAME = "gladia"
        internal const val BASE = "https://api.gladia.io/v2"
        internal const val KEY_HEADER = "x-gladia-key"
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"
        private const val TIMEOUT_SEC = 60
        private const val UPLOAD_TIMEOUT_SEC = 900
    }
}
