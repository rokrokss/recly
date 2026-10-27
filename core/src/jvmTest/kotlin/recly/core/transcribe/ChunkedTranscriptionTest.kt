package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.message.CoreMessage
import recly.core.testing.STEP_RUN_ID

/**
 * docs/08 "Length · size limits", end to end: twelve 900-second parts are three hours, over a two-hour
 * provider, so they go as parts 1–6 and 6–12 and come back as one transcript.
 */
class ChunkedTranscriptionTest {
    private val transcriptText =
        "[00:00:10] S1: opening\n[01:20:25] S2: we start the review now.\n[01:20:35] S1: sounds good\n[02:39:10] S2: closing remarks\n"

    @Test
    fun `a recording over a synchronous provider's limit goes as two pieces and comes back as one transcript`() = runBlocking {
        lateinit var provider: PiecesProvider
        val h = TranscribeHarness(partCount = 12, providers = { provider })
        provider = PiecesProvider(h.fs, polling = false)

        val outcome = h.run(h.transcribeStep(provider = PiecesProvider.NAME))

        assertIs<StepOutcome.Done>(outcome, "a synchronous provider finishes on the pass that submitted")
        assertEquals(listOf(audio(1..6), audio(6..12)), provider.audio)
        assertEquals(listOf<Double?>(5400.0, 6300.0), provider.durations, "each piece is told its own length")
        assertEquals(2, h.audio.calls.size)
        assertEquals(transcriptText, h.localContent(TranscribeRunner.textFileName(h.base)))
        val transcript = Json.parseToJsonElement(h.localContent(TranscribeRunner.jsonFileName(h.base))).jsonObject
        assertEquals(2, transcript["speakers"]!!.jsonArray.size)
        assertEquals("piece-model", transcript["provider"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertFalse("jobRef" in transcript["provider"]!!.jsonObject, "no single job made it")
        assertFalse(h.fs.list(h.dir).any { it.name.endsWith(".concat.m4a") || it.name.startsWith(".chunk-") })
        assertEquals(
            listOf(1 to 2, 2 to 2),
            h.logger.fieldsOf("transcribe.submit").map { it["chunk"] to it["chunks"] },
        )
        assertEquals(2, h.logger.fieldsOf("transcribe.done").single()["chunks"])
    }

    @Test
    fun `a recording within the limits goes as one file with no plan in its state`() = runBlocking {
        lateinit var provider: PiecesProvider
        val h = TranscribeHarness(partCount = 8, providers = { provider })
        provider = PiecesProvider(h.fs, polling = false)

        h.run(h.transcribeStep(provider = PiecesProvider.NAME))

        assertEquals(listOf(audio(1..8)), provider.audio)
        assertFalse("chunks" in h.state!!)
        assertFalse("chunk" in h.logger.fieldsOf("transcribe.submit").single())
        assertFalse("chunks" in h.logger.fieldsOf("transcribe.done").single())
    }

    @Test
    fun `a process that dies while the second piece is polled goes on polling it without paying for the first again`() = runBlocking {
        lateinit var provider: PiecesProvider
        val h = TranscribeHarness(partCount = 12, providers = { provider })
        provider = PiecesProvider(h.fs, polling = true)
        val step = h.transcribeStep(provider = PiecesProvider.NAME)
        val pieceFile = h.dir / ".chunk-$STEP_RUN_ID-1.json"

        assertIs<StepOutcome.Waiting>(h.run(step)) // piece 1 submitted
        assertIs<StepOutcome.Waiting>(h.run(step)) // still running
        assertIs<StepOutcome.Waiting>(h.run(step)) // piece 1 done; piece 2 submitted on the same pass

        val state = h.state!!
        assertEquals(listOf((1..6).toList(), (6..12).toList()), state["chunks"]!!.jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.int } })
        assertEquals(1, state["chunk"]!!.jsonPrimitive.int)
        assertEquals("piece-6-2", state["ref"]!!.jsonPrimitive.content)
        assertTrue(h.fs.exists(pieceFile), "the finished piece is on disk, not in the row")

        // A new process: nothing survives but the step's state and the recording's directory.
        val before = provider
        provider = PiecesProvider(h.fs, polling = true)
        val output = h.runToDone(step)

        assertEquals(emptyList(), provider.audio, "nothing is submitted again")
        assertEquals(listOf("piece-6-2", "piece-6-2"), provider.polled)
        assertEquals(listOf(audio(1..6), audio(6..12)), before.audio)
        assertEquals(transcriptText, h.localContent(TranscribeRunner.textFileName(h.base)))
        assertEquals(2, output.json["transcript"]!!.jsonObject["speakerCount"]!!.jsonPrimitive.int)
        assertFalse(h.fs.exists(pieceFile), "the piece files go once the transcript is written")
    }

