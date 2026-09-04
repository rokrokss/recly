package recly.core.transcribe

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult

/**
 * AssemblyAI (docs/08 provider table, verified against the API reference on 2026-08-29):
 * `POST /v2/upload` with the raw bytes → `upload_url`, `POST /v2/transcript` → `id`,
 * `GET /v2/transcript/{id}` → `queued` / `processing` / `completed` / `error`.
 *
 * The model is pinned to `universal-2`: it is the newest one that speaks Korean, so the step's
 * `model` field does not apply here.
 */
class AssemblyAiProvider : SttProvider {
    override val name: String = NAME

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val size = ctx.deps.fileSystem.metadata(file).size
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.UNSUPPORTED_AUDIO.code(detail = "cannot size '$file'"),
            )
        val upload = Reasons.send(
            ctx.deps,
            "assemblyai.upload",
            HttpPlan(
                method = "POST",
                url = "$BASE/upload",
                headers = mapOf("authorization" to ctx.apiKey),
                body = HttpBody.FileRange(file, 0, size, AUDIO_TYPE),
                timeoutSec = UPLOAD_TIMEOUT_SEC,
            ),
        )
        if (upload.status !in 200..299) {
            throw Reasons.failure("assemblyai.upload", upload, CoreMessage.UNSUPPORTED_AUDIO)
        }
        val audioUrl = upload.json()?.string("upload_url")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "upload gave no upload_url"),
            )

        val submit = Reasons.send(
            ctx.deps,
            "assemblyai.submit",
            HttpPlan(
                method = "POST",
                url = "$BASE/transcript",
                headers = mapOf("authorization" to ctx.apiKey),
                body = HttpBody.Text(request(ctx, audioUrl).toString(), JSON_TYPE),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (submit.status !in 200..299) {
            throw Reasons.failure("assemblyai.submit", submit, CoreMessage.UNSUPPORTED_AUDIO)
        }
        val id = submit.json()?.string("id")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "submit gave no id"),
            )
        return Submitted.Polling(id)
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        val result = Reasons.send(
            ctx.deps,
            "assemblyai.poll",
            HttpPlan(
                method = "GET",
                url = "$BASE/transcript/$ref",
                headers = mapOf("authorization" to ctx.apiKey),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("assemblyai.poll", result, CoreMessage.PROVIDER_ERROR)
        val json = result.json()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "poll gave no JSON"),
            )
        return when (val status = json.string("status")) {
            "completed" -> PollResult.Done(read(json))
            "queued", "processing" -> PollResult.Pending
            // The provider's own message is the only thing that says why, so it is kept verbatim.
            "error" -> PollResult.Failed(json.string("error") ?: "failed")

            else -> throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "assemblyai unknown status '$status'"),
            )
        }
    }

    /** `speakers_expected` is a single number, so it is only honest when min and max agree. */
    private fun request(ctx: SttContext, audioUrl: String): JsonObject = buildJsonObject {
        put("audio_url", audioUrl)
        putJsonArray("speech_models") { add(MODEL) }
        when (ctx.step.language) {
            Language.AUTO -> put("language_detection", true)
            else -> put("language_code", languageCode(ctx.step.language))
        }
        put("speaker_labels", ctx.step.diarize)
        if (ctx.step.diarize) ctx.speakersExpected?.let { put("speakers_expected", it) }
    }

    /** docs/08: `ko-en` has no mixed-language code here, and Korean is the half that matters. */
    private fun languageCode(language: Language): String = when (language) {
        Language.EN -> "en"
        else -> "ko"
    }

    /**
     * `utterances` is what `speaker_labels` produces — one entry per speaker turn, with its own
     * words. Without diarization the response has no utterances, only the flat `text`.
     */
    private fun read(json: JsonObject): SttResult {
        val utterances = json["utterances"]?.takeIf { it !is JsonNull }?.jsonArray
        val segments = if (utterances != null && utterances.isNotEmpty()) {
            utterances.map { element ->
                val utterance = element.jsonObject
                SttSegment(
                    start = seconds(utterance, "start"),
                    end = seconds(utterance, "end"),
                    speaker = utterance.string("speaker"),
                    text = utterance.string("text").orEmpty(),
                    words = words(utterance),
                )
            }
        } else {
            val text = json.string("text").orEmpty()
            if (text.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    SttSegment(
                        start = 0.0,
                        end = json["audio_duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        speaker = null,
                        text = text,
                        words = words(json),
                    ),
                )
            }
        }
        return SttResult(
            segments = segments,
            language = json.string("language_code"),
            durationSec = json["audio_duration"]?.jsonPrimitive?.doubleOrNull,
            model = MODEL,
        )
    }

    private fun words(owner: JsonObject): List<SttWord>? =
        owner["words"]?.takeIf { it !is JsonNull }?.jsonArray
            ?.map { it.jsonObject }
            ?.map { SttWord(seconds(it, "start"), seconds(it, "end"), it.string("text").orEmpty()) }
            ?.takeIf { it.isNotEmpty() }

    /** Every timestamp in this API is milliseconds. */
    private fun seconds(owner: JsonObject, key: String): Double =
        (owner[key]?.jsonPrimitive?.doubleOrNull ?: 0.0) / 1000.0

    private fun HttpResult.json(): JsonObject? =
        body.decodeToString().takeIf { it.isNotBlank() }
            ?.let { runCatching { providerJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val NAME = "assemblyai"
        internal const val BASE = "https://api.assemblyai.com/v2"
        internal const val MODEL = "universal-2"
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "application/octet-stream"
        private const val TIMEOUT_SEC = 60
        private const val UPLOAD_TIMEOUT_SEC = 900
    }
}
