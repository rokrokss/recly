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
 * ElevenLabs Scribe (docs/08 provider table, verified against the API reference on 2026-09-02):
 * `POST /v1/speech-to-text` as `multipart/form-data`, keyed by `xi-api-key`, and the transcript
 * comes back on that same request — so [submit] answers [Submitted.Finished] like `clova`.
 *
 * The response has no segments, only a flat word stream, so the turns are cut here: a new one
 * starts wherever the speaker changes or the silence runs past [GAP_SEC].
 */
class ElevenLabsProvider : SttProvider {
    override val name: String = NAME
    override val synchronous: Boolean = true

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val model = ctx.step.model ?: MODEL
        val result = Reasons.send(
            ctx.deps,
            "elevenlabs.speech-to-text",
            HttpPlan(
                method = "POST",
                url = "$BASE/speech-to-text",
                headers = mapOf(KEY_HEADER to ctx.apiKey),
                body = HttpBody.Multipart(parts(ctx, file, model)),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) {
            throw Reasons.failure("elevenlabs.speech-to-text", result, CoreMessage.UNSUPPORTED_AUDIO)
        }
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "elevenlabs gave no JSON"),
            )
        return Submitted.Finished(read(ctx, json, model))
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = throw StepFailure(
        retryable = false,
        reason = CoreMessage.PROVIDER_ERROR.code(detail = "elevenlabs is synchronous and has no '$ref' to poll"),
    )

    private fun parts(ctx: SttContext, file: Path, model: String): List<HttpBody.Multipart.Part> = buildList {
        add(
            HttpBody.Multipart.Part(
                name = "file",
                contentType = AUDIO_TYPE,
                source = HttpBody.Multipart.Source.File(file),
                filename = file.name,
            ),
        )
        add(field("model_id", model))
        languageCode(ctx.step.language)?.let { add(field("language_code", it)) }
        add(field("diarize", ctx.step.diarize.toString()))
        // `num_speakers` is a single number: only honest when the hint is one number to begin with.
        if (ctx.step.diarize) {
            speakerCount(ctx)?.let { add(field("num_speakers", "$it")) }
        }
        // Word timings are what the turn-cutting below is made of, and audio events (`[laughter]`)
        // would land in the transcript as if someone had said them.
        add(field("timestamps_granularity", "word"))
        add(field("tag_audio_events", "false"))
    }

    private fun speakerCount(ctx: SttContext): Int? =
        ctx.speakersExpected ?: ctx.step.speakers.min.takeIf { it == ctx.step.speakers.max }

    private fun field(name: String, value: String): HttpBody.Multipart.Part = HttpBody.Multipart.Part(
        name = name,
        contentType = TEXT_TYPE,
        source = HttpBody.Multipart.Source.Bytes(value.encodeToByteArray()),
    )

    /** docs/08: no mixed-language code, and `auto` is the provider's own detection. */
    private fun languageCode(language: Language): String? = when (language) {
        Language.KO -> "ko"
        Language.EN -> "en"
        Language.KO_EN -> "ko"
        Language.AUTO -> null
    }

    private fun read(ctx: SttContext, json: JsonObject, model: String): SttResult {
        val segments = segments(json)
        return SttResult(
            segments = segments,
            language = json.string("language_code") ?: languageCode(ctx.step.language),
            durationSec = json["audio_duration_secs"]?.jsonPrimitive?.doubleOrNull ?: segments.lastOrNull()?.end,
            model = model,
        )
    }

    /**
     * The word stream, cut into turns. `spacing` tokens are the spaces between words and belong to
     * the text as they come; `audio_event` tokens are noises, not speech, and are dropped.
     */
    private fun segments(json: JsonObject): List<SttSegment> {
        val tokens = json["words"]?.takeIf { it !is JsonNull }?.jsonArray.orEmpty().map { it.jsonObject }
        val segments = mutableListOf<SttSegment>()
        var text = StringBuilder()
        var words = mutableListOf<SttWord>()
        var speaker: String? = null
        var lastEnd: Double? = null

        fun flush() {
            if (words.isEmpty()) return
            segments += SttSegment(
                start = words.first().start,
                end = words.last().end,
                speaker = speaker,
                text = text.toString().trim(),
                words = words.toList(),
            )
            text = StringBuilder()
            words = mutableListOf()
        }

        tokens.forEach { token ->
            when (token.string("type")) {
                AUDIO_EVENT -> Unit

                WORD -> {
                    val start = token.seconds("start")
                    val id = token.string("speaker_id")
                    val gap = lastEnd?.let { start - it } ?: 0.0
                    if (words.isNotEmpty() && (id != speaker || gap >= GAP_SEC)) flush()
                    speaker = id
                    text.append(token.string("text").orEmpty())
                    words += SttWord(start, token.seconds("end"), token.string("text").orEmpty())
                    lastEnd = token.seconds("end")
                }

                // Anything else is spacing, which only matters once a turn is open.
                else -> if (words.isNotEmpty()) text.append(token.string("text").orEmpty())
            }
        }
        flush()
        return segments
    }

    private fun JsonObject.seconds(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    companion object {
        const val NAME = "elevenlabs"
        internal const val BASE = "https://api.elevenlabs.io/v1"
        internal const val KEY_HEADER = "xi-api-key"
        internal const val MODEL = "scribe_v2"

        /** Two seconds of silence is a turn boundary, which is all this API gives us to go on. */
        internal const val GAP_SEC = 2.0

        private const val WORD = "word"
        private const val AUDIO_EVENT = "audio_event"
        private const val TEXT_TYPE = "text/plain"
        private const val AUDIO_TYPE = "audio/mp4"

        /** docs/08: fifteen minutes, because the transcript comes back on this same request. */
        private const val TIMEOUT_SEC = 900
    }
}