    @Test
    fun `a failed second piece drops only its own ref and the retry submits only it`() = runBlocking {
        lateinit var provider: PiecesProvider
        val h = TranscribeHarness(partCount = 12, providers = { provider })
        provider = PiecesProvider(h.fs, polling = true)
        val step = h.transcribeStep(provider = PiecesProvider.NAME)
        repeat(3) { h.run(step) }
        provider.failing += "piece-6-2"

        val failure = assertFailsWith<StepFailure> { h.run(step) }

        assertContains(failure.reason, CoreMessage.PROVIDER_ERROR.name)
        assertTrue(failure.retryable)
        val state = h.state!!
        assertEquals(null, state["ref"], "the dead submission is gone")
        assertEquals(1, state["chunk"]!!.jsonPrimitive.int, "the finished piece is kept")
        assertTrue("chunks" in state)

        h.runToDone(step)

        assertEquals(listOf(audio(1..6), audio(6..12), audio(6..12)), provider.audio)
        assertEquals(transcriptText, h.localContent(TranscribeRunner.textFileName(h.base)))
    }

    @Test
    fun `a synchronous second piece that fails is the only one sent again`() = runBlocking {
        lateinit var provider: PiecesProvider
        val h = TranscribeHarness(partCount = 12, providers = { provider })
        provider = PiecesProvider(h.fs, polling = false)
        provider.throwOnce += 6
        val step = h.transcribeStep(provider = PiecesProvider.NAME)

        assertFailsWith<StepFailure> { h.run(step) }
        assertEquals(1, h.state!!["chunk"]!!.jsonPrimitive.int)
        assertIs<StepOutcome.Done>(h.run(step))

        assertEquals(listOf(audio(1..6), audio(6..12), audio(6..12)), provider.audio)
        assertEquals(transcriptText, h.localContent(TranscribeRunner.textFileName(h.base)))
    }

    /** What [FakeAudioTools] makes of the parts: their contents end to end. */
    private fun audio(parts: IntRange): String = parts.joinToString("") { "audio-$it-MONO" }
}

/**
 * A provider with clova's two-hour ceiling that answers each piece by its first part — synchronously, or
 * through a ref that is pending for one poll. The ref names the piece, so a fresh instance (a new process)
 * can answer a ref it did not hand out, as a real provider would.
 */
private class PiecesProvider(private val fs: FileSystem, private val polling: Boolean) : SttProvider {
    override val name: String = NAME
    override val limits: SttLimits = SttLimits(maxDurationSec = 2 * 3600.0)

    /** The audio of every submission, in order. */
    val audio = mutableListOf<String>()
    val durations = mutableListOf<Double?>()
    val polled = mutableListOf<String>()

    /** Refs the provider reports failed on their next poll. */
    val failing = mutableSetOf<String>()

    /** First parts whose next submission throws a retryable provider error. */
    val throwOnce = mutableSetOf<Int>()

    override suspend fun submit(ctx: SttContext, file: Path): Submitted {
        val content = fs.read(file) { readUtf8() }
        audio += content
        durations += ctx.audioDurationSec
        val first = Regex("audio-(\\d+)-").find(content)!!.groupValues[1].toInt()
        if (throwOnce.remove(first)) throw StepFailure(retryable = true, reason = CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 503"))
        return if (polling) Submitted.Polling("piece-$first-${audio.size}") else Submitted.Finished(answer(first))
    }

    override suspend fun poll(ctx: SttContext, ref: String): PollResult {
        polled += ref
        if (failing.remove(ref)) return PollResult.Failed("boom")
        if (polled.count { it == ref } == 1) return PollResult.Pending
        return PollResult.Done(answer(ref.split('-')[1].toInt()))
    }

    /**
     * Each piece on its own axis. Part 6, 4500..5400 on the joined axis, is heard by both: the same five
     * words 0.1 s apart with the widest pause before "now", and the two speakers' labels the other way round.
     */
    private fun answer(first: Int): SttResult = when (first) {
        1 -> SttResult(
            segments = listOf(
                SttSegment(10.0, 14.0, "A", "opening"),
                SttSegment(
                    4800.0, 4804.0, "B", "we start the review now",
                    listOf(
                        SttWord(4800.0, 4800.3, "we"), SttWord(4800.4, 4800.8, "start"), SttWord(4800.9, 4801.0, "the"),
                        SttWord(4801.1, 4801.6, "review"), SttWord(4803.6, 4804.0, "now"),
                    ),
                ),
                SttSegment(4810.0, 4816.0, "A", "sounds good"),
            ),
            language = "en",
            durationSec = 5400.0,
            model = "piece-model",
        )

        6 -> SttResult(
            segments = listOf(
                SttSegment(
                    300.1, 304.1, "A", "We start the review now.",
                    listOf(
                        SttWord(300.1, 300.4, "We"), SttWord(300.5, 300.9, "start"), SttWord(301.0, 301.1, "the"),
                        SttWord(301.2, 301.7, "review"), SttWord(303.7, 304.1, "now."),
                    ),
                ),
                SttSegment(310.1, 316.1, "B", "sounds good"),
                SttSegment(5000.0, 5004.0, "A", "closing remarks"),
            ),
            language = "en",
            durationSec = 6300.0,
            model = "other-model",
        )

        else -> error("no piece starts at part $first")
    }

    companion object {
        const val NAME = "pieces"
    }
}
