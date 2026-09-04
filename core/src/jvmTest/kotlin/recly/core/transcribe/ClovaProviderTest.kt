package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import recly.core.model.Speakers

/**
 * docs/08 `clova`. The synchronous provider: what goes up is a multipart upload and what comes
 * back is the transcript itself, so there is nothing here that polls.
 */
class ClovaProviderTest {
    private val harness = ProviderHarness()
    private val provider = ClovaProvider()

    @Test
    fun `the audio and the params go up as one multipart upload`() = runBlocking {
        harness.server.reply(COMPLETED)

        provider.submit(context(), harness.audio)

        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals("$INVOKE_URL/recognizer/upload", request.url)
        assertEquals("clova-key", request.headers[ClovaProvider.KEY_HEADER])
        assertTrue(
            request.headers["Content-Type"]?.startsWith("multipart/form-data; boundary=") == true,
            "content type was ${request.headers["Content-Type"]}",
        )
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("media"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("media"))
    }

    @Test
    fun `the params say sync, word alignment and the speaker range`() = runBlocking {
        harness.server.reply(COMPLETED)

        provider.submit(context(speakers = Speakers(min = 2, max = 6)), harness.audio)

        val params = params(0)
        assertEquals("ko-KR", params["language"]?.jsonPrimitive?.content)
        assertEquals("sync", params["completion"]?.jsonPrimitive?.content)
        assertEquals(true, params["wordAlignment"]?.jsonPrimitive?.boolean)
        val diarization = params["diarization"]!!.jsonObject
        assertEquals(true, diarization["enable"]?.jsonPrimitive?.boolean)
        assertEquals(2, diarization["speakerCountMin"]?.jsonPrimitive?.int)
        assertEquals(6, diarization["speakerCountMax"]?.jsonPrimitive?.int)
    }

    @Test
    fun `the recorded participant count collapses the range`() = runBlocking {
        harness.server.reply(COMPLETED)

        provider.submit(context(speakers = Speakers(min = 1, max = 10), speakersExpected = 3), harness.audio)

        val diarization = params(0)["diarization"]!!.jsonObject
        assertEquals(3, diarization["speakerCountMin"]?.jsonPrimitive?.int)
        assertEquals(3, diarization["speakerCountMax"]?.jsonPrimitive?.int)
    }

    @Test
    fun `diarization off sends no speaker hint at all`() = runBlocking {
        harness.server.reply(COMPLETED)

        provider.submit(context(diarize = false), harness.audio)

        val diarization = params(0)["diarization"]!!.jsonObject
        assertEquals(false, diarization["enable"]?.jsonPrimitive?.boolean)
        assertNull(diarization["speakerCountMin"])
    }

    @Test
    fun `every workflow language maps onto one the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "ko-KR",
            Language.EN to "en-US",
            Language.KO_EN to "enko",
            // docs/08: this provider has no detection mode, so `auto` is Korean.
            Language.AUTO to "ko-KR",
        )
        expected.entries.forEachIndexed { index, (language, code) ->
            harness.server.reply(COMPLETED)
            provider.submit(context(language = language), harness.audio)
            assertEquals(code, params(index)["language"]?.jsonPrimitive?.content, "for $language")
        }
    }

    @Test
    fun `the response body is the finished transcript`() = runBlocking {
        harness.server.reply(COMPLETED)

        val submitted = provider.submit(context(), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.2, result.segments[0].end)
        assertEquals("A", result.segments[0].speaker)
        assertEquals("안녕하세요", result.segments[0].text)
        assertEquals(listOf(SttWord(0.0, 1.2, "안녕하세요")), result.segments[0].words)
        assertEquals("B", result.segments[1].speaker)
        assertEquals(9.1, result.durationSec)
        assertEquals("ko-KR", result.language)
    }

    @Test
    fun `a poll is a bug, because a sync provider never hands out a ref`() = runBlocking {
        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "whatever") }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `a 200 that is not COMPLETED is the provider reporting its own failure`() = runBlocking {
        harness.server.reply("""{"result":"FAILED","message":"media decode error"}""")

        val failure = assertFailsWith<StepFailure> { provider.submit(context(), harness.audio) }

        assertEquals(true, failure.retryable)
        assertTrue("media decode error" in failure.reason, failure.reason)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertReason(403, CoreMessage.AUTH_REJECTED, retryable = false)
        assertReason(429, CoreMessage.QUOTA, retryable = true)
        assertReason(402, CoreMessage.QUOTA, retryable = true)
        assertReason(503, CoreMessage.PROVIDER_ERROR, retryable = true)
        // A 4xx that is not about the key is the file being refused.
        assertReason(400, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    @Test
    fun `a step with no invokeUrl cannot be run at all`() = runBlocking {
        val failure = assertFailsWith<StepFailure> {
            provider.submit(harness.sttContext(ClovaProvider.NAME, "clova-key"), harness.audio)
        }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    private suspend fun assertReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = """{"message":"no"}""")
        val failure = assertFailsWith<StepFailure> {
            ClovaProvider().submit(
                harness.sttContext(ClovaProvider.NAME, "clova-key", invokeUrl = INVOKE_URL),
                harness.audio,
            )
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun params(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).multipartPart("params")).jsonObject

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
    ): SttContext = harness.sttContext(
        provider = ClovaProvider.NAME,
        apiKey = "clova-key",
        language = language,
        diarize = diarize,
        speakers = speakers,
        speakersExpected = speakersExpected,
        invokeUrl = INVOKE_URL,
    )

    private companion object {
        const val INVOKE_URL = "https://clovaspeech-gw.ncloud.com/external/v1/1234/abcd"

        /** The documented shape: milliseconds, `speaker.label`, and words as triples. */
        const val COMPLETED = """
            {"result":"COMPLETED","message":"Succeeded","text":"안녕하세요 반갑습니다",
             "segments":[
               {"start":0,"end":3200,"text":"안녕하세요","confidence":0.9,
                "speaker":{"label":"A","name":"화자1","edited":false},
                "diarization":{"label":"A"},
                "words":[[0,1200,"안녕하세요"]]},
               {"start":3600,"end":9100,"text":"반갑습니다","confidence":0.9,
                "speaker":{"label":"B","name":"화자2","edited":false},
                "diarization":{"label":"B"}}
             ]}
        """
    }
}
