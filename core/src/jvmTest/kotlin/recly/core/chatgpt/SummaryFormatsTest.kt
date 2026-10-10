package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import recly.core.message.CoreMessage
import recly.core.model.Highlight
import recly.core.model.Track
import recly.core.privacy.TransferConsents
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRepository
import recly.core.testing.testMeta
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/** docs/08 "Summaries" and "Ask": formats, About you, marked moments, citations, and questions about one recording. */
class SummaryFormatsTest {
    private class Fixture(
        transcript: Transcript = TRANSCRIPT,
        highlights: List<Highlight> = emptyList(),
        requireTransferConsent: Boolean = false,
        locale: String = "en",
    ) {
        val h = ChatGptHarness(requireTransferConsent, locale)
        val recordings = RecordingRepository(h.db, h.deps)
        val summaries = Summaries(h.db, h.deps, h.account, recordings, { transcript }, TransferConsents(h.db, h.deps))
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
    fun `the format, About you and the marked moments shape the request, and the summary keeps its format`() = runBlocking {
        val f = Fixture(highlights = listOf(Highlight(2.0)))
        f.ready()
        f.summaries.setPreferences(SummaryPreferences(SummaryFormat.LECTURE, aboutMe = "  Product manager  "))
        f.h.server.reply(stream("Summary\n- Ship [00:00:02]"), headers = SSE)

        val summary = (f.summaries.summarize(ID) as SummaryState.Ready).summary

        val (instructions, input) = f.request()
        assertTrue("This is a lecture or a talk." in instructions)
        assertTrue("About the person who made the recording, to judge what matters to them: Product manager\n" in instructions)
        assertTrue("[HH:MM:SS]" in instructions, "every point says when it was said")
        assertTrue("moments they marked as important" in instructions)
        assertTrue(input.endsWith("${Summaries.MARKED_HEADING}\n- [00:00:02] We ship on Friday.\n"), input)
        assertFalse("Weekly sync" in input, "still no title")
        assertEquals("lecture", summary.format)
        assertEquals(SummaryFormat.LECTURE, summary.summaryFormat)
    }

    @Test
    fun `Summarize as uses the format asked for once, and General is written without a format`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.summaries.setPreferences(SummaryPreferences(SummaryFormat.INTERVIEW))
        f.h.server.reply(stream("Notes"), headers = SSE)
        assertNull((f.summaries.summarize(ID, SummaryFormat.AUTO) as SummaryState.Ready).summary.format)
        assertTrue("Write these sections in this order" in f.request().first)
        assertFalse("interview" in f.request().first)
        assertFalse(MARKED in f.request().second, "no marks, no heading")

        f.h.server.reply(stream("Notes"), headers = SSE)
        assertEquals("interview", (f.summaries.summarize(ID) as SummaryState.Ready).summary.format, "Settings' format otherwise")
    }

    @Test
    fun `My format is the user's own instructions, and without words it is not on offer`() = runBlocking {
        val f = Fixture()
        f.ready()
        val kept = f.summaries.setPreferences(SummaryPreferences(SummaryFormat.CUSTOM, customFormat = "   ", aboutMe = "x".repeat(400)))
        assertEquals("", kept.customFormat)
        assertEquals(SummaryPreferences.ABOUT_MAX, kept.aboutMe.length)
        assertEquals(listOf(SummaryFormat.AUTO, SummaryFormat.ONE_ON_ONE, SummaryFormat.LECTURE, SummaryFormat.INTERVIEW), kept.formats)
        assertEquals(SummaryFormat.AUTO, kept.effectiveFormat)
        f.h.server.reply(stream("Notes"), headers = SSE)
        assertNull((f.summaries.summarize(ID, SummaryFormat.CUSTOM) as SummaryState.Ready).summary.format, "falls back to General")

        f.summaries.setPreferences(SummaryPreferences(SummaryFormat.CUSTOM, customFormat = "Risks, then next steps."))
        f.h.server.reply(stream("Notes"), headers = SSE)
        assertEquals("custom", (f.summaries.summarize(ID) as SummaryState.Ready).summary.format)
        assertTrue("Follow the user's own instructions for what to write and how to lay it out:\nRisks, then next steps.\n" in f.request().first)
    }

    @Test
    fun `preferences are kept on this device and read back`() = runBlocking {
        val f = Fixture()
        assertEquals(SummaryPreferences(), f.summaries.preferences())
        f.summaries.setPreferences(SummaryPreferences(SummaryFormat.ONE_ON_ONE, "Mine", "Me"))
        val again = Summaries(f.h.db, f.h.deps, f.h.account, f.recordings, { TRANSCRIPT }, TransferConsents(f.h.db, f.h.deps))
        assertEquals(SummaryPreferences(SummaryFormat.ONE_ON_ONE, "Mine", "Me"), again.preferences())
        assertEquals(SummaryPreferences(SummaryFormat.ONE_ON_ONE, "Mine", "Me"), again.observePreferences().first())
    }

    @Test
    fun `the person who made the recording is Me to the model`() = runBlocking {
        val f = Fixture(transcript = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", me = true))))
        f.ready()
        f.h.server.reply(stream("Notes"), headers = SSE)
        f.summaries.summarize(ID)
        val (instructions, input) = f.request()
        assertTrue("\"Me\" in the transcript is the person who made the recording" in instructions)
        assertTrue("[00:00:00] Me: We ship on Friday." in input)
    }

    @Test
    fun `an answer is asked, shown, never written, and closed`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(stream("Subject: Friday"), headers = SSE)

        val state = f.summaries.ask(ID, AskPreset.FOLLOW_UP_EMAIL, null)

        val answer = (state as AskState.Ready).answer
        assertEquals("Subject: Friday", answer.text)
        assertEquals(AskPreset.FOLLOW_UP_EMAIL, answer.preset)
        assertEquals(state, f.summaries.observeAsk(ID).first())
        val (instructions, input) = f.request()
        assertTrue("You answer a question about a recording's transcript." in instructions)
        assertTrue(input.endsWith("${Summaries.QUESTION_HEADING}\n${Summaries.presetQuestion(AskPreset.FOLLOW_UP_EMAIL)}\n"))
        assertFalse(f.h.fs.exists(f.dir / "summary.v1.json"), "an answer is not a summary")
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
    fun `asking needs the same permission as summarizing, before anything is sent`() = runBlocking {
        val f = Fixture(requireTransferConsent = true)
        f.ready()
        val sent = f.h.server.requests.size
        val state = f.summaries.ask(ID, AskPreset.OPEN_QUESTIONS, null)
        assertTrue((state as AskState.Failed).reason.startsWith(CoreMessage.TRANSFER_CONSENT_REQUIRED.name))
        assertEquals(sent, f.h.server.requests.size)
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
            speakers = listOf(TranscriptSpeaker("S1")),
            segments = listOf(TranscriptSegment(0.0, 4.0, "S1", "We ship on Friday.")),
        )

        fun stream(vararg deltas: String): String = buildString {
            deltas.forEach {
                append("data: {\"type\":\"response.output_text.delta\",\"delta\":")
                append(kotlinx.serialization.json.Json.encodeToString(String.serializer(), it))
                append("}\n\n")
            }
            append("data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        }
    }
}
