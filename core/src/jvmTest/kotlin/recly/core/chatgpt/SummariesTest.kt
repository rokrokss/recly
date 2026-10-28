package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import recly.core.message.CoreMessage
import recly.core.model.Highlight
import recly.core.model.Track
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRepository
import recly.core.testing.testMeta
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/** docs/08 "AI requests" and "Ask": the request every ChatGPT feature shares, and questions about one recording. */
class SummariesTest {
    private class Fixture(
        val transcript: Transcript? = TRANSCRIPT,
        highlights: List<Highlight> = emptyList(),
        requireTransferConsent: Boolean = false,
        locale: String = "en",
    ) {
        val h = ChatGptHarness(requireTransferConsent, locale)
        val recordings = RecordingRepository(h.db, h.deps)
        val consents = TransferConsents(h.db, h.deps)
        val summaries = Summaries(h.db, h.deps, h.account, recordings, { transcript }, consents)
        val meta = testMeta(title = "Weekly sync").copy(highlights = highlights)
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()

        suspend fun ready() {
            recordings.create(meta, dir)
            h.signIn()
        }

        fun request(): Pair<String, String> {
            val body = h.server.requests.last().json()
            val input = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            return body["instructions"]!!.jsonPrimitive.content to input
        }
    }

    @Test
    fun `a question goes to the plan as text only, and its answer is shown until it is closed`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(stream("Subject: Friday"), headers = SSE)

        val state = f.summaries.ask(ID, AskPreset.FOLLOW_UP_EMAIL, null)

        val answer = (state as AskState.Ready).answer
        assertEquals("Subject: Friday", answer.text)
        assertEquals(AskPreset.FOLLOW_UP_EMAIL, answer.preset)
        assertEquals("gpt-a", answer.model)
        assertEquals("GPT A", answer.modelName, "the plan's name, kept for when the footer is read signed out")
        assertEquals(state, f.summaries.observeAsk(ID).first())
        val request = f.h.server.requests.last()
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        val body = request.json()
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(setOf("model", "instructions", "input", "store", "stream"), body.keys, "nothing the plan's API refuses")
        val (instructions, input) = f.request()
        assertTrue("You answer a question about a recording's transcript." in instructions)
        assertTrue("Mina: We ship on Friday." in input)
        assertFalse("Weekly sync" in input, "the transcript's text only — not the title")
        assertFalse(MARKED in input, "no marks, no heading")
        assertTrue(input.endsWith("${Summaries.QUESTION_HEADING}\n${Summaries.presetQuestion(AskPreset.FOLLOW_UP_EMAIL)}\n"))
        assertTrue("summary.ask.done" in f.h.logger.events)

