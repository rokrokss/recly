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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Language
import recly.core.model.Speakers

/**
 * docs/08 `azure` fast transcription. Synchronous like `clova`, and like `clova` it is addressed at
 * the customer's own resource — so a step without an `invokeUrl` cannot be run at all.
 */
class AzureProviderTest {
    private val harness = ProviderHarness()
    private val provider = AzureProvider()

    @Test
    fun `the audio and the definition go up as one multipart form`() = runBlocking {
        harness.server.reply(TRANSCRIBED)

        provider.submit(context(), harness.audio)

        val request = harness.server.request(0)
        assertEquals("POST", request.method)
        assertEquals(
            "$INVOKE_URL/speechtotext/transcriptions:transcribe?api-version=${AzureProvider.API_VERSION}",
            request.url,
        )
        assertEquals("azure-key", request.headers[AzureProvider.KEY_HEADER])
        assertTrue("filename=\"joined.m4a\"" in request.multipartHeaders("audio"))
        assertEquals(ProviderHarness.AUDIO, request.multipartPart("audio"))
        assertTrue("application/json" in request.multipartHeaders("definition"))
    }

    @Test
    fun `the definition carries the locales and the speaker ceiling`() = runBlocking {
        harness.server.reply(TRANSCRIBED)

        provider.submit(context(speakers = Speakers(min = 2, max = 6)), harness.audio)

        val definition = definition(0)
        assertEquals(listOf(AzureProvider.KO), locales(definition))
        val diarization = definition["diarization"]!!.jsonObject
        assertEquals(true, diarization["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(6, diarization["maxSpeakers"]?.jsonPrimitive?.int)
    }

    @Test
    fun `the recorded participant count is the ceiling it knows`() = runBlocking {
        harness.server.reply(TRANSCRIBED)

        provider.submit(context(speakers = Speakers(min = 1, max = 10), speakersExpected = 3), harness.audio)

        assertEquals(3, definition(0)["diarization"]!!.jsonObject["maxSpeakers"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a speaker ceiling the API would reject is pulled into the range it takes`() = runBlocking {
        // One participant is a real recording; `maxSpeakers: 1` is a 400 from this API.
        harness.server.reply(TRANSCRIBED)
        provider.submit(context(speakers = Speakers(min = 1, max = 1), speakersExpected = 1), harness.audio)
        assertEquals(2, maxSpeakers(0))

        harness.server.reply(TRANSCRIBED)
        provider.submit(context(speakers = Speakers(min = 1, max = 1)), harness.audio)
        assertEquals(2, maxSpeakers(1))

        harness.server.reply(TRANSCRIBED)
        provider.submit(context(speakersExpected = 40), harness.audio)
        assertEquals(35, maxSpeakers(2))
    }

    @Test
    fun `diarization off leaves the whole block out`() = runBlocking {
        harness.server.reply(TRANSCRIBED)

        provider.submit(context(diarize = false), harness.audio)

        assertNull(definition(0)["diarization"])
    }

    @Test
    fun `every workflow language maps onto the locales the provider knows`() = runBlocking {
        val expected = mapOf(
            Language.KO to listOf(AzureProvider.KO),
            Language.EN to listOf(AzureProvider.EN),
            // The one provider that takes both halves of 한영 혼용 at once.
            Language.KO_EN to listOf(AzureProvider.KO, AzureProvider.EN),
            // An empty list is how this API is asked to detect the language itself.
            Language.AUTO to emptyList(),
        )
        expected.entries.forEachIndexed { index, (language, wanted) ->
            harness.server.reply(TRANSCRIBED)
            provider.submit(context(language = language), harness.audio)
            assertEquals(wanted, locales(definition(index)), "for $language")
        }
    }

    @Test
    fun `one phrase is one segment, on the second axis`() = runBlocking {
        harness.server.reply(TRANSCRIBED)

        val submitted = provider.submit(context(), harness.audio)

        val result = (submitted as Submitted.Finished).result
        assertEquals(2, result.segments.size)
        assertEquals(0.0, result.segments[0].start)
        assertEquals(3.2, result.segments[0].end)
        assertEquals("1", result.segments[0].speaker)
        assertEquals("안녕하세요", result.segments[0].text)
        assertEquals(listOf(SttWord(0.0, 1.2, "안녕하세요")), result.segments[0].words)
        assertEquals("2", result.segments[1].speaker)
        assertEquals(9.1, result.durationSec)
        assertEquals(AzureProvider.KO, result.language)
        assertNull(result.model)
    }

    @Test
    fun `the name is one this build can run`() {
        assertEquals(AzureProvider.NAME, SttProviders.create(AzureProvider.NAME)?.name)
    }

    @Test
    fun `a poll is a bug, because a sync provider never hands out a ref`() = runBlocking {
        val failure = assertFailsWith<StepFailure> { provider.poll(context(), "whatever") }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
    }

    @Test
    fun `a step with no invokeUrl cannot be run at all`() = runBlocking {
        val failure = assertFailsWith<StepFailure> {
            provider.submit(harness.sttContext(AzureProvider.NAME, "azure-key"), harness.audio)
        }

        assertEquals(false, failure.retryable)
        assertEquals(CoreMessage.PROVIDER_ERROR, CoreMessageRef.parse(failure.reason)?.message)
        assertEquals(0, harness.server.requests.size)
    }

    @Test
    fun `the http status decides the docs 08 reason`() = runBlocking {
        assertReason(401, CoreMessage.AUTH_REJECTED, retryable = false)
        assertReason(429, CoreMessage.QUOTA, retryable = true)
        assertReason(500, CoreMessage.PROVIDER_ERROR, retryable = true)
        assertReason(400, CoreMessage.UNSUPPORTED_AUDIO, retryable = false)
    }

    private suspend fun assertReason(status: Int, message: CoreMessage, retryable: Boolean) {
        val harness = ProviderHarness()
        harness.server.reply(status = status, body = """{"error":{"message":"no"}}""")
        val failure = assertFailsWith<StepFailure> {
            AzureProvider().submit(
                harness.sttContext(AzureProvider.NAME, "azure-key", invokeUrl = INVOKE_URL),
                harness.audio,
            )
        }
        assertEquals(message, CoreMessageRef.parse(failure.reason)?.message, "HTTP $status")
        assertEquals(retryable, failure.retryable, "HTTP $status")
    }

    private fun maxSpeakers(index: Int): Int =
        definition(index)["diarization"]!!.jsonObject["maxSpeakers"]!!.jsonPrimitive.int

    private fun locales(definition: JsonObject): List<String> =
        definition["locales"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun definition(index: Int): JsonObject =
        Json.parseToJsonElement(harness.server.request(index).multipartPart("definition")).jsonObject

    private fun context(
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
    ): SttContext = harness.sttContext(
        provider = AzureProvider.NAME,
        apiKey = "azure-key",
        language = language,
        diarize = diarize,
        speakers = speakers,
        speakersExpected = speakersExpected,
        invokeUrl = INVOKE_URL,
    )

    private companion object {
        const val INVOKE_URL = "https://recly.cognitiveservices.azure.com"

        /** The documented shape: milliseconds everywhere, an integer speaker, a locale per phrase. */
        const val TRANSCRIBED = """
            {"durationMilliseconds":9100,
             "combinedPhrases":[{"text":"안녕하세요 반갑습니다"}],
             "phrases":[
               {"offsetMilliseconds":0,"durationMilliseconds":3200,"text":"안녕하세요","speaker":1,
                "locale":"ko-KR","confidence":0.9,
                "words":[{"text":"안녕하세요","offsetMilliseconds":0,"durationMilliseconds":1200}]},
               {"offsetMilliseconds":3600,"durationMilliseconds":5500,"text":"반갑습니다","speaker":2,
                "locale":"ko-KR","confidence":0.9}
             ]}
        """
    }
}
