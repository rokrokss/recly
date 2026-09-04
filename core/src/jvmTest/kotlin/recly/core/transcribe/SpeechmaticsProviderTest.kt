package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language

/**
 * docs/08 `speechmatics`. Three calls for one transcript: submit, poll, and the fetch that the poll
 * which sees `done` does for itself rather than parking the job for another interval.
 */
class SpeechmaticsProviderTest {
    private val harness = ProviderHarness()
    private val provider = SpeechmaticsProvider()

    @Test
    fun `the audio and the config go up as one multipart upload`() = runBlocking {
        harness.server.reply("""{"id":"job-1"}""")

        val submitted = provider.submit(context(), harness.audio)

        assertEquals(Submitted.Polling("job-1"), submitted)
        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals("${SpeechmaticsProvider.BASE}/jobs", request.url)
        assertEquals("Bearer sm-key", request.headers["Authorization"])
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("data_file"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("data_file"))
    }

    @Test
    fun `the config asks for transcription, the default operating point and speaker diarization`() = runBlocking {
        harness.server.reply("""{"id":"job-1"}""")

        provider.submit(context(), harness.audio)

        val config = config(0)
        assertEquals("transcription", config["type"]?.jsonPrimitive?.content)
        val transcription = config["transcription_config"]!!.jsonObject
        assertEquals("ko", transcription["language"]?.jsonPrimitive?.content)
        assertEquals(SpeechmaticsProvider.ENHANCED, transcription["operating_point"]?.jsonPrimitive?.content)
        assertEquals("speaker", transcription["diarization"]?.jsonPrimitive?.content)
    }

    @Test
    fun `diarization off is a mode of its own, and the step's model is the operating point`() = runBlocking {
        harness.server.reply("""{"id":"job-1"}""")

        provider.submit(context(diarize = false, model = "standard"), harness.audio)

        val transcription = config(0)["transcription_config"]!!.jsonObject
        assertEquals("none", transcription["diarization"]?.jsonPrimitive?.content)
        assertEquals("standard", transcription["operating_point"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every workflow language maps onto one the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "ko",
            Language.EN to "en",
            // docs/08: no mixed-language code here, and Korean is the half that matters.
            Language.KO_EN to "ko",
            // Unlike the other Korean-first providers, this one does have a detection mode.
            Language.AUTO to "auto",
        )
        expected.entries.forEachIndexed { index, (language, code) ->
            harness.server.reply("""{"id":"job-1"}""")
            provider.submit(context(language = language), harness.audio)
            val transcription = config(index)["transcription_config"]!!.jsonObject
            assertEquals(code, transcription["language"]?.jsonPrimitive?.content, "for $language")
        }
    }

    @Test
    fun `an invokeUrl moves every call to that region`() = runBlocking {
        harness.server.reply("""{"id":"job-1"}""").reply(RUNNING)

        val ctx = context(invokeUrl = "$US1/")
        provider.submit(ctx, harness.audio)
        provider.poll(ctx, "job-1")

        assertEquals("$US1/jobs", harness.server.request(0).url)
        assertEquals("$US1/jobs/job-1", harness.server.request(1).url)
    }

    @Test
    fun `a running job is pending and a done one is fetched in the same poll`() = runBlocking {
        harness.server.reply(RUNNING)
        assertEquals(PollResult.Pending, provider.poll(context(), "job-1"))

        harness.server.reply(DONE).reply(TRANSCRIPT)
        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        val transcript = harness.server.request(2)
        assertEquals("GET", transcript.method)
        // `json-v2` is the only JSON this endpoint offers; a plain `json` is refused.
        assertEquals("${SpeechmaticsProvider.BASE}/jobs/job-1/transcript?format=json-v2", transcript.url)
        assertEquals("Bearer sm-key", transcript.headers["Authorization"])
        val result = done.result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.25, result.segments[0].end)
        assertEquals("S1", result.segments[0].speaker)
        // The punctuation attaches to the word before it rather than taking a space of its own.
        assertEquals("Hello, there", result.segments[0].text)
        assertEquals(
            listOf(SttWord(0.0, 1.5, "Hello"), SttWord(1.75, 3.25, "there")),
            result.segments[0].words,
        )
        // `UU` is this provider's own label for a speaker it could not tell apart.
        assertEquals("UU", result.segments[1].speaker)
        assertEquals("Hi", result.segments[1].text)
        assertEquals("ko", result.language)
        assertEquals(4.5, result.durationSec)
        assertEquals(SpeechmaticsProvider.ENHANCED, result.model)
    }