        f.summaries.clearAsk(ID)
        assertEquals(AskState.None, f.summaries.observeAsk(ID).first())
    }

    @Test
    fun `the user's own question wins over a preset and is answered in its own language`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(stream("금요일"), headers = SSE)
        val answer = (f.summaries.ask(ID, AskPreset.ACTION_ITEMS, "  언제 배포하나요?  ") as AskState.Ready).answer
        assertNull(answer.preset)
        assertEquals("언제 배포하나요?", answer.question)
        val (instructions, input) = f.request()
        assertTrue("Write everything in the language of the question" in instructions)
        assertTrue(input.endsWith("${Summaries.QUESTION_HEADING}\n언제 배포하나요?\n"))
        assertEquals(AskState.None, Fixture().summaries.ask(ID, null, "   "), "nothing asked")
    }

    @Test
    fun `About you and the marked moments shape the question`() = runBlocking {
        val f = Fixture(highlights = listOf(Highlight(2.0)))
        f.ready()
        assertEquals("Product manager", f.summaries.setPreferences(SummaryPreferences(aboutMe = "  Product manager  ")).aboutMe)
        f.h.server.reply(stream("- Mina: ship"), headers = SSE)

        f.summaries.ask(ID, AskPreset.ACTION_ITEMS, null)

        val (instructions, input) = f.request()
        assertTrue("About the person who made the recording, to judge what matters to them: Product manager\n" in instructions)
        assertTrue("moments they marked as important" in instructions)
        assertTrue("$MARKED\n- [00:00:02] We ship on Friday.\n\n${Summaries.QUESTION_HEADING}\n" in input, input)
        assertFalse("Weekly sync" in input, "still no title")
        assertEquals(SummaryPreferences.ABOUT_MAX, f.summaries.setPreferences(SummaryPreferences(aboutMe = "x".repeat(400))).aboutMe.length)
    }

    @Test
    fun `the person who made the recording is Me to the model`() = runBlocking {
        val f = Fixture(transcript = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", me = true))))
        f.ready()
        f.h.server.reply(stream("Notes"), headers = SSE)
        f.summaries.ask(ID, AskPreset.MY_SPEAKING, null)
        val (instructions, input) = f.request()
        assertTrue("\"Me\" in the transcript is the person who made the recording" in instructions)
        assertTrue("[00:00:00] Me: We ship on Friday." in input)
    }

    @Test
    fun `a language change in the app reaches the next question and the presets`() = runBlocking {
        val f = Fixture(transcript = TRANSCRIPT.copy(language = "auto"), locale = "en")
        f.ready()
        assertFalse(AskPreset.TRANSLATE in Fixture(locale = "en").summaries.askPresets(ID))
        f.h.server.reply(stream("Questions"), headers = SSE)
        f.summaries.ask(ID, AskPreset.OPEN_QUESTIONS, null)
        assertTrue("Write everything in English (en)" in f.request().first)

        f.summaries.setLocale("ko-KR")
        f.h.server.reply(stream("질문"), headers = SSE)
        f.summaries.ask(ID, AskPreset.OPEN_QUESTIONS, null)
        assertTrue("Write everything in Korean (ko)" in f.request().first, "no restart needed")
        val english = Fixture(locale = "en")
        english.summaries.setLocale("ja")
        assertTrue(AskPreset.TRANSLATE in english.summaries.askPresets(ID))
    }

    @Test
    fun `the question names the recording's language`() = runBlocking {
        val f = Fixture(transcript = TRANSCRIPT.copy(language = "ja"))
        f.ready()
        f.h.server.reply(stream("要約"), headers = SSE)
        f.summaries.ask(ID, AskPreset.ACTION_ITEMS, null)
        assertTrue("Write everything in Japanese (ja)" in f.request().first)
    }

    @Test
    fun `a usage limit in the middle of the answer fails the question with the plan's reason`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Half\"}\n\n" +
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n",
            headers = SSE,
        )
        val failed = AskState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), AskPreset.ACTION_ITEMS, null)
        assertEquals(failed, f.summaries.ask(ID, AskPreset.ACTION_ITEMS, null))
        assertEquals(failed, f.summaries.observeAsk(ID).first())
        assertTrue("summary.ask.failed" in f.h.logger.events)
    }

    @Test
    fun `on iPhone nothing is sent before the plan is allowed as a destination`() = runBlocking {
        val f = Fixture(requireTransferConsent = true)
        f.ready()
        val sent = f.h.server.requests.size
        assertEquals(
            AskState.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code(), AskPreset.OPEN_QUESTIONS, null),
            f.summaries.ask(ID, AskPreset.OPEN_QUESTIONS, null),
        )
        assertEquals(sent, f.h.server.requests.size)
        assertEquals(AskState.None, f.summaries.observeAsk(ID).first(), "a missing permission is the dialog's to show, not a failed answer")

        f.consents.grant(listOf(TransferTargets.chatGptSummary()))
        f.h.server.reply(stream("Done"), headers = SSE)
        assertTrue(f.summaries.ask(ID, AskPreset.OPEN_QUESTIONS, null) is AskState.Ready)
    }

    @Test
    fun `a recording with no transcript is not sent`() = runBlocking {
        val f = Fixture(transcript = null)
        f.ready()
        val sent = f.h.server.requests.size
        assertEquals(
            AskState.Failed(CoreMessage.STEP_FAILED.code("no transcript"), AskPreset.ACTION_ITEMS, null),
            f.summaries.ask(ID, AskPreset.ACTION_ITEMS, null),
        )
        assertEquals(sent, f.h.server.requests.size)
        assertEquals(emptyList(), f.summaries.askPresets(ID))
    }

    @Test
    fun `without a sign-in the question asks for one`() = runBlocking {
        val f = Fixture()
        f.recordings.create(f.meta, f.dir)
        assertEquals(
            AskState.Failed(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code(), AskPreset.ACTION_ITEMS, null),
            f.summaries.ask(ID, AskPreset.ACTION_ITEMS, null),
        )
        assertTrue(f.h.server.requests.none { it.url.endsWith("/responses") })
    }

    @Test
    fun `Translate is offered for another language, and feedback only when the transcript knows who the user is`() = runBlocking {
        val english = Fixture(locale = "en")
        assertEquals(listOf(AskPreset.FOLLOW_UP_EMAIL, AskPreset.ACTION_ITEMS, AskPreset.OPEN_QUESTIONS), english.summaries.askPresets(ID))
        val korean = Fixture(transcript = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", me = true))), locale = "ko")
        assertEquals(AskPreset.entries.toList(), korean.summaries.askPresets(ID))
        assertFalse(AskPreset.TRANSLATE in Fixture(transcript = TRANSCRIPT.copy(language = "ko-en"), locale = "ja").summaries.askPresets(ID), "a mix is already in the app's language")
        assertTrue(SummaryLanguage.translatable("ja", "ko-KR"))
        assertFalse(SummaryLanguage.translatable("auto", "ko"))
        assertEquals("Korean (ko)", SummaryLanguage.app("ko-KR"))
    }

    @Test
    fun `citations are read the same way everywhere`() {
        val text = "- Ship [00:12:34]\n- Hire [12:34] and [1:02:03], not [00:61:00] or [today]"
        val found = SummaryCitations.parse(text)
        assertEquals(listOf(754.0, 754.0, 3723.0), found.map { it.atSec })
        assertEquals(listOf("[00:12:34]", "[12:34]", "[1:02:03]"), found.map { text.substring(it.offset, it.offset + it.length) })
        assertEquals(emptyList(), SummaryCitations.parse("no times here"))
    }

    @Test
    fun `the stream is read to its terminal event`() {
        assertEquals("Hello, world", ResponseStream.text(stream("Hello", ", world")))
        // No deltas, only the completed response's output.
        assertEquals(
            "Whole",
            ResponseStream.text(
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Whole\"}]}]}}\n\n",
            ),
        )
        assertEquals("Plain", ResponseStream.text("""{"output":[{"content":[{"type":"output_text","text":"Plain"}]}]}"""))
        assertEquals("CRLF", ResponseStream.text(stream("CRLF").replace("\n", "\r\n")))
        val early = assertFailsWith<ChatGptFailure> {
            ResponseStream.text("data: {\"type\":\"response.output_text.delta\",\"delta\":\"cut\"}\n\n")
        }
        assertEquals(CoreMessage.PROVIDER_ERROR.code(detail = "stream ended early"), early.reason)
        val incomplete = assertFailsWith<ChatGptFailure> {
            ResponseStream.text("data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n")
        }
        assertEquals(CoreMessage.PROVIDER_ERROR.code(detail = "incomplete max_output_tokens"), incomplete.reason)
    }

    @Test
    fun `notes and answers are written in the recording's language, or the app's when it has none`() {
        assertEquals("Korean (ko)", SummaryLanguage.of("ko", "en"))
        assertEquals("Thai (th)", SummaryLanguage.of("th", "ko"))
        assertEquals("Traditional Chinese (zh-tw)", SummaryLanguage.of("zh-tw", "en"))
        assertEquals("Japanese (ja)", SummaryLanguage.of("auto", "ja"))
        assertEquals("Arabic (ar)", SummaryLanguage.of("", "ar-EG"))
        assertEquals("English (en)", SummaryLanguage.of("ko-en", "en-US"), "a mix the app language is part of")
        assertEquals("Korean (ko)", SummaryLanguage.of("ko-en", "fr"))
        assertEquals("Brazilian Portuguese (pt-br)", SummaryLanguage.of("auto", "pt-BR"))
        assertEquals("English (en)", SummaryLanguage.of("xx", "zz"))
    }

    @Test
    fun `the plan as a destination is a valid grant`() {
        val target = TransferTargets.chatGptSummary()
        assertEquals("summarize", target.kind)
        assertEquals("https://api.openai.com/v1", target.endpoint)
        assertTrue(TransferTargets.valid(target))
        assertFalse(TransferTargets.valid(target.copy(endpoint = "https://example.com/v1")))
    }

    private companion object {
        const val ID = "01J9ABCDEF0123456789ABCDEF"
        const val MARKED = Summaries.MARKED_HEADING
        val SSE = mapOf("Content-Type" to "text/event-stream")

        val TRANSCRIPT = Transcript(
            recordingId = ID,
            track = Track.MONO,
            language = "en",
            provider = TranscriptProvider("openai"),
            createdAt = "2026-08-26T01:20:00Z",
            durationSec = 60.0,
            speakers = listOf(TranscriptSpeaker("S1", "Mina")),
            segments = listOf(TranscriptSegment(0.0, 4.0, "S1", "We ship on Friday.")),
        )

        fun stream(vararg deltas: String): String = buildString {
            append("event: response.created\ndata: {\"type\":\"response.created\"}\n\n")
            deltas.forEach {
                append("data: {\"type\":\"response.output_text.delta\",\"delta\":")
                append(kotlinx.serialization.json.Json.encodeToString(String.serializer(), it))
                append("}\n\n")
            }
            append("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n")
        }
    }
}
