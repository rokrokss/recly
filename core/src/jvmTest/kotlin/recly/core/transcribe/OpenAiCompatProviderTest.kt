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
 * docs/08 `openai` · `groq` · `together` · `mistral`. One API, four dialects: what changes between
 * them is the base URL, the default model and how each one is asked to diarize.
 */
class OpenAiCompatProviderTest {
    private val harness = ProviderHarness()

    @Test
    fun `the audio and the options go up as one multipart form`() = runBlocking {
        harness.server.reply(DIARIZED)

        provider(Profile.OPENAI).submit(context(Profile.OPENAI), harness.audio)

        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", request.url)
        assertEquals("Bearer openai-key", request.headers["Authorization"])
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("file"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("file"))
        assertEquals(OpenAiCompatProvider.GPT_4O_DIARIZE, request.multipartPart("model"))
        assertEquals("ko", request.multipartPart("language"))
    }

    @Test
    fun `openai picks the format from the model it was told to use`() = runBlocking {
        harness.server.reply(DIARIZED)
        provider(Profile.OPENAI).submit(context(Profile.OPENAI), harness.audio)
        assertEquals(OpenAiCompatProvider.DIARIZED_JSON, harness.server.request(0).multipartPart("response_format"))
        assertEquals("auto", harness.server.request(0).multipartPart("chunking_strategy"))

        // No diarization means `whisper-1`, which answers in the older verbose shape.
        harness.server.reply(VERBOSE)
        provider(Profile.OPENAI).submit(context(Profile.OPENAI, diarize = false), harness.audio)
        assertEquals(OpenAiCompatProvider.WHISPER_1, harness.server.request(1).multipartPart("model"))
        assertEquals(OpenAiCompatProvider.VERBOSE_JSON, harness.server.request(1).multipartPart("response_format"))
        assertEquals("segment", harness.server.request(1).multipartPart("timestamp_granularities[]"))

        // Anything else — a non-diarizing gpt-4o model — has only the plain format.
        harness.server.reply(PLAIN)
        provider(Profile.OPENAI).submit(context(Profile.OPENAI, model = "gpt-4o-transcribe"), harness.audio)
        assertEquals(OpenAiCompatProvider.PLAIN_JSON, harness.server.request(2).multipartPart("response_format"))
    }

    @Test
    fun `every workflow language maps onto one the vendor knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "ko",
            Language.EN to "en",
            // No mixed code exists here, and Korean is the half that matters.
            Language.KO_EN to "ko",
            // Omitted entirely: that is how these APIs are asked to detect it themselves.
            Language.AUTO to null,
        )
        expected.entries.forEachIndexed { index, (language, code) ->
            harness.server.reply(DIARIZED)
            provider(Profile.OPENAI).submit(context(Profile.OPENAI, language = language), harness.audio)
            val request = harness.server.request(index)
            if (code == null) {
                assertFalse("name=\"language\"" in request.text, "for $language")
            } else {
                assertEquals(code, request.multipartPart("language"), "for $language")
            }
        }
    }

    @Test
    fun `an invokeUrl replaces the vendor's own base`() = runBlocking {
        harness.server.reply(DIARIZED)

        provider(Profile.OPENAI).submit(
            harness.sttContext(
                OpenAiCompatProvider.OPENAI_NAME,
                "openai-key",
                invokeUrl = "https://gateway.internal/openai/v1/",
            ),
            harness.audio,
        )

