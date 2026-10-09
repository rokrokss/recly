package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

class SummariesTest {
    private fun fixture(requireTransferConsent: Boolean = false, transcript: Transcript? = TRANSCRIPT) = Fixture(requireTransferConsent, transcript)

    private class Fixture(requireTransferConsent: Boolean, transcript: Transcript?) {
        val h = ChatGptHarness(requireTransferConsent)
        val recordings = RecordingRepository(h.db, h.deps)
        val consents = TransferConsents(h.db, h.deps)
        val summaries = Summaries(h.deps, h.account, recordings, { transcript }, consents)
        val meta = testMeta(title = "Weekly sync")
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()

        suspend fun ready() {
            recordings.create(meta, dir)
            h.signIn()
        }
    }

    @Test
    fun `a summary goes to the plan as text only and is kept beside the recording`() = runBlocking {
        val f = fixture()
        f.ready()
        f.h.server.reply(stream("Summary\n", "- Ship on Friday"), headers = SSE)
        val state = f.summaries.summarize(ID)
        assertEquals("Summary\n- Ship on Friday", (state as SummaryState.Ready).summary.text)
        assertEquals("gpt-a", state.summary.model)

        val request = f.h.server.requests.last()
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        val body = request.json()
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(setOf("model", "instructions", "input", "store", "stream"), body.keys, "nothing the plan's API refuses")
        val text = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Title: Weekly sync" in text)
        assertTrue("Mina: We ship on Friday." in text)

        assertTrue(f.h.fs.exists(f.dir / "summary.v1.json"))
        assertEquals(state, f.summaries.state(ID))
        assertEquals(state, f.summaries.observe(ID).first())
        assertTrue("summary.done" in f.h.logger.events)
    }

    @Test
    fun `a failed summary keeps the one before it`() = runBlocking {
        val f = fixture()
        f.ready()
        f.h.server.reply(stream("First"), headers = SSE)
        val first = (f.summaries.summarize(ID) as SummaryState.Ready).summary
        f.h.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Half\"}\n\n" +
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n",
            headers = SSE,
        )
        assertEquals(SummaryState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), first), f.summaries.summarize(ID))
        assertEquals(SummaryState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), first), f.summaries.observe(ID).first())
    }

    @Test
    fun `on iPhone nothing is sent before the destination is allowed`() = runBlocking {
        val f = fixture(requireTransferConsent = true)
        f.ready()
        val sent = f.h.server.requests.size
        assertEquals(SummaryState.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code(), null), f.summaries.summarize(ID))
        assertEquals(sent, f.h.server.requests.size)
        assertEquals(SummaryState.None, f.summaries.state(ID), "a missing permission is the dialog's to show, not a failed summary")

        f.consents.grant(listOf(TransferTargets.chatGptSummary()))
        f.h.server.reply(stream("Done"), headers = SSE)
        assertTrue(f.summaries.summarize(ID) is SummaryState.Ready)
    }

    @Test
    fun `a recording with no transcript is not sent`() = runBlocking {
        val f = fixture(transcript = null)
        f.ready()
        val sent = f.h.server.requests.size
        assertEquals(SummaryState.Failed(CoreMessage.STEP_FAILED.code("no transcript"), null), f.summaries.summarize(ID))
        assertEquals(sent, f.h.server.requests.size)
    }

    @Test
    fun `without a sign-in the summary asks for one`() = runBlocking {
        val f = fixture()
        f.recordings.create(f.meta, f.dir)
        assertEquals(SummaryState.Failed(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code(), null), f.summaries.summarize(ID))
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
    fun `the summarize destination is a valid grant`() {
        val target = TransferTargets.chatGptSummary()
        assertEquals("summarize", target.kind)
        assertEquals("https://api.openai.com/v1", target.endpoint)
        assertTrue(TransferTargets.valid(target))
        assertFalse(TransferTargets.valid(target.copy(endpoint = "https://example.com/v1")))
    }

    private companion object {
        const val ID = "01J9ABCDEF0123456789ABCDEF"
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
