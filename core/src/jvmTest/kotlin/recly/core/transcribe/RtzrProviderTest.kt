@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language

/**
 * docs/08 `rtzr`. Three endpoints and one piece of state: the access token, which lives in the
 * step's own `state_json` so a poll every thirty seconds does not buy a new one each time.
 */
class RtzrProviderTest {
    private val harness = ProviderHarness()
    private val provider = RtzrProvider()

    @Test
    fun `the secret buys a token and the token carries the upload`() = runBlocking {
        harness.server.reply(TOKEN).reply("""{"id":"job-1"}""")

        val submitted = provider.submit(context(), harness.audio)

        assertEquals(Submitted.Polling("job-1"), submitted)
        val auth = harness.server.request(0)
        assertEquals("POST", auth.method)
        assertEquals("${RtzrProvider.BASE}/authenticate", auth.url)
        assertEquals("client_id=vito-id&client_secret=vito-secret", auth.text)
        val upload = harness.server.request(1)
        assertEquals("${RtzrProvider.BASE}/transcribe", upload.url)
        assertEquals("Bearer jwt-1", upload.headers["Authorization"])
        assertTrue("filename=\"joined.m4a\"" in upload.multipartHeaders("file"))
        assertEquals(ProviderHarness.AUDIO, upload.multipartPart("file"))
    }

