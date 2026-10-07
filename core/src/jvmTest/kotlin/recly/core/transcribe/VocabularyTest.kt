package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.Path
import recly.core.model.Language

/**
 * docs/08 "Vocabulary": what each adapter puts on the wire for the user's list, checked against each
 * provider's API reference on 2026-10-07 — and what it leaves off where sending could cost a
 * transcription.
 */
class VocabularyTest {
    private val terms = listOf("Recly", "민수", "CLOVA Speech", "Q3 roadmap")

    private suspend fun sent(
        provider: SttProvider,
        language: Language = Language.EN,
        model: String? = null,
        diarize: Boolean = true,
        vocabulary: List<String> = terms,
        replies: List<String> = listOf("""{"id":"job-1"}"""),
        invokeUrl: String? = null,
    ): ProviderHarness {
        val harness = ProviderHarness()
        replies.forEach { harness.server.reply(it) }
        // RTZR's key is a `{clientId}:{clientSecret}` pair (docs/08).
        val ctx = harness.sttContext(provider.name, "client:secret", language = language, diarize = diarize, model = model,
            invokeUrl = invokeUrl, vocabulary = vocabulary)
        runCatching { provider.submit(ctx, harness.audio) }
        return harness
    }

    private fun Recorded.part(name: String): JsonObject = Json.parseToJsonElement(multipartPart(name)) as JsonObject

    @Test
    fun `assemblyai sends keyterms_prompt where universal-3-5-pro runs, and nothing for Korean`() = runBlocking {
        val upload = """{"upload_url":"https://cdn/x"}"""
        val english = sent(AssemblyAiProvider(), replies = listOf(upload, """{"id":"t"}""")).server.request(1).json()
        assertEquals("""["Recly","민수","CLOVA Speech","Q3 roadmap"]""", english["keyterms_prompt"].toString())

        val korean = sent(AssemblyAiProvider(), language = Language.KO, replies = listOf(upload, """{"id":"t"}""")).server.request(1).json()
        assertFalse("keyterms_prompt" in korean)
    }

    @Test
    fun `deepgram repeats keyterm on nova-3, keywords on nova-2, and never both`() = runBlocking {
        val nova3 = sent(DeepgramProvider(), language = Language.KO).server.request(0).url
        assertTrue(nova3.endsWith("&mip_opt_out=true&keyterm=Recly&keyterm=%EB%AF%BC%EC%88%98&keyterm=CLOVA%20Speech&keyterm=Q3%20roadmap"), nova3)
        assertFalse("keywords=" in nova3)

        val nova2 = sent(DeepgramProvider(), model = "nova-2").server.request(0).url
        assertTrue(nova2.endsWith("&keywords=Recly&keywords=%EB%AF%BC%EC%88%98"), nova2)
        assertFalse("keyterm=" in nova2)

        val detecting = sent(DeepgramProvider(), language = Language.AUTO).server.request(0).url
        assertFalse("keyterm" in detecting, "keyterms with language detection are not documented")
    }

    @Test
    fun `deepgram stops the keyterms before the 500-token limit could fail the request`() {
        val long = (1..50).map { "가나다라마바사아자차$it" }
        val (_, kept) = Vocabulary.deepgram(long, "nova-3")!!
        assertTrue(kept.sumOf { Vocabulary.estimatedTokens(it) } <= 400)
        assertTrue(kept.size < long.size)
        assertEquals(long.take(kept.size), kept, "whole terms, in order")
    }

    @Test
    fun `openai prompts whisper-1 and leaves the diarizing model alone`() = runBlocking {
        val whisper = sent(OpenAiCompatProvider.openai(), model = "whisper-1", replies = listOf("{}")).server.request(0)
        assertEquals("Recly, 민수, CLOVA Speech, Q3 roadmap", whisper.multipartPart("prompt"))

        val diarizing = sent(OpenAiCompatProvider.openai(), replies = listOf("{}")).server.request(0)
        assertFalse("name=\"prompt\"" in diarizing.text, "gpt-4o-transcribe-diarize takes no prompt")
    }

    @Test
    fun `groq and together take the same prompt`() = runBlocking {
        for (provider in listOf(OpenAiCompatProvider.groq(), OpenAiCompatProvider.together())) {
            val request = sent(provider, language = Language.KO, replies = listOf("{}")).server.request(0)
            assertEquals("Recly, 민수, CLOVA Speech, Q3 roadmap", request.multipartPart("prompt"), provider.name)
        }
    }

