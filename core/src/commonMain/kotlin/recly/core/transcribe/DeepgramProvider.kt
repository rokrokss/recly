package recly.core.transcribe

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import recly.core.drive.string
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Language
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan

/**
 * Deepgram pre-recorded transcription (docs/08 provider table, verified against the API reference
 * on 2026-09-02): `POST /v1/listen` with the audio as the raw request body and every option in the
 * query string, keyed by `Authorization: Token`. Synchronous — the transcript is the response — so
 * [submit] answers [Submitted.Finished] like `clova`.
 *
 * The file goes up as a byte range rather than in memory: a two-hour recording is still tens of
 * megabytes (docs/10).
 */
class DeepgramProvider : SttProvider {
    override val name: String = NAME
    override val synchronous: Boolean = true

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val size = ctx.deps.fileSystem.metadata(file).size
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.UNSUPPORTED_AUDIO.code(detail = "cannot size '$file'"),
            )
        val model = ctx.step.model ?: MODEL
        val result = Reasons.send(
            ctx.deps,
            "deepgram.listen",
            HttpPlan(
                method = "POST",
                url = "$BASE/listen?${query(ctx, model)}",
                headers = mapOf("Authorization" to "Token ${ctx.apiKey}"),
                body = HttpBody.FileRange(file, 0, size, AUDIO_TYPE),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("deepgram.listen", result, CoreMessage.UNSUPPORTED_AUDIO)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "deepgram gave no JSON"),
            )
        return Submitted.Finished(read(ctx, json, model))
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = throw StepFailure(
        retryable = false,
        reason = CoreMessage.PROVIDER_ERROR.code(detail = "deepgram is synchronous and has no '$ref' to poll"),
    )

    /**
     * `utterances` is what turns the word stream into speaker turns, so it is always on; the
     * speaker count is not something this API takes a hint for.
     */
    private fun query(ctx: SttContext, model: String): String = buildList {
        add("model=$model")
        add("smart_format=true")
        add("punctuate=true")
        add("utterances=true")
        // `diarize_model` is the documented switch and picks the model; sending the older `diarize`
        // flag beside it is rejected, so it is this one alone.
        if (ctx.step.diarize) add("diarize_model=latest")
        when (val code = languageCode(ctx.step.language)) {
            null -> add("detect_language=true")
            else -> add("language=$code")
        }
    }.joinToString("&")

    /** docs/08: no mixed-language code, and `auto` is this provider's own detection. */
    private fun languageCode(language: Language): String? = when (language) {
        Language.KO -> "ko"
        Language.EN -> "en"
        Language.KO_EN -> "ko"
        Language.AUTO -> null
    }

    private fun read(ctx: SttContext, json: JsonObject, model: String): SttResult {
        val results = json["results"]?.takeIf { it !is JsonNull }?.jsonObject
        val segments = results?.get("utterances")?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .map { utterance ->
                SttSegment(
                    start = utterance.seconds("start"),
                    end = utterance.seconds("end"),
                    // An integer index, which the normalizer turns into `S1`, `S2`, …
                    speaker = utterance["speaker"]?.jsonPrimitive?.content,
                    text = utterance.string("transcript").orEmpty(),
                    words = words(utterance),
                )
            }
        return SttResult(
            segments = segments,
            // `detect_language` is the only mode that answers with a language of its own.
            language = results?.get("channels")?.takeIf { it !is JsonNull }?.jsonArray
                ?.firstOrNull()?.jsonObject?.string("detected_language")
                ?: languageCode(ctx.step.language),
            durationSec = json["metadata"]?.takeIf { it !is JsonNull }?.jsonObject
                ?.get("duration")?.jsonPrimitive?.doubleOrNull
                ?: segments.lastOrNull()?.end,
            model = model,
        )
    }

    /** `smart_format` writes the punctuated form beside the raw one; the punctuated one is read. */
    private fun words(utterance: JsonObject): List<SttWord>? =
        utterance["words"]?.takeIf { it !is JsonNull }?.jsonArray
            ?.map { it.jsonObject }
            ?.map {
                val text = it.string("punctuated_word") ?: it.string("word").orEmpty()
                SttWord(it.seconds("start"), it.seconds("end"), text)
            }
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.seconds(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    companion object {
        const val NAME = "deepgram"
        internal const val BASE = "https://api.deepgram.com/v1"
        internal const val MODEL = "nova-3"
        private const val AUDIO_TYPE = "audio/mp4"

        /** docs/08: fifteen minutes, because the transcript comes back on this same request. */
        private const val TIMEOUT_SEC = 900
    }
}