        assertEquals("https://gateway.internal/openai/v1/audio/transcriptions", harness.server.request(0).url)
    }

    @Test
    fun `groq has no diarization to ask for and reads the verbose shape`() = runBlocking {
        harness.server.reply(VERBOSE)

        val submitted = provider(Profile.GROQ).submit(context(Profile.GROQ), harness.audio)

        val request = harness.server.request(0)
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", request.url)
        assertEquals("whisper-large-v3-turbo", request.multipartPart("model"))
        assertEquals(OpenAiCompatProvider.VERBOSE_JSON, request.multipartPart("response_format"))
        assertFalse("name=\"diarize\"" in request.text)
        val result = (submitted as Submitted.Finished).result
        assertNull(result.segments[0].speaker)
    }

    @Test
    fun `together asks for a speaker range and the participant count collapses it`() = runBlocking {
        harness.server.reply(TOGETHER)
        provider(Profile.TOGETHER).submit(
            context(Profile.TOGETHER, speakers = Speakers(min = 2, max = 6)),
            harness.audio,
        )
        val request = harness.server.request(0)
        assertEquals("https://api.together.ai/v1/audio/transcriptions", request.url)
        assertEquals("openai/whisper-large-v3", request.multipartPart("model"))
        assertEquals("true", request.multipartPart("diarize"))
        assertEquals("segment", request.multipartPart("timestamp_granularities"))
        assertEquals("2", request.multipartPart("min_speakers"))
        assertEquals("6", request.multipartPart("max_speakers"))

        harness.server.reply(TOGETHER)
        provider(Profile.TOGETHER).submit(
            context(Profile.TOGETHER, speakers = Speakers(min = 1, max = 10), speakersExpected = 3),
            harness.audio,
        )
        assertEquals("3", harness.server.request(1).multipartPart("min_speakers"))
        assertEquals("3", harness.server.request(1).multipartPart("max_speakers"))
    }

    @Test
    fun `diarization off sends no speaker hint at all`() = runBlocking {
        harness.server.reply(VERBOSE)

        provider(Profile.TOGETHER).submit(context(Profile.TOGETHER, diarize = false), harness.audio)

        val request = harness.server.request(0)
        assertEquals("false", request.multipartPart("diarize"))
        assertFalse("name=\"min_speakers\"" in request.text)
    }

    @Test
    fun `together answers with its own speaker segments when it diarized`() = runBlocking {
        harness.server.reply(TOGETHER)

        val submitted = provider(Profile.TOGETHER).submit(context(Profile.TOGETHER), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(2, result.segments.size)
        assertEquals("0", result.segments[0].speaker)
        assertEquals("안녕하세요", result.segments[0].text)
        assertEquals(listOf(SttWord(0.0, 1.2, "안녕하세요")), result.segments[0].words)
        assertEquals("1", result.segments[1].speaker)
        assertEquals(9.1, result.durationSec)
    }

    @Test
    fun `mistral labels its segments with a speaker id and bills the duration`() = runBlocking {
        harness.server.reply(MISTRAL)

        val submitted = provider(Profile.MISTRAL).submit(context(Profile.MISTRAL), harness.audio)

        val request = harness.server.request(0)
        assertEquals("https://api.mistral.ai/v1/audio/transcriptions", request.url)
        assertEquals("voxtral-mini-latest", request.multipartPart("model"))
        assertEquals("true", request.multipartPart("diarize"))
        val result = (submitted as Submitted.Finished).result
        assertEquals("0", result.segments[0].speaker)
        assertEquals("1", result.segments[1].speaker)
        assertEquals("ko", result.language)
        // No `duration` in this shape: the billed audio seconds are what the length is read from.
        assertEquals(9.1, result.durationSec)
        assertEquals("voxtral-mini-latest", result.model)
    }

    @Test
    fun `verbose words land in the segment they start in`() = runBlocking {
        harness.server.reply(VERBOSE)

        val submitted = provider(Profile.OPENAI).submit(context(Profile.OPENAI, diarize = false), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(listOf(SttWord(0.0, 1.2, "안녕하세요")), result.segments[0].words)
        assertEquals(listOf(SttWord(3.6, 4.8, "반갑습니다")), result.segments[1].words)
        // The response says what it heard, which is not the code that was sent.
        assertEquals("korean", result.language)
        assertEquals(9.1, result.durationSec)
    }

    @Test
    fun `a plain json answer is one undivided segment`() = runBlocking {
        harness.server.reply(PLAIN)

        val submitted = provider(Profile.OPENAI).submit(
            context(Profile.OPENAI, model = "gpt-4o-transcribe"),
            harness.audio,
        )

        val result = (submitted as Submitted.Finished).result
        assertEquals(1, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(12.5, result.segments[0].end)
        assertNull(result.segments[0].speaker)
        assertEquals("안녕하세요 반갑습니다", result.segments[0].text)
        assertEquals("ko", result.language)
    }

    @Test
    fun `a usage counted in tokens leaves the recording's own length to say how long it is`() = runBlocking {
        harness.server.reply(TOKENS)

        val submitted = provider(Profile.OPENAI).submit(
            context(Profile.OPENAI, model = "gpt-4o-transcribe", audioDurationSec = 61.5),
            harness.audio,
        )

        val result = (submitted as Submitted.Finished).result
        assertEquals(1, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        // Without this the whole transcript would sit in a segment spanning 0.0..0.0.
        assertEquals(61.5, result.segments[0].end)
        assertEquals(61.5, result.durationSec)
    }

    @Test
    fun `an audio length nobody knows is still no worse than nothing`() = runBlocking {
        harness.server.reply(TOKENS)

        val submitted = provider(Profile.OPENAI).submit(
            context(Profile.OPENAI, model = "gpt-4o-transcribe"),
            harness.audio,
        )

        assertEquals(0.0, (submitted as Submitted.Finished).result.segments[0].end)
    }

    @Test
    fun `the diarized answer is the finished transcript`() = runBlocking {
        harness.server.reply(DIARIZED)

        val submitted = provider(Profile.OPENAI).submit(context(Profile.OPENAI), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.2, result.segments[0].end)
        assertEquals("speaker_0", result.segments[0].speaker)
        assertEquals("speaker_1", result.segments[1].speaker)
        assertEquals(9.1, result.durationSec)
        assertEquals(OpenAiCompatProvider.GPT_4O_DIARIZE, result.model)
    }

    @Test
    fun `all four names are ones this build can run`() {
        Profile.entries.forEach { profile ->
            assertEquals(profile.provider, SttProviders.create(profile.provider)?.name, "for $profile")
        }
    }

    @Test
    fun `a poll is a bug, because a sync provider never hands out a ref`() = runBlocking {
        val failure = assertFailsWith<StepFailure> { provider(Profile.GROQ).poll(context(Profile.GROQ), "whatever") }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `a body that is not JSON is worth another try`() = runBlocking {
        harness.server.reply("<html>gateway</html>")

        val failure = assertFailsWith<StepFailure> {
            provider(Profile.OPENAI).submit(context(Profile.OPENAI), harness.audio)
        }

        assertEquals(true, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertReason(429, CoreMessage.QUOTA, retryable = true)
        assertReason(500, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertReason(413, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    private suspend fun assertReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = """{"error":{"message":"no"}}""")
        val failure = assertFailsWith<StepFailure> {
            OpenAiCompatProvider.openai().submit(
                harness.sttContext(OpenAiCompatProvider.OPENAI_NAME, "openai-key"),
                harness.audio,
            )
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private enum class Profile(val provider: String, val key: String) {
        OPENAI(OpenAiCompatProvider.OPENAI_NAME, "openai-key"),
        GROQ(OpenAiCompatProvider.GROQ_NAME, "groq-key"),
        TOGETHER(OpenAiCompatProvider.TOGETHER_NAME, "together-key"),
        MISTRAL(OpenAiCompatProvider.MISTRAL_NAME, "mistral-key"),
    }

    private fun provider(profile: Profile): OpenAiCompatProvider = when (profile) {
        Profile.OPENAI -> OpenAiCompatProvider.openai()
        Profile.GROQ -> OpenAiCompatProvider.groq()
        Profile.TOGETHER -> OpenAiCompatProvider.together()
        Profile.MISTRAL -> OpenAiCompatProvider.mistral()
    }

    private fun context(
        profile: Profile,
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
        model: String? = null,
        audioDurationSec: Double? = null,
    ): SttContext = harness.sttContext(
        provider = profile.provider,
        apiKey = profile.key,
        language = language,
        diarize = diarize,
        speakers = speakers,
        speakersExpected = speakersExpected,
        model = model,
        audioDurationSec = audioDurationSec,
    )

    private companion object {
        /** `diarized_json`: seconds, a speaker on every segment, and a top-level duration. */
        const val DIARIZED = """
            {"task":"transcribe","duration":9.1,"text":"안녕하세요 반갑습니다",
             "segments":[
               {"id":0,"start":0.0,"end":3.2,"text":"안녕하세요","speaker":"speaker_0"},
               {"id":1,"start":3.6,"end":9.1,"text":"반갑습니다","speaker":"speaker_1"}
             ]}
        """

        /** `verbose_json`: no speakers, and the words beside the segments rather than inside them. */
        const val VERBOSE = """
            {"task":"transcribe","language":"korean","duration":9.1,"text":"안녕하세요 반갑습니다",
             "segments":[
               {"id":0,"start":0.0,"end":3.2,"text":"안녕하세요"},
               {"id":1,"start":3.6,"end":9.1,"text":"반갑습니다"}
             ],
             "words":[
               {"word":"안녕하세요","start":0.0,"end":1.2},
               {"word":"반갑습니다","start":3.6,"end":4.8}
             ]}
        """

        /** Together answers `speaker_segments` when it diarized, with the words already grouped. */
        const val TOGETHER = """
            {"duration":9.1,"text":"안녕하세요 반갑습니다",
             "speaker_segments":[
               {"speaker_id":"0","start":0.0,"end":3.2,"text":"안녕하세요",
                "words":[{"word":"안녕하세요","start":0.0,"end":1.2,"speaker_id":"0"}]},
               {"speaker_id":"1","start":3.6,"end":9.1,"text":"반갑습니다"}
             ]}
        """

        /** Mistral: `speaker_id` on each segment and the length only in the usage block. */
        const val MISTRAL = """
            {"text":"안녕하세요 반갑습니다","language":"ko",
             "segments":[
               {"start":0.0,"end":3.2,"text":"안녕하세요","speaker_id":0},
               {"start":3.6,"end":9.1,"text":"반갑습니다","speaker_id":1}
             ],
             "usage":{"prompt_audio_seconds":9.1}}
        """

        const val PLAIN = """{"text":"안녕하세요 반갑습니다","usage":{"type":"duration","seconds":12.5}}"""

        /** What every `gpt-*-transcribe` model bills in: tokens, which say nothing about time. */
        const val TOKENS = """
            {"text":"안녕하세요 반갑습니다",
             "usage":{"type":"tokens","input_tokens":420,"output_tokens":30,"total_tokens":450}}
        """
    }
}