    @Test
    fun `the prompt stays under Whisper's 224 tokens by whole terms`() {
        val many = (1..50).map { "용어번호$it" }
        val prompt = Vocabulary.prompt(many)!!
        assertTrue(Vocabulary.estimatedTokens(prompt) <= 224)
        assertTrue(prompt.endsWith(many[prompt.split(", ").size - 1]))
        assertNull(Vocabulary.prompt(emptyList()))
    }

    @Test
    fun `mistral repeats context_bias for English with spaces made underscores`() = runBlocking {
        val english = sent(OpenAiCompatProvider.mistral(), vocabulary = listOf("CLOVA Speech", "a,b", "Recly"), replies = listOf("{}")).server.request(0)
        assertEquals(listOf("CLOVA_Speech", "ab", "Recly"), english.multipartParts("context_bias"))
        assertFalse("name=\"prompt\"" in english.text)

        val korean = sent(OpenAiCompatProvider.mistral(), language = Language.KO, replies = listOf("{}")).server.request(0)
        assertEquals(emptyList(), korean.multipartParts("context_bias"), "experimental outside English")
    }

    @Test
    fun `elevenlabs repeats keyterms and drops what it would refuse`() = runBlocking {
        val request = sent(ElevenLabsProvider(), vocabulary = listOf("Recly", "a [b]", "one two three four five six"), replies = listOf("{}")).server.request(0)
        assertEquals(listOf("Recly"), request.multipartParts("keyterms"))
    }

    @Test
    fun `clova boosts Korean and English in the reference's own shape`() = runBlocking {
        val korean = sent(ClovaProvider(), language = Language.KO, vocabulary = listOf("클로바", "네", "a, b"), invokeUrl = CLOVA_URL, replies = listOf("{}"))
            .server.request(0).part("params")
        assertEquals("""[{"words":"클로바, a b"}]""", korean["boostings"].toString())

        val mixed = sent(ClovaProvider(), language = Language.KO_EN, invokeUrl = CLOVA_URL, replies = listOf("{}")).server.request(0).part("params")
        assertFalse("boostings" in mixed, "enko is not named")
    }

    @Test
    fun `rtzr sends Hangul keywords for Korean on sommers only`() = runBlocking {
        val token = """{"access_token":"jwt-1","expire_at":1787727600}"""
        val sommers = sent(RtzrProvider(), language = Language.KO, vocabulary = listOf("에스티티", "STT", "민수 씨", "가".repeat(21)),
            replies = listOf(token, """{"id":"job-1"}""")).server.request(1).part("config")
        assertEquals("""["에스티티"]""", sommers["keywords"].toString())

        val whisper = sent(RtzrProvider(), language = Language.KO, model = "whisper", vocabulary = listOf("에스티티", "STT", "위스퍼 V2"),
            replies = listOf(token, """{"id":"job-1"}""")).server.request(1).part("config")
        assertEquals("""["에스티티","STT","위스퍼 V2"]""", whisper["keywords"].toString())

        val english = sent(RtzrProvider(), language = Language.EN, replies = listOf(token, """{"id":"job-1"}""")).server.request(1).part("config")
        assertFalse("keywords" in english)
    }

    @Test
    fun `azure sends a phrase list with one locale and none to the multilingual model`() = runBlocking {
        val korean = sent(AzureProvider(), language = Language.KO, invokeUrl = AZURE_URL, replies = listOf("{}")).server.request(0).part("definition")
        assertEquals("""{"phrases":["Recly","민수","CLOVA Speech","Q3 roadmap"]}""", korean["phraseList"].toString())

        val auto = sent(AzureProvider(), language = Language.AUTO, invokeUrl = AZURE_URL, replies = listOf("{}")).server.request(0).part("definition")
        assertFalse("phraseList" in auto)
    }

    @Test
    fun `daglo boosts keywords for Korean only`() = runBlocking {
        val korean = sent(DagloProvider(), language = Language.KO, replies = listOf("""{"rid":"job-1"}""")).server.request(0).part("sttConfig")
        assertEquals("""{"enable":true,"keywords":["Recly","민수","CLOVA Speech","Q3 roadmap"]}""", korean["keywordBoost"].toString())

        val english = sent(DagloProvider(), language = Language.EN, replies = listOf("""{"rid":"job-1"}""")).server.request(0).part("sttConfig")
        assertFalse("keywordBoost" in english)
    }

