@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import io.ktor.http.encodeURLParameter
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * Return Zero (리턴제로) VITO STT (docs/08 provider table, verified against the developer docs on
 * 2026-08-29): the secret is `{clientId}:{clientSecret}`, which buys a six-hour access token from
 * `POST /v1/authenticate`; the audio goes to `POST /v1/transcribe` as `multipart/form-data`
 * (`file` + a `config` JSON string) and the job is polled at `GET /v1/transcribe/{id}`.
 *
 * The token is cached in the step's own state ([SttContext.providerState]) rather than fetched per
 * call: a two-hour transcription polled every thirty seconds would otherwise authenticate 240
 * times for one recording.
 */
class RtzrProvider : SttProvider {
    override val name: String = NAME

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val result = Reasons.send(
            ctx.deps,
            "rtzr.transcribe",
            HttpPlan(
                method = "POST",
                url = "$BASE/transcribe",
                headers = mapOf("Authorization" to "Bearer ${token(ctx)}"),
                body = HttpBody.Multipart(
                    listOf(
                        HttpBody.Multipart.Part(
                            name = "file",
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
        if (result.status !in 200..299) throw Reasons.failure("rtzr.transcribe", result, CoreMessage.UNSUPPORTED_AUDIO)
        val id = result.jsonBody()?.string("id")
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rtzr submit gave no id"),
            )
        return Submitted.Polling(id)
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        val result = Reasons.send(
            ctx.deps,
            "rtzr.poll",
            HttpPlan(
                method = "GET",
                url = "$BASE/transcribe/$ref",
                headers = mapOf("Authorization" to "Bearer ${token(ctx)}"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) throw Reasons.failure("rtzr.poll", result, CoreMessage.PROVIDER_ERROR)
        val json = result.jsonBody()
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rtzr poll gave no JSON"),
            )
        return when (val status = json.string("status")) {
            "completed" -> PollResult.Done(read(ctx, json))
            "transcribing" -> PollResult.Pending
            // The provider's own message is the only thing that says why, so it is kept verbatim.
            "failed" -> PollResult.Failed(json["error"]?.jsonObject?.string("message") ?: "failed")

            else -> throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rtzr unknown status '$status'"),
            )
        }
    }

    /**
     * The cached token if it is still good, a fresh one otherwise. [SKEW_SEC] keeps a token that
     * expires while the request is in flight from being spent.
     */
    private suspend fun token(ctx: SttContext): String {
        val cached = ctx.providerState
            ?.let { runCatching { providerJson.decodeFromJsonElement(Token.serializer(), it) }.getOrNull() }
        val now = ctx.deps.clock.now().epochSeconds
        if (cached != null && cached.expiresAt - SKEW_SEC > now) return cached.token

        val (clientId, clientSecret) = ctx.apiKey.split(':', limit = 2)
            .takeIf { it.size == 2 && it.all(String::isNotEmpty) }
            ?.let { it[0] to it[1] }
            ?: throw StepFailure(
                retryable = false,
                reason = CoreMessage.AUTH_REJECTED.code(detail = "rtzr secret must be '{clientId}:{clientSecret}'"),
            )
        val form = "client_id=${clientId.encodeURLParameter()}&client_secret=${clientSecret.encodeURLParameter()}"
        val result = Reasons.send(
            ctx.deps,
            "rtzr.authenticate",
            HttpPlan(
                method = "POST",
                url = "$BASE/authenticate",
                body = HttpBody.Text(form, FORM_TYPE),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        // A rejected client id or secret is not a bad request about the audio: it is the key.
        if (result.status !in 200..299) throw Reasons.failure("rtzr.authenticate", result, CoreMessage.AUTH_REJECTED)
        val json = result.jsonBody()
        val token = json?.string("access_token")
        val expiresAt = json?.get("expire_at")?.jsonPrimitive?.longOrNull
        if (token == null || expiresAt == null) {
            throw StepFailure(
                retryable = true,
                reason = CoreMessage.PROVIDER_ERROR.code(detail = "rtzr authenticate gave no token"),
            )
        }
        ctx.providerState = providerJson.encodeToJsonElement(Token.serializer(), Token(token, expiresAt)).jsonObject
        return token
    }

    /**
     * docs/08 language mapping. `sommers` is the default model but only speaks Korean and
     * Japanese, so everything else routes to `whisper` — which is also the only model that takes
     * `multi` (한영 혼용) and `detect`.
     */
    private fun config(ctx: SttContext): JsonObject = buildJsonObject {
        val language = when (ctx.step.language) {
            Language.KO -> "ko"
            Language.EN -> "en"
            Language.KO_EN -> "multi"
            Language.AUTO -> "detect"
        }
        put("model_name", modelName(ctx))
        put("language", language)
        put("use_diarization", ctx.step.diarize)
        // `spk_count` is a single number: only honest when the hint is one number to begin with.
        if (ctx.step.diarize) {
            ctx.speakersExpected?.let { putJsonObject("diarization") { put("spk_count", it) } }
        }
        put("use_word_timestamp", true)
    }

    /** The step's own choice wins; otherwise the model that speaks the language that was asked for. */
    private fun modelName(ctx: SttContext): String =
        ctx.step.model ?: if (ctx.step.language == Language.KO) SOMMERS else WHISPER

    private fun read(ctx: SttContext, json: JsonObject): SttResult {
        val utterances = json["results"]?.takeIf { it !is JsonNull }?.jsonObject
            ?.get("utterances")?.takeIf { it !is JsonNull }?.jsonArray.orEmpty()
            .map { it.jsonObject }
        val segments = utterances.map { utterance ->
            val start = millis(utterance, "start_at")
            SttSegment(
                start = start,
                end = start + millis(utterance, "duration"),
                // An integer index, which the normalizer turns into `S1`, `S2`, …
                speaker = utterance["spk"]?.jsonPrimitive?.content,
                text = utterance.string("msg").orEmpty(),
                words = words(utterance),
            )
        }
        return SttResult(
            segments = segments,
            // Every utterance carries the language it was recognised in; `detect` makes that the
            // only place the answer appears.
            language = utterances.firstOrNull()?.string("lang"),
            durationSec = segments.lastOrNull()?.end,
            model = modelName(ctx),
        )
    }

    /**
     * `use_word_timestamp` adds a `words` array to each utterance. The published reference does not
     * spell its entries out, so only the two shapes it could reasonably have are read and anything
     * else leaves the segment without words — which the normalizer already allows for.
     */
    private fun words(utterance: JsonObject): List<SttWord>? =
        utterance["words"]?.takeIf { it !is JsonNull }?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { word ->
                val text = word.string("msg") ?: word.string("text") ?: return@mapNotNull null
                val start = millis(word, "start_at")
                SttWord(start, start + millis(word, "duration"), text)
            }
            ?.takeIf { it.isNotEmpty() }

    /** Every timestamp in this API is milliseconds. */
    private fun millis(owner: JsonObject, key: String): Double =
        (owner[key]?.jsonPrimitive?.doubleOrNull ?: 0.0) / MILLIS

    /** What survives in `step_run.state_json` between passes: nothing but the token and its clock. */
    @Serializable
    private data class Token(val token: String, val expiresAt: Long)

    companion object {
        const val NAME = "rtzr"
        internal const val BASE = "https://openapi.vito.ai/v1"
        internal const val SOMMERS = "sommers"
        internal const val WHISPER = "whisper"

        /** A token that would expire mid-request is treated as already expired. */
        internal const val SKEW_SEC = 60L
        private const val MILLIS = 1000.0
        private const val JSON_TYPE = "application/json"
        private const val AUDIO_TYPE = "audio/mp4"
        private const val FORM_TYPE = "application/x-www-form-urlencoded"
        private const val TIMEOUT_SEC = 60
        private const val UPLOAD_TIMEOUT_SEC = 900
    }
}