    @Test
    fun `a rejected job comes back as data, with the provider's own messages`() = runBlocking {
        harness.server.reply(
            """{"job":{"id":"job-1","status":"rejected","errors":[{"message":"unsupported codec"}]}}""",
        )

        val failed = assertIs<PollResult.Failed>(provider.poll(context(), "job-1"))

        assertEquals("unsupported codec", failed.reason)
    }

    @Test
    fun `a rejection with no message at all still says it was rejected`() = runBlocking {
        harness.server.reply("""{"job":{"id":"job-1","status":"rejected"}}""")

        assertEquals("rejected", assertIs<PollResult.Failed>(provider.poll(context(), "job-1")).reason)
    }

    @Test
    fun `a job that is gone comes back as data, because the ref is worthless`() = runBlocking {
        listOf("deleted", "expired").forEach { status ->
            val harness = ProviderHarness()
            harness.server.reply("""{"job":{"id":"job-1","status":"$status"}}""")
            val failed = assertIs<PollResult.Failed>(
                SpeechmaticsProvider().poll(harness.sttContext(SpeechmaticsProvider.NAME, KEY), "job-1"),
            )
            assertEquals(status, failed.reason)
        }
    }

    @Test
    fun `the language it identified beats the one that was asked for`() = runBlocking {
        harness.server.reply(DONE).reply(DETECTED)

        val done = assertIs<PollResult.Done>(provider.poll(context(language = Language.AUTO), "job-1"))

        assertEquals("en", done.result.language)
    }

    @Test
    fun `an unknown status is a provider error rather than a silent success`() = runBlocking {
        harness.server.reply("""{"job":{"id":"job-1","status":"paused"}}""")

        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "job-1") }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `a transcript fetch that fails keeps the ref, because the job is still there`() = runBlocking {
        harness.server.reply(DONE).reply(status = 500, body = "no")

        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "job-1") }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertSubmitReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertSubmitReason(429, CoreMessage.QUOTA, retryable = true)
        assertSubmitReason(503, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertSubmitReason(400, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    private suspend fun assertSubmitReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = "no")
        val failure = assertFailsWith<StepFailure> {
            SpeechmaticsProvider().submit(harness.sttContext(SpeechmaticsProvider.NAME, KEY), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun config(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).multipartPart("config")).jsonObject

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        model: String? = null,
        invokeUrl: String? = null,
    ): SttContext = harness.sttContext(
        provider = SpeechmaticsProvider.NAME,
        apiKey = KEY,
        language = language,
        diarize = diarize,
        model = model,
        invokeUrl = invokeUrl,
    )

    private companion object {
        const val KEY = "sm-key"
        const val US1 = "https://us1.asr.api.speechmatics.com/v2"
        const val RUNNING = """{"job":{"id":"job-1","status":"running"}}"""
        const val DONE = """{"job":{"id":"job-1","status":"done"}}"""

        /** The documented shape: one flat token list, punctuation attaching to the token before. */
        const val TRANSCRIPT = """
            {"metadata":{"transcription_config":{"language":"ko"}},
             "results":[
               {"type":"word","start_time":0.0,"end_time":1.5,
                "alternatives":[{"content":"Hello","speaker":"S1"}]},
               {"type":"punctuation","start_time":1.5,"end_time":1.5,"attaches_to":"previous",
                "alternatives":[{"content":",","speaker":"S1"}]},
               {"type":"word","start_time":1.75,"end_time":3.25,
                "alternatives":[{"content":"there","speaker":"S1"}]},
               {"type":"word","start_time":3.75,"end_time":4.5,
                "alternatives":[{"content":"Hi","speaker":"UU"}]}
             ]}
        """

        /** What identification adds: the language it settled on, written on the tokens. */
        const val DETECTED = """
            {"metadata":{"transcription_config":{"language":"auto"}},
             "results":[
               {"type":"word","start_time":0.0,"end_time":1.5,
                "alternatives":[{"content":"Hello","speaker":"S1","language":"en"}]}
             ]}
        """
    }
}
