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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language
import recly.core.model.Speakers

/**
 * docs/08 `gladia`. Two calls to start one job — the audio has to be somewhere the API can reach
 * before the job that reads it can be created — and a poll whose `done` body is the transcript.
 */
class GladiaProviderTest {
    private val harness = ProviderHarness()
    private val provider = GladiaProvider()

    @Test
    fun `the audio is uploaded first and the job points at what came back`() = runBlocking {
        harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)

        val submitted = provider.submit(context(), harness.audio)

        assertEquals(Submitted.Polling("job-1"), submitted)
        val upload = harness.server.request(0)
        assertEquals("POST", upload.method)
        assertEquals("${GladiaProvider.BASE}/upload", upload.url)
        assertEquals("gladia-key", upload.headers[GladiaProvider.KEY_HEADER])
        assertTrue("filename=\"joined.m4a\"" in upload.multipartHeaders("audio"))
        assertEquals(ProviderHarness.AUDIO, upload.multipartPart("audio"))
        val submit = harness.server.request(1)
        assertEquals("${GladiaProvider.BASE}/pre-recorded", submit.url)
        assertEquals("gladia-key", submit.headers[GladiaProvider.KEY_HEADER])
        assertEquals(AUDIO_URL, request(1)["audio_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the recorded participant count is the exact number of speakers`() = runBlocking {
        harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)

        provider.submit(context(speakers = Speakers(min = 1, max = 10), speakersExpected = 3), harness.audio)

        val request = request(1)
        assertEquals(true, request["diarization"]?.jsonPrimitive?.boolean)
        val diarization = request["diarization_config"]!!.jsonObject
        assertEquals(3, diarization["number_of_speakers"]?.jsonPrimitive?.int)
        assertNull(diarization["min_speakers"])
    }

    @Test
    fun `a range that is not one number goes out as a range`() = runBlocking {
        harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)

        provider.submit(context(speakers = Speakers(min = 2, max = 6)), harness.audio)

        val diarization = request(1)["diarization_config"]!!.jsonObject
        assertEquals(2, diarization["min_speakers"]?.jsonPrimitive?.int)
        assertEquals(6, diarization["max_speakers"]?.jsonPrimitive?.int)
        assertNull(diarization["number_of_speakers"])
    }

    @Test
    fun `diarization off sends no speaker hint at all`() = runBlocking {
        harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)

        provider.submit(context(diarize = false, speakersExpected = 3), harness.audio)

        val request = request(1)
        assertEquals(false, request["diarization"]?.jsonPrimitive?.boolean)
        assertNull(request["diarization_config"])
    }

    @Test
    fun `every workflow language maps onto the codes the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to (listOf("ko") to false),
            Language.EN to (listOf("en") to false),
            // 한영 혼용 is the only case that asks for a switch mid-recording.
            Language.KO_EN to (listOf("ko", "en") to true),
        )
        expected.entries.forEachIndexed { index, (language, wanted) ->
            harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)
            provider.submit(context(language = language), harness.audio)
            val config = request(index * 2 + 1)["language_config"]!!.jsonObject
            val languages = config["languages"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(wanted.first, languages, "for $language")
            assertEquals(wanted.second, config["code_switching"]?.jsonPrimitive?.boolean, "for $language")
        }
    }

    @Test
    fun `auto sends no language config at all, which is what asks for detection`() = runBlocking {
        harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)

        provider.submit(context(language = Language.AUTO), harness.audio)

        assertNull(request(1)["language_config"])
    }

    @Test
    fun `the step's model is only sent when it has one`() = runBlocking {
        harness.server.reply(UPLOADED).reply(SUBMITTED, status = 201)
            .reply(UPLOADED).reply(SUBMITTED, status = 201)

        provider.submit(context(), harness.audio)
        provider.submit(context(model = "solaria-1"), harness.audio)

        assertNull(request(1)["model"])
        assertEquals("solaria-1", request(3)["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a queued job is pending and a done one carries the transcript itself`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"queued"}""")
        assertEquals(PollResult.Pending, provider.poll(context(), "job-1"))
        assertEquals("${GladiaProvider.BASE}/pre-recorded/job-1", harness.server.request(0).url)

        harness.server.reply(DONE)
        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        val result = done.result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.25, result.segments[0].end)
        assertEquals("0", result.segments[0].speaker)
        assertEquals("Hello there", result.segments[0].text)
        assertEquals(
            listOf(SttWord(0.0, 1.5, "Hello"), SttWord(1.75, 3.25, "there")),
            result.segments[0].words,
        )
        assertEquals("1", result.segments[1].speaker)
        assertNull(result.segments[1].words)
        assertEquals("ko", result.language)
        assertEquals(4.5, result.durationSec)
        assertNull(result.model)
    }

    @Test
    fun `a job the provider gave up on comes back as data`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"error","error_code":400,"message":"bad audio"}""")

        val failed = assertIs<PollResult.Failed>(provider.poll(context(), "job-1"))

        assertEquals("400 bad audio", failed.reason)
    }

    @Test
    fun `an unknown status is a provider error rather than a silent success`() = runBlocking {
        harness.server.reply("""{"id":"job-1","status":"cancelled"}""")

        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "job-1") }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertUploadReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertUploadReason(429, CoreMessage.QUOTA, retryable = true)
        assertUploadReason(503, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertUploadReason(400, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    @Test
    fun `an upload that answers with no audio_url never starts a job`() = runBlocking {
        harness.server.reply("""{}""")

        val failure = assertFailsWith<StepFailure> { provider.submit(context(), harness.audio) }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
        assertEquals(1, harness.server.requests.size)
    }

    private suspend fun assertUploadReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = "no")
        val failure = assertFailsWith<StepFailure> {
            GladiaProvider().submit(harness.sttContext(GladiaProvider.NAME, KEY), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun request(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).text).jsonObject

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
        model: String? = null,
    ): SttContext = harness.sttContext(
        provider = GladiaProvider.NAME,
        apiKey = KEY,
        language = language,
        diarize = diarize,
        speakers = speakers,
        speakersExpected = speakersExpected,
        model = model,
    )

    private companion object {
        const val KEY = "gladia-key"
        const val AUDIO_URL = "https://api.gladia.io/file/abcd"
        const val UPLOADED = """{"audio_url":"$AUDIO_URL","audio_metadata":{"duration":4.5}}"""
        const val SUBMITTED = """{"id":"job-1","result_url":"https://api.gladia.io/v2/pre-recorded/job-1"}"""

        /** The documented shape: one utterance per turn, with the words under it. */
        const val DONE = """
            {"id":"job-1","status":"done","result":{
              "metadata":{"audio_duration":4.5},
              "transcription":{"full_transcript":"Hello there Hi","utterances":[
                {"text":"Hello there","start":0.0,"end":3.25,"speaker":0,"language":"ko",
                 "words":[{"word":"Hello","start":0.0,"end":1.5},{"word":"there","start":1.75,"end":3.25}]},
                {"text":"Hi","start":3.75,"end":4.5,"speaker":1,"language":"ko"}
              ]}}}
        """
    }
}
