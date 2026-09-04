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
 * Azure AI Speech fast transcription (docs/08 provider table, verified against the API reference on
 * 2026-09-02): `POST {invokeUrl}/speechtotext/transcriptions:transcribe`, `multipart/form-data`
 * with the audio in `audio` and the options as a JSON string in `definition`, keyed by
 * `Ocp-Apim-Subscription-Key`. Synchronous, so [submit] answers [Submitted.Finished] like `clova`.
 *
 * The endpoint belongs to the customer's own resource, which is why `invokeUrl` is required here
 * rather than being a regional override.
 */
class AzureProvider : SttProvider {
    override val name: String = NAME
    override val synchronous: Boolean = true

    /** docs/08: Fast transcription takes five hours of audio. */
    override val limits: SttLimits = SttLimits(maxDurationSec = 5 * 3600.0)

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        // The parser refuses an `azure` step without one, so this is a guard, not a path.
        val invokeUrl = ctx.step.invokeUrl?.trimEnd('/')
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "azure needs invokeUrl"),
            )
        val result = Reasons.send(
            ctx.deps,
            "azure.transcribe",
            HttpPlan(
                method = "POST",
                url = "$invokeUrl/speechtotext/transcriptions:transcribe?api-version=$API_VERSION",
                headers = mapOf(KEY_HEADER to ctx.apiKey),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "audio",
                            contentType = AUDIO_TYPE,
                            source = HttpBody.Multipart.Source.File(file),
                            filename = file.name,
                        ),
                        HttpBody.Multipart.Part(
                            name = "definition",
                            contentType = JSON_TYPE,
                            source = HttpBody.Multipart.Source.Bytes(
                                definition(ctx).toString().encodeToByteArray(),
                            ),
                        ),
                    ),
                ),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("azure.transcribe", result, CoreMessage.UNSUPPORTED_AUDIO)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "azure gave no JSON"),
            )
        return Submitted.Finished(read(ctx, json))
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = throw StepFailure(
        retryable = false,
        reason = CoreMessage.PROVIDER_ERROR.code(detail = "azure is synchronous and has no '$ref' to poll"),
    )

    /** An empty `locales` is how this API is told to detect the language itself. */
    private fun definition(ctx: SttContext): JsonObject = buildJsonObject {
        putJsonArray("locales") { locales(ctx.step.language).forEach { add(it) } }
        // The whole object is left out when diarization is off: `enabled: false` plus a speaker
        // count would be a request for something we are not asking for.
        if (ctx.step.diarize) {
            putJsonObject("diarization") {
                put("enabled", true)
                // The API takes 2..35 and rejects anything else, so a one-participant recording
                // asks for the smallest range it will accept rather than for one speaker.
                put("maxSpeakers", (ctx.speakersExpected ?: ctx.step.speakers.max).coerceIn(MIN_SPEAKERS, MAX_SPEAKERS))
            }
        }
    }

    /** docs/08: the one provider that takes both halves of `ko-en` as locales of their own. */
    private fun locales(language: Language): List<String> = when (language) {
        Language.KO -> listOf(KO)
        Language.EN -> listOf(EN)
        Language.KO_EN -> listOf(KO, EN)
        Language.AUTO -> emptyList()
    }

    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val phrases = json["phrases"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().map { it.jsonObject }
        val segments = phrases.map { phrase ->
            val start = phrase.millis("offsetMilliseconds")
            SttSegment(
                start = start,
                end = start + phrase.millis("durationMilliseconds"),
                // An integer index, which the normalizer turns into `S1`, `S2`, …
                speaker = phrase["speaker"]?.jsonPrimitive?.content,
                text = phrase.string("text").orEmpty(),
                words = words(phrase),
            )
        }
        return SttResult(
            segments = segments,
            // Each phrase carries the locale it was recognised in; with an empty `locales` that is
            // the only place the answer appears.
            language = phrases.firstOrNull()?.string("locale") ?: locales(ctx.step.language).firstOrNull(),
            durationSec = json["durationMilliseconds"]?.jsonPrimitive?.doubleOrNull?.div(MILLIS)
                ?: segments.lastOrNull()?.end,
            model = null,
        )
    }

    private fun words(phrase: JsonObject): List<SttWord>? =
        phrase["words"]?.takeIf { it !is JsonNull }?.jsonArray
            ?.map { it.jsonObject }
            ?.map {
                val start = it.millis("offsetMilliseconds")
                SttWord(start, start + it.millis("durationMilliseconds"), it.string("text").orEmpty())
            }
            ?.takeIf { it.isNotEmpty() }

    /** Every timestamp in this API is milliseconds. */
    private fun JsonObject.millis(key: String): Double = (this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0) / MILLIS

    companion object {
        const val NAME = "azure"
        internal const val KEY_HEADER = "Ocp-Apim-Subscription-Key"
        internal const val API_VERSION = "2025-10-15"
        internal const val KO = "ko-KR"
        internal const val EN = "en-US"

        /** docs/08: the speaker ceiling this API accepts, either end of it. */
        private const val MIN_SPEAKERS = 2
        private const val MAX_SPEAKERS = 35
        private const val MILLIS = 1000.0
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"

        /** docs/08: fifteen minutes, because the transcript comes back on this same request. */
        private const val TIMEOUT_SEC = 900
    }
}
