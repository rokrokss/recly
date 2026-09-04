package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language

/**
 * docs/08 `rev`. The one provider that diarizes unless it is told not to, so the step's `diarize`
 * goes out inverted as `skip_diarization`.
 */
class RevProviderTest {
    private val harness = ProviderHarness()
    private val provider = RevProvider()

    @Test
    fun `the audio and the options go up as one multipart upload`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"in_progress"}""")

        val submitted = provider.submit(context(), harness.audio)

        assertEquals(Submitted.Polling("job-1"), submitted)
        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals("${RevProvider.BASE}/jobs", request.url)
        assertEquals("Bearer rev-key", request.headers["Authorization"])
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("media"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("media"))
    }

    @Test
    fun `the options ask for the machine transcriber and leave diarization on`() = runBlocking {
        harness.server.reply("""{"id":"job-1"}""")

        provider.submit(context(), harness.audio)

        val options = options(0)
        assertEquals("machine", options["transcriber"]?.jsonPrimitive?.content)
        assertEquals("ko", options["language"]?.jsonPrimitive?.content)
        assertEquals(false, options["skip_diarization"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `diarization off is the flag that skips it`() = runBlocking {
        harness.server.reply("""{"id":"job-1"}""")

        provider.submit(context(diarize = false), harness.audio)

        assertEquals(true, options(0)["skip_diarization"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `every workflow language maps onto one the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "ko",
            Language.EN to "en",
            // docs/08: no mixed-language code here, and Korean is the half that matters.
            Language.KO_EN to "ko",
            // Leaving the field out would not detect anything here, it would fall back to English.
            Language.AUTO to "ko",
        )
        expected.entries.forEachIndexed { index, (language, code) ->
            harness.server.reply("""{"id":"job-1"}""")
            provider.submit(context(language = language), harness.audio)
            assertEquals(code, options(index)["language"]?.jsonPrimitive?.content, "for $language")
        }
    }

    @Test
    fun `a job in progress is pending and a transcribed one is fetched in the same poll`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"in_progress"}""")
        assertEquals(PollResult.Pending, provider.poll(context(), "job-1"))
        assertEquals("${RevProvider.BASE}/jobs/job-1", harness.server.request(0).url)

        harness.server.reply(TRANSCRIBED).reply(TRANSCRIPT)
        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        val transcript = harness.server.request(2)
        assertEquals("${RevProvider.BASE}/jobs/job-1/transcript", transcript.url)
        assertEquals(RevProvider.TRANSCRIPT_TYPE, transcript.headers["Accept"])
        val result = done.result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        // The punctuation has no timestamps, so the turn's end is the last word's.
        assertEquals(3.25, result.segments[0].end)
        assertEquals("0", result.segments[0].speaker)
        assertEquals("Hello, there", result.segments[0].text)
        assertEquals(
            listOf(SttWord(0.0, 1.5, "Hello"), SttWord(1.75, 3.25, "there")),
            result.segments[0].words,
        )
        assertEquals("1", result.segments[1].speaker)
        assertEquals("Hi.", result.segments[1].text)
        assertEquals("ko", result.language)
        assertEquals(4.5, result.durationSec)
        assertNull(result.model)
    }

    @Test
    fun `auto asks for Korean here too, so the transcript says so`() = runBlocking {
        harness.server.reply(TRANSCRIBED).reply(TRANSCRIPT)

        val done = assertIs<PollResult.Done>(provider.poll(context(language = Language.AUTO), "job-1"))

        assertEquals("ko", done.result.language)
    }

    @Test
    fun `a failed job comes back as data, with the provider's own detail`() = runBlocking {
        harness.server.reply(
            """{"id":"job-1","status":"failed","failure":"invalid_media",
                "failure_detail":"the file could not be decoded"}""",
        )

        val failed = assertIs<PollResult.Failed>(provider.poll(context(), "job-1"))

        assertEquals("the file could not be decoded", failed.reason)
    }

    @Test
    fun `a failure with no detail falls back to the token`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"failed","failure":"internal_processing"}""")

        assertEquals(
            "internal_processing",
            assertIs<PollResult.Failed>(provider.poll(context(), "job-1")).reason,
        )
    }

    @Test
    fun `an unknown status is a provider error rather than a silent success`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"deleted"}""")

        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "job-1") }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertSubmitReason(403, CoreMessage.AUTH_REJECTED, retryable = false)
        assertSubmitReason(402, CoreMessage.QUOTA, retryable = true)
        assertSubmitReason(500, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertSubmitReason(400, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    private suspend fun assertSubmitReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = "no")
        val failure = assertFailsWith<StepFailure> {
            RevProvider().submit(harness.sttContext(RevProvider.NAME, KEY), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun options(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).multipartPart("options")).jsonObject

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
    ): SttContext = harness.sttContext(
        provider = RevProvider.NAME,
        apiKey = KEY,
        language = language,
        diarize = diarize,
    )

    private companion object {
        const val KEY = "rev-key"
        const val TRANSCRIBED = """{"id":"job-1","status":"transcribed"}"""

        /** The documented shape: one monologue per turn, punctuation without timestamps. */
        const val TRANSCRIPT = """
            {"monologues":[
              {"speaker":0,"elements":[
                {"type":"text","value":"Hello","ts":0.0,"end_ts":1.5},
                {"type":"punct","value":","},
                {"type":"text","value":"there","ts":1.75,"end_ts":3.25}]},
              {"speaker":1,"elements":[
                {"type":"text","value":"Hi","ts":3.75,"end_ts":4.5},
                {"type":"punct","value":"."}]}
            ]}
        """
    }
}