    @Test
    fun `the config asks for diarization, word timestamps and the default model`() = runBlocking {
        harness.server.reply(TOKEN).reply("""{"id":"job-1"}""")

        provider.submit(context(speakersExpected = 4), harness.audio)

        val config = config(1)
        assertEquals(RtzrProvider.SOMMERS, config["model_name"]?.jsonPrimitive?.content)
        assertEquals("ko", config["language"]?.jsonPrimitive?.content)
        assertEquals(true, config["use_diarization"]?.jsonPrimitive?.boolean)
        assertEquals(true, config["use_word_timestamp"]?.jsonPrimitive?.boolean)
        assertEquals(4, config["diarization"]!!.jsonObject["spk_count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a speaker range that is not one number is left to the provider`() = runBlocking {
        harness.server.reply(TOKEN).reply("""{"id":"job-1"}""")

        provider.submit(context(speakersExpected = null), harness.audio)

        assertNull(config(1)["diarization"])
    }

    @Test
    fun `only Korean stays on sommers`() = runBlocking {
        val expected = mapOf(
            Language.KO to (RtzrProvider.SOMMERS to "ko"),
            // The default model does not speak English, so this one has to change model.
            Language.EN to (RtzrProvider.WHISPER to "en"),
            Language.KO_EN to (RtzrProvider.WHISPER to "multi"),
            Language.AUTO to (RtzrProvider.WHISPER to "detect"),
        )
        expected.forEach { (language, wanted) ->
            val harness = ProviderHarness()
            harness.server.reply(TOKEN).reply("""{"id":"job-1"}""")
            RtzrProvider().submit(
                harness.sttContext(RtzrProvider.NAME, SECRET, language = language),
                harness.audio,
            )
            val config = Json.parseToJsonElement(harness.server.request(1).multipartPart("config")).jsonObject
            assertEquals(wanted.first, config["model_name"]?.jsonPrimitive?.content, "$language model")
            assertEquals(wanted.second, config["language"]?.jsonPrimitive?.content, "$language language")
        }
    }

    @Test
    fun `a token that is still good is spent again instead of bought again`() = runBlocking {
        harness.server.reply(TOKEN).reply("""{"id":"job-1"}""")
        val ctx = context()
        provider.submit(ctx, harness.audio)
        assertEquals(2, harness.server.requests.size)

        // The next pass is a fresh context carrying the state the runner persisted.
        harness.server.reply(TRANSCRIBING)
        val next = context(providerState = ctx.providerState)
        provider.poll(next, "job-1")

        assertEquals(3, harness.server.requests.size)
        assertEquals("Bearer jwt-1", harness.server.request(2).headers["Authorization"])
    }

    @Test
    fun `a token that expires while the job runs is replaced`() = runBlocking {
        harness.server.reply(TOKEN).reply("""{"id":"job-1"}""")
        val ctx = context()
        provider.submit(ctx, harness.audio)

        // START + 6 h is exactly `expire_at`, so the skew alone has already ruled it out.
        harness.clock.advance(6.hours)
        harness.server.reply(SECOND_TOKEN).reply(TRANSCRIBING)
        provider.poll(context(providerState = ctx.providerState), "job-1")

        assertEquals(4, harness.server.requests.size)
        assertEquals("${RtzrProvider.BASE}/authenticate", harness.server.request(2).url)
        assertEquals("Bearer jwt-2", harness.server.request(3).headers["Authorization"])
    }

    @Test
    fun `a running job is pending and a finished one is the transcript`() = runBlocking {
        harness.server.reply(TOKEN).reply(TRANSCRIBING)
        val ctx = context()
        assertEquals(PollResult.Pending, provider.poll(ctx, "job-1"))

        harness.server.reply(COMPLETED)
        val done = assertIs<PollResult.Done>(provider.poll(ctx, "job-1"))

        val result = done.result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.2, result.segments[0].end)
        assertEquals("0", result.segments[0].speaker)
        assertEquals("안녕하세요", result.segments[0].text)
        assertEquals(listOf(SttWord(0.0, 1.2, "안녕하세요")), result.segments[0].words)
        assertEquals("1", result.segments[1].speaker)
        assertEquals("ko", result.language)
        assertEquals(9.1, result.durationSec)
        assertEquals(RtzrProvider.SOMMERS, result.model)
    }

    @Test
    fun `a failed job comes back as data, with the provider's own message`() = runBlocking {
        harness.server.reply(TOKEN)
            .reply("""{"id":"job-1","status":"failed","error":{"code":"E1","message":"bad audio"}}""")

        val failed = assertIs<PollResult.Failed>(provider.poll(context(), "job-1"))

        assertEquals("bad audio", failed.reason)
    }

    @Test
    fun `an unknown status is a provider error rather than a silent success`() = runBlocking {
        harness.server.reply(TOKEN).reply("""{"id":"job-1","status":"paused"}""")

        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "job-1") }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `a secret that is not id colon secret never reaches the network`() = runBlocking {
        val failure = assertFailsWith<StepFailure> {
            provider.submit(harness.sttContext(RtzrProvider.NAME, "just-one-value"), harness.audio)
        }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.AUTH_REJECTED, CoreMessageRef.parse(failure.reason)?.message)
        assertEquals(0, harness.server.requests.size)
    }

    @Test
    fun `a rejected client secret is an auth failure, not a bad upload`() = runBlocking {
        harness.server.reply(status = 401, body = """{"message":"unauthorized"}""")

        val failure = assertFailsWith<StepFailure> { provider.submit(context(), harness.audio) }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.AUTH_REJECTED, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertUploadReason(403, CoreMessage.AUTH_REJECTED, retryable = false)
        assertUploadReason(429, CoreMessage.QUOTA, retryable = true)
        assertUploadReason(500, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertUploadReason(415, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    @Test
    fun `a 429 with Retry-After is parked for exactly that long`() = runBlocking {
        harness.server.reply(TOKEN)
            .reply(status = 429, body = "slow down", headers = mapOf("Retry-After" to "90"))

        val failure = assertFailsWith<StepFailure> { provider.submit(context(), harness.audio) }

        assertEquals(90L, failure.retryAfterSec)
    }

    private suspend fun assertUploadReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(TOKEN).reply(status = status, body = "no")
        val failure = assertFailsWith<StepFailure> {
            RtzrProvider().submit(harness.sttContext(RtzrProvider.NAME, SECRET), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun config(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).multipartPart("config")).jsonObject

    private fun context(
        language: Language = Language.KO,
        speakersExpected: Int? = null,
        providerState: JsonObject? = null,
    ): SttContext = harness.sttContext(
        provider = RtzrProvider.NAME,
        apiKey = SECRET,
        language = language,
        speakersExpected = speakersExpected,
        providerState = providerState,
    )

    private companion object {
        const val SECRET = "vito-id:vito-secret"

        /** `expire_at` is unix seconds; `FakeClock` starts at 2026-08-26T01:00:00Z. */
        const val TOKEN = """{"access_token":"jwt-1","expire_at":1787727600}"""
        const val SECOND_TOKEN = """{"access_token":"jwt-2","expire_at":1787749200}"""
        const val TRANSCRIBING = """{"id":"job-1","status":"transcribing"}"""

        /** The documented shape: `start_at` and `duration` in milliseconds, `spk` an index. */
        const val COMPLETED = """
            {"id":"job-1","status":"completed",
             "results":{"utterances":[
               {"start_at":0,"duration":3200,"msg":"안녕하세요","spk":0,"lang":"ko",
                "words":[{"start_at":0,"duration":1200,"msg":"안녕하세요"}]},
               {"start_at":3600,"duration":5500,"msg":"반갑습니다","spk":1,"lang":"ko"}
             ]}}
        """
    }
}
