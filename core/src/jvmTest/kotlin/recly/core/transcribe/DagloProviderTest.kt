package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language
import recly.core.model.Speakers

/**
 * docs/08 `daglo`. An upload that answers with a `rid` and a poll that answers with one flat word
 * list — the turns in the transcript are the ones this adapter cut out of it.
 */
class DagloProviderTest {
    private val harness = ProviderHarness()
    private val provider = DagloProvider()

    @Test
    fun `the audio and the config go up as one multipart upload`() = runBlocking {
        harness.server.reply("""{"rid":"job-1"}""")

        val submitted = provider.submit(context(), harness.audio)

        assertEquals(Submitted.Polling("job-1"), submitted)
        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals("${DagloProvider.BASE}/stt/v1/async/transcripts", request.url)
        assertEquals("Bearer daglo-key", request.headers["Authorization"])
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("file"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("file"))
    }

    @Test
    fun `the config carries the default model, the language and the speaker hint`() = runBlocking {
        harness.server.reply("""{"rid":"job-1"}""")

        provider.submit(context(speakersExpected = 4), harness.audio)

        val config = config(0)
        assertEquals(DagloProvider.GENERAL, config["model"]?.jsonPrimitive?.content)
        assertEquals("ko-KR", config["language"]?.jsonPrimitive?.content)
        val diarization = config["speakerDiarization"]!!.jsonObject
        assertEquals(true, diarization["enable"]?.jsonPrimitive?.boolean)
        assertEquals(4, diarization["speakerCountHint"]?.jsonPrimitive?.int)
    }