    @Test
    fun `speechmatics puts the words in additional_vocab, auto included`() = runBlocking {
        val config = sent(SpeechmaticsProvider(), language = Language.AUTO).server.request(0).part("config")
        assertEquals(
            """[{"content":"Recly"},{"content":"민수"},{"content":"CLOVA Speech"},{"content":"Q3 roadmap"}]""",
            config["transcription_config"]!!.jsonObject["additional_vocab"].toString(),
        )
    }

    @Test
    fun `rev sends English phrases without digits, and nothing for Korean`() = runBlocking {
        val english = sent(RevProvider()).server.request(0).part("options")
        assertEquals("""[{"phrases":["Recly","CLOVA Speech"]}]""", english["custom_vocabularies"].toString())

        val korean = sent(RevProvider(), language = Language.KO).server.request(0).part("options")
        assertFalse("custom_vocabularies" in korean, "a list Rev cannot use fails the job, and Korean is not documented")
    }

    @Test
    fun `gladia turns the custom vocabulary on with plain strings`() = runBlocking {
        val uploaded = """{"audio_url":"https://api.gladia.io/file/x"}"""
        val body = sent(GladiaProvider(), language = Language.KO, replies = listOf(uploaded, """{"id":"job-1"}""")).server.request(1).json()
        assertEquals("true", body["custom_vocabulary"].toString())
        assertEquals("""{"vocabulary":["Recly","민수","CLOVA Speech","Q3 roadmap"]}""", body["custom_vocabulary_config"].toString())
    }

    @Test
    fun `an empty list sends no field anywhere`() = runBlocking {
        val deepgram = sent(DeepgramProvider(), vocabulary = emptyList()).server.request(0).url
        assertFalse("keyterm" in deepgram)
        val gladia = sent(GladiaProvider(), vocabulary = emptyList(), replies = listOf("""{"audio_url":"https://x"}""", "{}")).server.request(1).json()
        assertFalse("custom_vocabulary" in gladia)
    }

    @Test
    fun `the settings can tell which providers use a vocabulary`() {
        assertTrue(SttProviders.supportsVocabulary("deepgram", null, Language.KO))
        assertFalse(SttProviders.supportsVocabulary("deepgram", null, Language.AUTO))
        assertFalse(SttProviders.supportsVocabulary("openai", null, Language.KO), "the plan's OpenAI default diarizes")
        assertTrue(SttProviders.supportsVocabulary("openai", "whisper-1", Language.KO))
        assertFalse(SttProviders.supportsVocabulary("assemblyai", null, Language.KO))
        assertTrue(SttProviders.supportsVocabulary("assemblyai", null, Language.JA))
        assertTrue(SttProviders.supportsVocabulary("rtzr", null, Language.KO))
        assertFalse(SttProviders.supportsVocabulary("rtzr", null, Language.JA))
        assertFalse(SttProviders.supportsVocabulary("speechmatics", "melia-1", Language.EN))
        assertTrue(SttProviders.supportsVocabulary("gladia", null, Language.KO))
        assertFalse(SttProviders.supportsVocabulary("unknown", null, Language.KO))
    }

    @Test
    fun `the plan's list reaches the adapter`() = runBlocking {
        var seen: List<String>? = null
        val capture = object : SttProvider {
            override val name = "assemblyai"
            override suspend fun submit(ctx: SttContext, file: Path): Submitted {
                seen = ctx.vocabulary
                return Submitted.Finished(SttResult(emptyList(), "ko", 1.0, null))
            }
            override suspend fun poll(ctx: SttContext, ref: String): PollResult = PollResult.Pending
        }
        val h = TranscribeHarness(providers = { capture })

        h.runToDone(h.transcribeStep().copy(vocabulary = listOf("Recly", "민수")))

        assertEquals(listOf("Recly", "민수"), seen)
    }

    private companion object {
        const val CLOVA_URL = "https://clovaspeech-gw.ncloud.com/external/v1/1/abc"
        const val AZURE_URL = "https://res.cognitiveservices.azure.com"
    }
}
