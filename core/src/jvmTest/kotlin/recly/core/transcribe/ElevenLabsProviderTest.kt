package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language
import recly.core.model.Speakers

/**
 * docs/08 `elevenlabs`. Synchronous like `clova`, but the response is a flat word stream: the turns
 * are cut here, on a speaker change or two seconds of silence.
 */
class ElevenLabsProviderTest {
    private val harness = ProviderHarness()
    private val provider = ElevenLabsProvider()

    @Test
    fun `the audio and the options go up as one multipart form`() = runBlocking {
        harness.server.reply(WORDS)

        provider.submit(context(), harness.audio)

        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals("${ElevenLabsProvider.BASE}/speech-to-text", request.url)
        assertEquals("scribe-key", request.headers[ElevenLabsProvider.KEY_HEADER])
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("file"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("file"))
        assertEquals(ElevenLabsProvider.MODEL, request.multipartPart("model_id"))
        assertEquals("ko", request.multipartPart("language_code"))
        assertEquals("true", request.multipartPart("diarize"))
        assertEquals("word", request.multipartPart("timestamps_granularity"))
        assertEquals("false", request.multipartPart("tag_audio_events"))
    }

    @Test
    fun `a speaker count is only sent when it is one number`() = runBlocking {
        harness.server.reply(WORDS)
        provider.submit(context(speakers = Speakers(min = 2, max = 6)), harness.audio)
        assertFalse("name=\"num_speakers\"" in harness.server.request(0).text)

        harness.server.reply(WORDS)
        provider.submit(context(speakers = Speakers(min = 3, max = 3)), harness.audio)
        assertEquals("3", harness.server.request(1).multipartPart("num_speakers"))

        // `context.participants` beats the workflow's own range.
        harness.server.reply(WORDS)
        provider.submit(context(speakers = Speakers(min = 1, max = 10), speakersExpected = 4), harness.audio)
        assertEquals("4", harness.server.request(2).multipartPart("num_speakers"))
    }

    @Test
    fun `diarization off sends no speaker hint at all`() = runBlocking {
        harness.server.reply(WORDS)

        provider.submit(context(diarize = false, speakersExpected = 4), harness.audio)

        val request = harness.server.request(0)
        assertEquals("false", request.multipartPart("diarize"))
        assertFalse("name=\"num_speakers\"" in request.text)
    }

    @Test
    fun `every workflow language maps onto one the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "ko",
            Language.EN to "en",
            Language.KO_EN to "ko",
            // Omitted entirely: that is how this API is asked to detect it itself.
            Language.AUTO to null,
        )
        expected.entries.forEachIndexed { index, (language, code) ->
            harness.server.reply(WORDS)
            provider.submit(context(language = language), harness.audio)
            val request = harness.server.request(index)
            if (code == null) {
                assertFalse("name=\"language_code\"" in request.text, "for $language")
            } else {
                assertEquals(code, request.multipartPart("language_code"), "for $language")
            }
        }
    }

    @Test
    fun `the word stream is cut into turns on the speaker and on silence`() = runBlocking {
        harness.server.reply(WORDS)

        val submitted = provider.submit(context(), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(3, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(1.4, result.segments[0].end)
        assertEquals("0", result.segments[0].speaker)
        // The spacing tokens are the spaces, and the trailing one is trimmed off.
        assertEquals("안녕하세요 반갑습니다", result.segments[0].text)
        assertEquals(
            listOf(SttWord(0.0, 0.6, "안녕하세요"), SttWord(0.8, 1.4, "반갑습니다")),
            result.segments[0].words,
        )
        // A different speaker starts a turn even with no silence between them.
        assertEquals("1", result.segments[1].speaker)
        assertEquals("네", result.segments[1].text)
        // Same speaker again, but four seconds later.
        assertEquals("1", result.segments[2].speaker)
        assertEquals("그럼", result.segments[2].text)
        assertEquals(6.5, result.durationSec)
        assertEquals("kor", result.language)
        assertEquals(ElevenLabsProvider.MODEL, result.model)
    }

    @Test
    fun `an audio event is a noise, not a word`() = runBlocking {
        harness.server.reply(EVENTS)

        val submitted = provider.submit(context(diarize = false), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(1, result.segments.size)
        assertEquals("안녕하세요", result.segments[0].text)
        assertEquals(1, result.segments[0].words?.size)
        assertNull(result.segments[0].speaker)
    }

    @Test
    fun `the name is one this build can run`() {
        assertEquals(ElevenLabsProvider.NAME, SttProviders.create(ElevenLabsProvider.NAME)?.name)
    }

    @Test
    fun `a poll is a bug, because a sync provider never hands out a ref`() = runBlocking {
        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "whatever") }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertReason(429, CoreMessage.QUOTA, retryable = true)
        assertReason(500, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertReason(422, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    private suspend fun assertReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = """{"detail":"no"}""")
        val failure = assertFailsWith<StepFailure> {
            ElevenLabsProvider().submit(harness.sttContext(ElevenLabsProvider.NAME, "scribe-key"), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
    ): SttContext = harness.sttContext(
        provider = ElevenLabsProvider.NAME,
        apiKey = "scribe-key",
        language = language,
        diarize = diarize,
        speakers = speakers,
        speakersExpected = speakersExpected,
    )

    private companion object {
        /** The documented shape: seconds, `spacing` tokens between words, `speaker_id` per word. */
        const val WORDS = """
            {"language_code":"kor","language_probability":0.99,"audio_duration_secs":6.5,
             "text":"안녕하세요 반갑습니다 네 그럼",
             "words":[
               {"text":"안녕하세요","type":"word","start":0.0,"end":0.6,"speaker_id":"0"},
               {"text":" ","type":"spacing","start":0.6,"end":0.8,"speaker_id":"0"},
               {"text":"반갑습니다","type":"word","start":0.8,"end":1.4,"speaker_id":"0"},
               {"text":" ","type":"spacing","start":1.4,"end":1.5,"speaker_id":"1"},
               {"text":"네","type":"word","start":1.5,"end":1.9,"speaker_id":"1"},
               {"text":" ","type":"spacing","start":1.9,"end":6.0,"speaker_id":"1"},
               {"text":"그럼","type":"word","start":6.0,"end":6.5,"speaker_id":"1"}
             ]}
        """

        const val EVENTS = """
            {"language_code":"kor","audio_duration_secs":2.0,"text":"안녕하세요",
             "words":[
               {"text":"(laughter)","type":"audio_event","start":0.0,"end":0.4},
               {"text":"안녕하세요","type":"word","start":0.5,"end":1.1}
             ]}
        """
    }
}