    @Test
    fun `the step's own model wins over the default`() = runBlocking {
        harness.server.reply("""{"rid":"job-1"}""")

        provider.submit(context(model = "premium"), harness.audio)

        assertEquals("premium", config(0)["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a range that is one number is a hint, and one that is not is left to the provider`() = runBlocking {
        harness.server.reply("""{"rid":"job-1"}""").reply("""{"rid":"job-2"}""")

        provider.submit(context(speakers = Speakers(min = 3, max = 3)), harness.audio)
        provider.submit(context(speakers = Speakers(min = 2, max = 6)), harness.audio)

        assertEquals(3, hint(0)?.jsonPrimitive?.int)
        assertNull(hint(1))
    }

    @Test
    fun `a hint of one speaker is not sent, because that is diarization asked to find nobody`() = runBlocking {
        harness.server.reply("""{"rid":"job-1"}""")

        provider.submit(context(speakersExpected = 1), harness.audio)

        assertNull(hint(0))
    }

    @Test
    fun `diarization off sends no speaker hint at all`() = runBlocking {
        harness.server.reply("""{"rid":"job-1"}""")

        provider.submit(context(diarize = false, speakersExpected = 4), harness.audio)

        assertEquals(false, config(0)["speakerDiarization"]!!.jsonObject["enable"]?.jsonPrimitive?.boolean)
        assertNull(hint(0))
    }

    @Test
    fun `every workflow language maps onto one the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "ko-KR",
            Language.EN to "en-US",
            Language.KO_EN to "mixed",
            // docs/08: this provider has no detection mode, so `auto` is Korean.
            Language.AUTO to "ko-KR",
        )
        expected.entries.forEachIndexed { index, (language, code) ->
            harness.server.reply("""{"rid":"job-1"}""")
            provider.submit(context(language = language), harness.audio)
            assertEquals(code, config(index)["language"]?.jsonPrimitive?.content, "for $language")
        }
    }

    @Test
    fun `a queued job is pending and a transcribed one is the transcript`() = runBlocking {
        harness.server.reply("""{"rid":"job-1","status":"queued"}""")
        assertEquals(PollResult.Pending, provider.poll(context(), "job-1"))
        assertEquals("${DagloProvider.BASE}/stt/v1/async/transcripts/job-1", harness.server.request(0).url)

        harness.server.reply(TRANSCRIBED)
        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        val result = done.result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.25, result.segments[0].end)
        assertEquals("1", result.segments[0].speaker)
        assertEquals("안녕하세요 반갑습니다", result.segments[0].text)
        assertEquals(
            listOf(SttWord(0.0, 1.5, "안녕하세요"), SttWord(1.75, 3.25, "반갑습니다")),
            result.segments[0].words,
        )
        assertEquals("2", result.segments[1].speaker)
        assertEquals("네", result.segments[1].text)
        assertEquals("ko-KR", result.language)
        assertEquals(4.5, result.durationSec)
        assertEquals(DagloProvider.GENERAL, result.model)
    }

    @Test
    fun `the same speaker on a new segment id is still a new turn`() = runBlocking {
        harness.server.reply(
            """
            {"rid":"job-1","status":"transcribed","sttResults":[{"words":[
              {"word":"one","startTime":{"seconds":"0","nanos":0},"endTime":{"seconds":"1","nanos":0},
               "segmentId":0,"speaker":"1"},
              {"word":"two","startTime":{"seconds":"1","nanos":500000000},"endTime":{"seconds":"2","nanos":0},
               "segmentId":1,"speaker":"1"}
            ]}]}
            """,
        )

        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        assertEquals(listOf("one", "two"), done.result.segments.map { it.text })
    }

    @Test
    fun `a finished job with nothing in it answers 204, which is a transcript of silence`() = runBlocking {
        harness.server.reply(status = 204, body = "")

        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        assertEquals(emptyList(), done.result.segments)
        assertEquals("ko-KR", done.result.language)
        assertNull(done.result.durationSec)
        assertEquals(DagloProvider.GENERAL, done.result.model)
    }

    @Test
    fun `a result with no words is one untimed turn, parked where the last one ended`() = runBlocking {
        harness.server.reply(UNTIMED)

        val done = assertIs<PollResult.Done>(provider.poll(context(), "job-1"))

        val segments = done.result.segments
        assertEquals(2, segments.size)
        assertEquals(3.25, segments[0].end)
        assertEquals(3.25, segments[1].start)
        assertEquals(3.25, segments[1].end)
        assertNull(segments[1].speaker)
        assertNull(segments[1].words)
        assertEquals("자막이 없는 문장", segments[1].text)
        assertEquals(3.25, done.result.durationSec)
    }

    @Test
    fun `each state the provider calls terminal comes back as data`() = runBlocking {
        DagloProvider.ERROR_STATES.forEach { state ->
            val harness = ProviderHarness()
            harness.server.reply("""{"rid":"job-1","status":"$state","message":"bad audio"}""")
            val failed = assertIs<PollResult.Failed>(
                DagloProvider().poll(harness.sttContext(DagloProvider.NAME, KEY), "job-1"),
            )
            assertEquals("$state bad audio", failed.reason)
        }
    }

    @Test
    fun `a status this build has never heard of is a queue state, not a failure`() = runBlocking {
        harness.server.reply("""{"rid":"job-1","status":"converting"}""")

        assertEquals(PollResult.Pending, provider.poll(context(), "job-1"))
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertSubmitReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertSubmitReason(429, CoreMessage.QUOTA, retryable = true)
        assertSubmitReason(503, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertSubmitReason(415, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    @Test
    fun `a submit that answers with no rid has nothing to poll`() = runBlocking {
        harness.server.reply("""{"status":"queued"}""")

        val failure = assertFailsWith<StepFailure> { provider.submit(context(), harness.audio) }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    private suspend fun assertSubmitReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = "no")
        val failure = assertFailsWith<StepFailure> {
            DagloProvider().submit(harness.sttContext(DagloProvider.NAME, KEY), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun hint(index: Int): JsonElement? =
        config(index)["speakerDiarization"]!!.jsonObject["speakerCountHint"]

    private fun config(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).multipartPart("sttConfig")).jsonObject

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
        model: String? = null,
    ): SttContext = harness.sttContext(
        provider = DagloProvider.NAME,
        apiKey = KEY,
        language = language,
        diarize = diarize,
        speakers = speakers,
        speakersExpected = speakersExpected,
        model = model,
    )

    private companion object {
        const val KEY = "daglo-key"

        /** The documented shape: protobuf durations, `seconds` as a string, one flat word list. */
        const val TRANSCRIBED = """
            {"rid":"job-1","status":"transcribed","sttResults":[
              {"transcript":"안녕하세요 반갑습니다 네","words":[
                {"word":"안녕하세요","startTime":{"seconds":"0","nanos":0},
                 "endTime":{"seconds":"1","nanos":500000000},"segmentId":0,"speaker":"1"},
                {"word":"반갑습니다","startTime":{"seconds":"1","nanos":750000000},
                 "endTime":{"seconds":"3","nanos":250000000},"segmentId":0,"speaker":"1"},
                {"word":"네","startTime":{"seconds":"3","nanos":750000000},
                 "endTime":{"seconds":"4","nanos":500000000},"segmentId":1,"speaker":"2"}
              ]}]}
        """

        /** `words` is optional; `transcript` is the only field a result always carries. */
        const val UNTIMED = """
            {"rid":"job-1","status":"transcribed","sttResults":[
              {"transcript":"안녕하세요 반갑습니다","words":[
                {"word":"안녕하세요","startTime":{"seconds":"0","nanos":0},
                 "endTime":{"seconds":"1","nanos":500000000},"segmentId":0,"speaker":"1"},
                {"word":"반갑습니다","startTime":{"seconds":"1","nanos":750000000},
                 "endTime":{"seconds":"3","nanos":250000000},"segmentId":0,"speaker":"1"}
              ]},
              {"transcript":"자막이 없는 문장"}
            ]}
        """
    }
}
