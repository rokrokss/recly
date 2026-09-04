package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language

/**
 * docs/08 `deepgram`. Every option is a query parameter and the body is nothing but the audio, so
 * what a test asserts on here is the URL.
 */
class DeepgramProviderTest {
    private val harness = ProviderHarness()
    private val provider = DeepgramProvider()

    @Test
    fun `the audio is the body and the options are the query`() = runBlocking {
        harness.server.reply(UTTERANCES)

        provider.submit(context(), harness.audio)

        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals(
            "${DeepgramProvider.BASE}/listen?model=${DeepgramProvider.MODEL}&smart_format=true" +
                "&punctuate=true&utterances=true&diarize_model=latest&language=ko",
            request.url,
        )
        assertEquals("Token deepgram-key", request.headers["Authorization"])
        assertEquals("audio/mp4", request.headers["Content-Type"])
        assertEquals(ProviderHarness.AUDIO, request.text)
    }

    @Test
    fun `the step's own model replaces the default`() = runBlocking {
        harness.server.reply(UTTERANCES)

        provider.submit(context(model = "nova-2"), harness.audio)

        assertTrue("model=nova-2" in harness.server.request(0).url)
    }

    @Test
    fun `diarization off asks for none`() = runBlocking {
        harness.server.reply(UTTERANCES)

        provider.submit(context(diarize = false), harness.audio)

        assertFalse("diarize_model" in harness.server.request(0).url)
    }

    @Test
    fun `every workflow language maps onto one the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to "language=ko",
            Language.EN to "language=en",
            Language.KO_EN to "language=ko",
            // This provider has a detection mode of its own, and it replaces the language.
            Language.AUTO to "detect_language=true",
        )
        expected.entries.forEachIndexed { index, (language, param) ->
            harness.server.reply(UTTERANCES)
            provider.submit(context(language = language), harness.audio)
            assertTrue(param in harness.server.request(index).url, "for $language")
        }
        // Detection replaces the language rather than joining it.
        assertFalse("&language=" in harness.server.request(3).url)
    }

    @Test
    fun `one utterance is one segment, with its punctuated words`() = runBlocking {
        harness.server.reply(UTTERANCES)

        val submitted = provider.submit(context(), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.2, result.segments[0].end)
        assertEquals("0", result.segments[0].speaker)
        assertEquals("안녕하세요.", result.segments[0].text)
        assertEquals(listOf(SttWord(0.0, 1.2, "안녕하세요.")), result.segments[0].words)
        assertEquals("1", result.segments[1].speaker)
        assertEquals(9.1, result.durationSec)
        // Nothing was detected, so what was asked for is what it heard.
        assertEquals("ko", result.language)
        assertEquals(DeepgramProvider.MODEL, result.model)
    }

    @Test
    fun `a detected language is the one the response reports`() = runBlocking {
        harness.server.reply(DETECTED)

        val submitted = provider.submit(context(language = Language.AUTO), harness.audio)

        assertEquals("en", (submitted as Submitted.Finished).result.language)
    }

    @Test
    fun `the name is one this build can run`() {
        assertEquals(DeepgramProvider.NAME, SttProviders.create(DeepgramProvider.NAME)?.name)
    }

    @Test
    fun `a poll is a bug, because a sync provider never hands out a ref`() = runBlocking {
        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "whatever") }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertReason(403, CoreMessage.AUTH_REJECTED, retryable = false)
        assertReason(402, CoreMessage.QUOTA, retryable = true)
        assertReason(503, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertReason(400, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    private suspend fun assertReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = """{"err_msg":"no"}""")
        val failure = assertFailsWith<StepFailure> {
            DeepgramProvider().submit(harness.sttContext(DeepgramProvider.NAME, "deepgram-key"), harness.audio)
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        model: String? = null,
    ): SttContext = harness.sttContext(
        provider = DeepgramProvider.NAME,
        apiKey = "deepgram-key",
        language = language,
        diarize = diarize,
        model = model,
    )

    private companion object {
        /** The documented shape: seconds, an integer speaker, and both word forms. */
        const val UTTERANCES = """
            {"metadata":{"duration":9.1,"channels":1},
             "results":{"channels":[{"alternatives":[{"transcript":"안녕하세요 반갑습니다"}]}],
              "utterances":[
                {"start":0.0,"end":3.2,"transcript":"안녕하세요.","speaker":0,
                 "words":[{"word":"안녕하세요","punctuated_word":"안녕하세요.","start":0.0,"end":1.2}]},
                {"start":3.6,"end":9.1,"transcript":"반갑습니다.","speaker":1}
              ]}}
        """

        const val DETECTED = """
            {"metadata":{"duration":2.0},
             "results":{"channels":[{"detected_language":"en"}],
              "utterances":[{"start":0.0,"end":2.0,"transcript":"hello","speaker":0}]}}
        """
    }
}
