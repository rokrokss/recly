package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Part
import recly.core.model.Track
import recly.core.testing.FakeAudioTools
import recly.core.testing.FakeLogger
import recly.core.testing.testDeps
import recly.core.testing.testMeta

/** docs/08 "Me and others": a desktop's mic and sys tracks tell the person who made the recording from the call. */
class MeAndOthersTest {
    private val w = MeAndOthers.WINDOW_SEC

    /** Levels for [sec] seconds: [loud] where [on] says so, a quiet floor elsewhere. */
    private fun levels(sec: Double, on: (Double) -> Boolean): List<Float> =
        List((sec / w).toInt()) { i -> if (on(i * w)) 0.5f else 0.001f }

    private fun transcript(vararg segments: TranscriptSegment, speakers: List<TranscriptSpeaker> = emptyList(), identification: String? = "unavailable") = Transcript(
        schema = Transcript.LOCAL_SCHEMA, recordingId = "01J9ABCDEF0123456789ABCDEF", track = Track.MIX, language = "en",
        provider = TranscriptProvider("apple"), createdAt = "2026-08-26T01:20:00Z", durationSec = 20.0,
        speakers = speakers, segments = segments.toList(), speakerIdentification = identification, timing = "segment",
    )

    // The user talks 0–5 s and 10–15 s; the call talks 5–10 s and 15–20 s.
    private val mic = levels(20.0) { it < 5 || it in 10.0..14.9 }
    private val sys = levels(20.0) { it in 5.0..9.9 || it >= 15 }

    @Test
    fun `without speakers the user's lines and the call's become two speakers`() {
        val marked = MeAndOthers.assign(
            transcript(
                TranscriptSegment(5.0, 10.0, "", "They answer."),
                TranscriptSegment(0.5, 4.5, "", "I ask."),
                TranscriptSegment(10.5, 14.5, "", "I ask again."),
            ),
            mic, sys, w,
        )
        assertEquals(listOf(TranscriptSpeaker("S1"), TranscriptSpeaker("S2", me = true)), marked.speakers)
        assertEquals(listOf("S1", "S2", "S2"), marked.segments.map { it.speaker })
        assertEquals("identified", marked.speakerIdentification)
        assertTrue("[00:00:00] Me: I ask." in TranscriptNormalizer.text(marked.copy(segments = marked.segments.sortedBy { it.start })))
    }

    @Test
    fun `identified speakers are kept, and the one who is mostly the user is marked`() {
        val marked = MeAndOthers.assign(
            transcript(
                TranscriptSegment(0.0, 5.0, "S1", "I ask."),
                TranscriptSegment(5.0, 10.0, "S2", "They answer."),
                TranscriptSegment(10.0, 15.0, "S1", "I ask again."),
                TranscriptSegment(15.0, 20.0, "S2", "They answer again."),
                speakers = listOf(TranscriptSpeaker("S1", "Minsu"), TranscriptSpeaker("S2")),
                identification = "identified",
            ),
            mic, sys, w,
        )
        assertEquals(listOf(TranscriptSpeaker("S1", "Minsu", me = true), TranscriptSpeaker("S2")), marked.speakers)
        assertEquals(listOf("S1", "S2", "S1", "S2"), marked.segments.map { it.speaker }, "nobody is split")
    }

    @Test
    fun `a call that never sounds, or a speaker who is only partly the user, changes nothing`() {
        val room = transcript(TranscriptSegment(0.0, 5.0, "", "Everyone in one room."))
        assertSame(room, MeAndOthers.assign(room, mic, levels(20.0) { false }, w))
        val mixed = transcript(
            TranscriptSegment(0.0, 10.0, "S1", "Both of us."),
            TranscriptSegment(10.0, 20.0, "S2", "Both of us again."),
            speakers = listOf(TranscriptSpeaker("S1"), TranscriptSpeaker("S2")),
            identification = "identified",
        )
        assertSame(mixed, MeAndOthers.assign(mixed, mic, sys, w))
    }

    @Test
    fun `the levels come from the recording's mic and sys parts, and missing parts leave it as it was`() = runBlocking {
        val fs = FakeFileSystem()
        val audio = FakeAudioTools(fs)
        val logger = FakeLogger()
        val deps = testDeps(fileSystem = fs, audio = audio, logger = logger)
        val dir = "/rec".toPath()
        val meta = testMeta(
            parts = listOf(
                Part(part = 1, track = Track.MIC, file = "p001_mic.m4a", startOffsetSec = 0.0, durationSec = 20.0, bytes = 1, sha256 = "a"),
                Part(part = 1, track = Track.SYS, file = "p001_sys.m4a", startOffsetSec = 0.0, durationSec = 20.0, bytes = 1, sha256 = "b"),
                Part(part = 1, track = Track.MIX, file = "p001_mix.m4a", startOffsetSec = 0.0, durationSec = 20.0, bytes = 1, sha256 = "c"),
            ),
        ).copy(tracks = listOf(Track.MIC, Track.SYS, Track.MIX))
        val plain = transcript(TranscriptSegment(0.5, 4.5, "", "I ask."), TranscriptSegment(5.0, 10.0, "", "They answer."))

        assertSame(plain, MeAndOthers.mark(deps, dir, meta, plain), "no parts on this device")
        assertTrue("transcribe.me.skipped" in logger.events)

        fs.createDirectories(dir)
        listOf("p001_mic.m4a", "p001_sys.m4a").forEach { fs.write(dir / it) { writeUtf8("aac") } }
        audio.levelsByName["p001_mic.m4a"] = mic
        audio.levelsByName["p001_sys.m4a"] = sys
        val marked = MeAndOthers.mark(deps, dir, meta, plain)
        assertEquals(true, marked.speakers.single { it.id == marked.segments.first().speaker }.me)
        assertNull(marked.speakers.single { it.id == marked.segments.last().speaker }.me)
        assertTrue("transcribe.me" in logger.events)

        val phone = testMeta()
        assertSame(plain, MeAndOthers.mark(deps, dir, phone, plain), "one track: nothing to tell apart")
    }
}
