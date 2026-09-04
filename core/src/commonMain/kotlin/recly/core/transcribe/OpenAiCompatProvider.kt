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
 * The four vendors that speak OpenAI's transcription API (docs/08 provider table, verified against
 * their references on 2026-09-02): `POST {base}/audio/transcriptions`, `multipart/form-data`, a
 * bearer key, and the transcript in the response — so [submit] answers [Submitted.Finished] and
 * [poll] is never reached, exactly like `clova`.
 *
 * One class rather than four because only the base URL, the default model and the shape of the
 * diarization request differ; the [Profile] is that difference and nothing else.
 */
class OpenAiCompatProvider(private val profile: Profile) : SttProvider {
    override val name: String = profile.provider
    override val synchronous: Boolean = true
    override val limits: SttLimits = profile.limits

    /**
     * Which vendor this instance talks to. `invokeUrl` overrides [base] for a regional host.
     *
     * Only two of the four publish a ceiling a Recly recording can reach: Groq's depends on the
     * tier, and Together's 80 MB is out of reach at 32 kbps (docs/08 "길이·크기 한도").
     */
    enum class Profile(val provider: String, val base: String, val limits: SttLimits = SttLimits()) {
        OPENAI(OPENAI_NAME, "https://api.openai.com/v1", SttLimits(maxBytes = 26_214_400)),
        GROQ(GROQ_NAME, "https://api.groq.com/openai/v1"),
        TOGETHER(TOGETHER_NAME, "https://api.together.ai/v1", SttLimits(maxDurationSec = 4 * 3600.0)),
        MISTRAL(MISTRAL_NAME, "https://api.mistral.ai/v1"),
    }

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val base = ctx.step.invokeUrl?.trimEnd('/') ?: profile.base
        val model = model(ctx)
        val what = "$name.transcriptions"
        val result = Reasons.send(
            ctx.deps,
            what,
            HttpPlan(
                method = "POST",
                url = "$base/audio/transcriptions",
                headers = mapOf("Authorization" to "Bearer ${ctx.apiKey}"),
                body = HttpBody.Multipart(parts(ctx, file, model)),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure(what, result, CoreMessage.UNSUPPORTED_AUDIO)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "$name gave no JSON"),
            )
        return Submitted.Finished(read(ctx, json, model))
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult = throw StepFailure(
        retryable = false,
        reason = CoreMessage.PROVIDER_ERROR.code(detail = "$name is synchronous and has no '$ref' to poll"),
    )

    /** The step's own choice wins; otherwise the vendor's default for what was asked for. */
    private fun model(ctx: SttContext): String = ctx.step.model ?: when (profile) {
        // The diarizing model is a different model, not a flag, so the default follows `diarize`.
        Profile.OPENAI -> if (ctx.step.diarize) GPT_4O_DIARIZE else WHISPER_1
        Profile.GROQ -> "whisper-large-v3-turbo"
        Profile.TOGETHER -> "openai/whisper-large-v3"
        Profile.MISTRAL -> "voxtral-mini-latest"
    }

    private fun parts(ctx: SttContext, file: Path, model: String): List<HttpBody.Multipart.Part> = buildList {
        add(
            HttpBody.Multipart.Part(
                name = "file",
                contentType = AUDIO_TYPE,
                source = HttpBody.Multipart.Source.File(file),
                filename = file.name,
            ),
        )
        add(field("model", model))
        languageCode(ctx.step.language)?.let { add(field("language", it)) }
        when (profile) {
            // OpenAI has no diarization switch: the model decides, and each model answers in the
            // one format it knows.
            Profile.OPENAI -> when {
                DIARIZE in model -> {
                    add(field("response_format", DIARIZED_JSON))
                    add(field("chunking_strategy", "auto"))
                }

                model.startsWith(WHISPER) -> {
                    add(field("response_format", VERBOSE_JSON))
                    add(field("timestamp_granularities[]", "segment"))
                }

                else -> add(field("response_format", PLAIN_JSON))
            }

            // Groq does not diarize at all, so `diarize` is silently ignored here (docs/08).
            Profile.GROQ -> add(field("response_format", VERBOSE_JSON))

            Profile.TOGETHER -> {
                add(field("diarize", ctx.step.diarize.toString()))
                add(field("response_format", VERBOSE_JSON))
                add(field("timestamp_granularities", "segment"))
                // This one takes a range, so `context.participants` collapses it to one number.
                if (ctx.step.diarize) {
                    add(field("min_speakers", "${ctx.speakersExpected ?: ctx.step.speakers.min}"))
                    add(field("max_speakers", "${ctx.speakersExpected ?: ctx.step.speakers.max}"))
                }
            }

            Profile.MISTRAL -> {
                add(field("diarize", ctx.step.diarize.toString()))
                add(field("timestamp_granularities", "segment"))
            }
        }
    }

    private fun field(name: String, value: String): HttpBody.Multipart.Part = HttpBody.Multipart.Part(
        name = name,
        contentType = TEXT_TYPE,
        source = HttpBody.Multipart.Source.Bytes(value.encodeToByteArray()),
    )

    /** docs/08: none of the four has a mixed-language code, and `auto` is their own detection. */
    private fun languageCode(language: Language): String? = when (language) {
        Language.KO -> "ko"
        Language.EN -> "en"
        Language.KO_EN -> "ko"
        Language.AUTO -> null
    }

    /**
     * Four response shapes, one reader: `diarized_json` and Mistral carry a speaker on each
     * segment, `verbose_json` does not, Together answers `speaker_segments` when it diarized, and
     * a plain `json` has nothing but the text.
     */
    private fun read(ctx: SttContext, json: JsonObject, model: String): SttResult {
        val array = json.array("speaker_segments") ?: json.array("segments")
        val segments = if (array == null) plain(ctx, json) else {
            // `verbose_json` puts the words beside the segments rather than inside them.
            val loose = words(json)
            array.map { it.jsonObject }.mapIndexed { index, segment ->
                val start = segment.seconds("start")
                val end = segment.seconds("end")
                SttSegment(
                    start = start,
                    end = end,
                    speaker = segment.string("speaker") ?: segment.string("speaker_id"),
                    text = segment.string("text").orEmpty(),
                    words = words(segment) ?: loose?.within(start, end, last = index == array.size - 1),
                )
            }
        }
        return SttResult(
            segments = segments,
            // The response echoes what it detected; without one, what was asked for is what it heard.
            language = json.string("language") ?: languageCode(ctx.step.language),
            durationSec = json["duration"]?.jsonPrimitive?.doubleOrNull ?: usageSeconds(json)
                ?: segments.lastOrNull()?.end ?: ctx.audioDurationSec,
            model = model,
        )
    }

    /**
     * A plain `json` answer is one undivided block of text. Its length comes from whatever the
     * response says about the audio — and when the usage is counted in tokens, as it is on every
     * `gpt-*-transcribe` model, from the recording itself, so the segment does not collapse to
     * `0.0..0.0` and take the whole transcript's timeline with it.
     */
    private fun plain(ctx: SttContext, json: JsonObject): List<SttSegment> {
        val text = json.string("text").orEmpty()
        if (text.isEmpty()) return emptyList()
        val end = usageSeconds(json) ?: ctx.audioDurationSec ?: 0.0
        return listOf(SttSegment(start = 0.0, end = end, speaker = null, text = text))
    }

    /** Mistral bills by audio seconds and reports them where a `duration` would otherwise be. */
    private fun usageSeconds(json: JsonObject): Double? = json["usage"]?.jsonObject?.let { usage ->
        usage["prompt_audio_seconds"]?.jsonPrimitive?.doubleOrNull ?: usage["seconds"]?.jsonPrimitive?.doubleOrNull
    }

    /** A word belongs to the segment its start falls in; the last segment also takes its own end. */
    private fun List<SttWord>.within(start: Double, end: Double, last: Boolean): List<SttWord>? =
        filter { it.start >= start && (it.start < end || (last && it.start <= end)) }.takeIf { it.isNotEmpty() }

    private fun words(owner: JsonObject): List<SttWord>? = owner.array("words")
        ?.map { it.jsonObject }
        ?.map { SttWord(it.seconds("start"), it.seconds("end"), it.string("word").orEmpty()) }
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.array(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonArray

    /** Every timestamp in these APIs is already seconds. */
    private fun JsonObject.seconds(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    companion object {
        const val OPENAI_NAME = "openai"
        const val GROQ_NAME = "groq"
        const val TOGETHER_NAME = "together"
        const val MISTRAL_NAME = "mistral"

        fun openai(): OpenAiCompatProvider = OpenAiCompatProvider(Profile.OPENAI)

        fun groq(): OpenAiCompatProvider = OpenAiCompatProvider(Profile.GROQ)

        fun together(): OpenAiCompatProvider = OpenAiCompatProvider(Profile.TOGETHER)

        fun mistral(): OpenAiCompatProvider = OpenAiCompatProvider(Profile.MISTRAL)

        internal const val GPT_4O_DIARIZE = "gpt-4o-transcribe-diarize"
        internal const val WHISPER_1 = "whisper-1"
        internal const val DIARIZED_JSON = "diarized_json"
        internal const val VERBOSE_JSON = "verbose_json"
        internal const val PLAIN_JSON = "json"

        private const val DIARIZE = "diarize"
        private const val WHISPER = "whisper"
        private const val TEXT_TYPE = "text/plain"
        private const val AUDIO_TYPE = "audio/mp4"

        /** docs/08: fifteen minutes, because the transcript comes back on this same request. */
        private const val TIMEOUT_SEC = 900
    }
}
