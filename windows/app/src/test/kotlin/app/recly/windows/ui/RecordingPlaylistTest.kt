package app.recly.windows.ui

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import recly.core.model.Part
import recly.core.model.Track

/**
 * docs/08 "결과 파일": what the detail plays back, chosen out of `meta.json` alone — the track a
 * person means, its parts in order, and only the files this PC still has. The same rules RecKit's
 * `RecordingPlaylistTests` pins, so the two shells play the same thing.
 */
class RecordingPlaylistTest {

    private val dir: Path = "/recordings/01J9REC".toPath()

    /** A meeting: `mic` and `sys` are the mix's own ingredients, and either alone is half of it. */
    @Test
    fun `the mix is played when the recording has one`() {
        val selection = RecordingPlaylist.select(
            parts = listOf(
                part(1, Track.MIC, "p001_mic.m4a", 10.0),
                part(1, Track.SYS, "p001_sys.m4a", 10.0),
                part(1, Track.MIX, "p001_mix.m4a", 10.0),
            ),
            dir = dir,
        ) { true }

        assertEquals(listOf(dir / "p001_mix.m4a"), selection.paths)
        assertEquals(10.0, selection.totalSec)
    }

    /** A memo has one track and it is the whole recording. */
    @Test
    fun `mono is played when there is no mix`() {
        val selection = RecordingPlaylist.select(
            parts = listOf(part(1, Track.MONO, "p001_mono.m4a", 42.0)),
            dir = dir,
        ) { true }

        assertEquals(listOf(dir / "p001_mono.m4a"), selection.paths)
        assertEquals(42.0, selection.totalSec)
    }

    /**
     * The parts are one recording end to end, so they are played in `part` order however
     * `meta.json` happens to list them.
     */
    @Test
    fun `the parts are played in part order`() {
        val selection = RecordingPlaylist.select(
            parts = listOf(
                part(3, Track.MONO, "p003_mono.m4a", 5.0),
                part(1, Track.MONO, "p001_mono.m4a", 300.0),
                part(2, Track.MONO, "p002_mono.m4a", 300.0),
            ),
            dir = dir,
        ) { true }

        assertEquals(
            listOf("p001_mono.m4a", "p002_mono.m4a", "p003_mono.m4a"),
            selection.paths.map { it.name },
        )
        assertEquals(listOf(300.0, 300.0, 5.0), selection.durations)
        assertEquals(605.0, selection.totalSec)
    }

    /**
     * docs/03 ADR-017: the sweep may have taken a part already. What is gone is dropped rather than
     * played as silence — and its seconds go with it, so the clock counts what is there.
     */
    @Test
    fun `a part whose file is gone is dropped`() {
        val selection = RecordingPlaylist.select(
            parts = listOf(
                part(1, Track.MONO, "p001_mono.m4a", 300.0),
                part(2, Track.MONO, "p002_mono.m4a", 120.0),
            ),
            dir = dir,
        ) { it.name == "p002_mono.m4a" }

        assertEquals(listOf("p002_mono.m4a"), selection.paths.map { it.name })
        assertEquals(120.0, selection.totalSec)
    }

    /** Every part gone is a recording with nothing to play, which the detail says in words. */
    @Test
    fun `nothing left on this PC is an empty selection`() {
        val selection = RecordingPlaylist.select(
            parts = listOf(part(1, Track.MONO, "p001_mono.m4a", 300.0)),
            dir = dir,
        ) { false }

        assertTrue(selection.isEmpty)
        assertEquals(0.0, selection.totalSec)
        assertEquals(RecordingPlaylist.Selection.EMPTY, selection)
    }

    // --- Falling back to Drive (docs/03 ADR-017) --------------------------------------------------

    /** Every part is here, so a trip to Drive would be a round trip for a file already on disk. */
    @Test
    fun `a whole playlist is not fetched`() {
        assertFalse(RecordingPlaylist.fetchesFromDrive(local = 3, track = 3, uploaded = true))
    }

    /** The sweep took a part and Drive has it: the whole case this fallback exists for. */
    @Test
    fun `a gap in an uploaded recording is fetched`() {
        assertTrue(RecordingPlaylist.fetchesFromDrive(local = 1, track = 3, uploaded = true))
        assertTrue(
            RecordingPlaylist.fetchesFromDrive(local = 0, track = 1, uploaded = true),
            "nothing local at all is still a gap Drive can fill",
        )
    }

    /** A recording Drive never got has nothing there to ask for — the files are simply gone. */
    @Test
    fun `a gap in a recording Drive never got is not fetched`() {
        assertFalse(RecordingPlaylist.fetchesFromDrive(local = 1, track = 3, uploaded = false))
        assertFalse(RecordingPlaylist.fetchesFromDrive(local = 0, track = 2, uploaded = false))
    }

    /** What comes back is the same parts under the same names, on `meta.json`'s own seconds. */
    @Test
    fun `a fetched playlist keeps the durations meta recorded`() {
        val selection = RecordingPlaylist.fetched(
            parts = listOf(
                part(1, Track.MONO, "p001_mono.m4a", 300.0),
                part(2, Track.MONO, "p002_mono.m4a", 120.0),
                part(3, Track.MONO, "p003_mono.m4a", 5.0),
            ),
            files = listOf("p001_mono.m4a", "p002_mono.m4a", "p003_mono.m4a"),
            dir = dir,
        )

        assertEquals(
            listOf("p001_mono.m4a", "p002_mono.m4a", "p003_mono.m4a"),
            selection.paths.map { it.name },
        )
        assertEquals(listOf(300.0, 120.0, 5.0), selection.durations)
        assertEquals(425.0, selection.totalSec)
    }

    /**
     * A part that stayed missing ends the playlist there rather than being skipped over: what plays
     * is the start of the recording, on the clock the transcript below is indexed against. Part 3
     * after part 1 would put 5:00 of the recording at 0:00 of everything after the gap.
     */
    @Test
    fun `a fetch that missed a part stops at the gap`() {
        val selection = RecordingPlaylist.fetched(
            parts = listOf(
                part(1, Track.MONO, "p001_mono.m4a", 300.0),
                part(2, Track.MONO, "p002_mono.m4a", 120.0),
                part(3, Track.MONO, "p003_mono.m4a", 5.0),
            ),
            files = listOf("p001_mono.m4a", "p003_mono.m4a"),
            dir = dir,
        )

        assertEquals(listOf("p001_mono.m4a"), selection.paths.map { it.name })
        assertEquals(300.0, selection.totalSec, "the clock counts what is actually playable")
    }

    /** A gap at the very front is a recording with nothing playable in it at all. */
    @Test
    fun `a fetch that missed the first part plays nothing`() {
        val selection = RecordingPlaylist.fetched(
            parts = listOf(
                part(1, Track.MONO, "p001_mono.m4a", 300.0),
                part(2, Track.MONO, "p002_mono.m4a", 120.0),
            ),
            files = listOf("p002_mono.m4a"),
            dir = dir,
        )

        assertEquals(RecordingPlaylist.Selection.EMPTY, selection)
    }

    /** The parts of `meta.json` come in whatever order it listed them; the playlist is ordered. */
    @Test
    fun `a fetched playlist is in part order`() {
        val selection = RecordingPlaylist.fetched(
            parts = listOf(
                part(2, Track.MONO, "p002_mono.m4a", 120.0),
                part(1, Track.MONO, "p001_mono.m4a", 300.0),
            ),
            files = listOf("p001_mono.m4a", "p002_mono.m4a"),
            dir = dir,
        )

        assertEquals(listOf("p001_mono.m4a", "p002_mono.m4a"), selection.paths.map { it.name })
        assertEquals(listOf(300.0, 120.0), selection.durations)
    }

    /** Only the played track is fetched back: the mix's ingredients are not what is playing. */
    @Test
    fun `a fetched playlist is the played track alone`() {
        val parts = listOf(
            part(1, Track.MIC, "p001_mic.m4a", 300.0),
            part(1, Track.MIX, "p001_mix.m4a", 300.0),
        )

        assertEquals(
            listOf("p001_mix.m4a"),
            RecordingPlaylist.fetched(parts, listOf("p001_mic.m4a", "p001_mix.m4a"), dir)
                .paths.map { it.name },
        )
    }

    // --- The clock -------------------------------------------------------------------------------

    /**
     * The position is the recording's, not the part's: two finished parts and eleven seconds into
     * the third is 10:11, not 0:11. Those eleven seconds are counted in the PCM the decoder has
     * handed the speaker — 16-bit mono at 16 kHz, so 32000 bytes a second.
     */
    @Test
    fun `the clock counts the finished parts as well as the PCM of the current one`() {
        assertEquals(
            611.0,
            RecordingPlayer.position(
                listOf(300.0, 300.0, 120.0),
                finished = 2,
                offsetSec = 0.0,
                itemBytes = 11 * 32_000L,
            ),
        )
    }

    /** Before anything has finished there is only the current part, and it starts at nothing. */
    @Test
    fun `the clock starts at the current part`() {
        assertEquals(
            7.0,
            RecordingPlayer.position(listOf(300.0), finished = 0, offsetSec = 0.0, itemBytes = 7 * 32_000L),
        )
        assertEquals(0.0, RecordingPlayer.position(listOf(300.0), finished = 0, offsetSec = 0.0, itemBytes = 0))
    }

    /** Half a second of PCM is half a second on the clock: it is not rounded to a whole part. */
    @Test
    fun `the clock counts part of a second`() {
        assertEquals(
            0.5,
            RecordingPlayer.position(listOf(300.0), finished = 0, offsetSec = 0.0, itemBytes = 16_000L),
        )
    }

    /** The end of the last part is the whole recording, with nothing of a next one on the clock. */
    @Test
    fun `the clock at the end of a part is the parts behind it`() {
        assertEquals(
            600.0,
            RecordingPlayer.position(listOf(300.0, 300.0), finished = 2, offsetSec = 0.0, itemBytes = 0),
        )
    }

    /**
     * A part started in the middle of, because a scrub landed there: what the decoder has written
     * is the seconds after the offset, so the clock is the offset plus them — not the part's start
     * plus them, which would put the playhead back where the drag began.
     */
    @Test
    fun `the clock counts where the current part was started at`() {
        assertEquals(
            611.0,
            RecordingPlayer.position(
                listOf(300.0, 300.0, 120.0),
                finished = 2,
                offsetSec = 8.0,
                itemBytes = 3 * 32_000L,
            ),
        )
    }

    // --- Scrubbing (docs/09 화면 원칙 2) ------------------------------------------------------------

    /**
     * The inverse of the clock: a drag on the waveform gives a second of the *recording*, and what
     * the player needs is a part and an offset into it.
     */
    @Test
    fun `a second inside the first part is that part`() {
        assertEquals(0 to 11.0, RecordingPlayer.target(listOf(300.0, 300.0, 120.0), sec = 11.0))
    }

    /** And one after two whole parts is 11 seconds into the third, not 611 into anything. */
    @Test
    fun `a second past two parts is the third part`() {
        assertEquals(2 to 11.0, RecordingPlayer.target(listOf(300.0, 300.0, 120.0), sec = 611.0))
    }

    /**
     * A boundary belongs to the part that is starting, not to the one that just ended: dropped on
     * the far end of a part, the playhead plays on rather than stopping where it landed.
     */
    @Test
    fun `a parts last instant is the next parts first`() {
        assertEquals(1 to 0.0, RecordingPlayer.target(listOf(300.0, 120.0), sec = 300.0))
    }

    /**
     * Dragged off the end of the row — which the seek's own clamp already stops at `totalSec` — the
     * target is the end of the last part rather than an index there is no part for.
     */
    @Test
    fun `past the end is the end of the last part`() {
        assertEquals(1 to 120.0, RecordingPlayer.target(listOf(300.0, 120.0), sec = 900.0))
    }

    /**
     * A recording with nothing playable in it has no part to scrub to, and asking for one is the
     * start of nothing rather than a read off the end of an empty list.
     */
    @Test
    fun `an empty recording has no part to seek into`() {
        assertEquals(0 to 0.0, RecordingPlayer.target(emptyList(), sec = 42.0))
    }

    // --- what Play may start ---------------------------------------------------------------------

    @Test
    fun `Play starts when the microphone is free, the fetch is decided and audio is here`() {
        assertTrue(
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = true, hasAudio = true, blocked = false),
        )
    }

    /** ADR-006: the capture takes the system audio with it, so what is playing would be in it. */
    @Test
    fun `Play starts nothing while this PC is recording`() {
        assertFalse(
            RecordingPlaylist.canPlay(recorderIdle = false, fetchDecided = true, hasAudio = true, blocked = false),
        )
    }

    @Test
    fun `Play starts nothing while the trip to Drive is still being decided`() {
        assertFalse(
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = false, hasAudio = true, blocked = false),
        )
    }

    @Test
    fun `Play starts nothing when no part of the recording is on this PC`() {
        assertFalse(
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = true, hasAudio = false, blocked = false),
        )
    }

    /**
     * The gate the shell holds ([PlaybackGate]): a capture that is opening — the recorder says
     * nothing until its Start has gone out, so `recorderIdle` is still true — or a delete removing
     * the very file a press would hand to ffmpeg.
     */
    @Test
    fun `Play starts nothing while the shell has the gate up`() {
        assertFalse(
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = true, hasAudio = true, blocked = true),
        )
    }

    // --- the gate --------------------------------------------------------------------------------

    @Test
    fun `nothing raised is nothing in the way`() {
        assertFalse(PlaybackGate().blocked)
    }

    /** Two reasons, and the gate is down only once both are: a delete inside a capture is both. */
    @Test
    fun `the gate stays up while any reason is`() {
        val gate = PlaybackGate()

        gate.raise(PlaybackGate.Reason.CAPTURE)
        gate.raise(PlaybackGate.Reason.CLEANUP)
        gate.lower(PlaybackGate.Reason.CLEANUP)

        assertTrue(gate.blocked, "the capture's own gate came down with the clean-up's")

        gate.lower(PlaybackGate.Reason.CAPTURE)

        assertFalse(gate.blocked)
    }

    /**
     * Reasons and not a count: the recorder raises CAPTURE again on `onState(true)` over the one
     * [ShellModel.begin] already raised, and the capture ending has to be the end of it.
     */
    @Test
    fun `raising the same reason twice is raising it once`() {
        val gate = PlaybackGate()

        gate.raise(PlaybackGate.Reason.CAPTURE)
        gate.raise(PlaybackGate.Reason.CAPTURE)
        gate.lower(PlaybackGate.Reason.CAPTURE)

        assertFalse(gate.blocked, "a second raise left a gate nothing will lower")
    }

    /** The order a delete keeps: the gate up over the whole of it, and the stop before the step. */
    @Test
    fun `a clean-up runs with the gate up and the speaker already off`() = runTest {
        val gate = PlaybackGate()
        val order = mutableListOf<String>()

        val result = gate.cleaning(
            stop = { order += "stop"; true },
            run = {
                order += "delete"
                assertTrue(gate.blocked, "the delete ran with Play still on the bar")
                "deleted"
            },
        )

        assertEquals("deleted", result)
        assertEquals(listOf("stop", "delete"), order)
        assertFalse(gate.blocked, "the gate was left up after the clean-up")
    }

    /**
     * docs/03: `RecordingRepository.delete` commits the rows before it walks the directory, so a
     * delete made over an ffmpeg that would not let go takes the rows and leaves the audio — with
     * nothing left to try again with. A stop that timed out is a step that does not run at all.
     */
    @Test
    fun `a clean-up whose stop timed out deletes nothing`() = runTest {
        val gate = PlaybackGate()
        var deleted = false

        val result = gate.cleaning(stop = { false }, run = { deleted = true; "deleted" })

        assertNull(result, "the caller was told the clean-up ran")
        assertFalse(deleted, "the core was asked to delete over a live decoder")
        assertFalse(gate.blocked, "a refused clean-up left the gate up for ever")
    }

    // --- The player ------------------------------------------------------------------------------

    /**
     * The teardown every way out of the page goes through — the window closed, another recording
     * picked, the composition disposed. Calling it on a player with nothing going has to be nothing
     * rather than a throw, because most of those callers cannot know whether one of the others got
     * there first.
     */
    @Test
    fun `stopping a player that is not playing is harmless`() {
        val player = RecordingPlayer()

        player.stop()
        player.stop()

        assertFalse(player.playing)
        assertEquals(0.0, player.positionSec)
    }

    /**
     * A recording with nothing left on this PC: the bar offers no button for it, and a press that
     * arrived anyway starts no ffmpeg.
     */
    @Test
    fun `an empty selection is nothing to play`() {
        val player = RecordingPlayer()

        player.play(RecordingPlaylist.Selection.EMPTY)

        assertFalse(player.playing)
    }

    /**
     * The contract deleting a recording rests on (docs/03 "앱에서 지우기"): when [RecordingPlayer.stop]
     * returns, the decoder is not merely told to go but gone — no process, no thread, and the line
     * closed. Windows will not remove a file ffmpeg still has open, and the core deletes the rows
     * before the directory, so a stop that only asked would leave the audio behind a row that is
     * already gone.
     *
     * Neither a sound card nor ffmpeg is here, so both are handed in.
     */
    @Test
    fun `stop returns only once the decoder and its thread are gone`() {
        val speaker = FakeSpeaker()
        val spawned = AtomicReference<FakeProcess>()
        val player = RecordingPlayer(
            speaker = { speaker },
            spawn = { _, _ -> FakeProcess().also(spawned::set) },
        )

        player.play(RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(60.0)))
        // The thread is what spawns it, so the press is not the moment it exists.
        val decoder = await("nothing was decoded") { spawned.get() }

        player.stop()

        assertFalse(decoder.isAlive, "ffmpeg is still holding the part open")
        assertTrue(speaker.closed, "the line was left open")
        assertTrue(
            Thread.getAllStackTraces().keys.none { it.name == PLAYER_THREAD && it.isAlive },
            "the decoding thread outlived the stop",
        )
        assertFalse(player.playing)
        assertEquals(0.0, player.positionSec)
    }

    /**
     * The two callers this player actually has: the model, stopping on `Dispatchers.IO` before a
     * delete, and the window's own effect on the UI thread. Whichever gets there second finds the
     * playback already detached, and must still not return until the ffmpeg is gone — the delete
     * behind it cannot tell the two apart, and Windows will not remove a file ffmpeg has open.
     */
    @Test
    fun `two stops at once both return only once the decoder is gone`() {
        val speaker = FakeSpeaker()
        val spawned = AtomicReference<FakeProcess>()
        val player = RecordingPlayer(speaker = { speaker }, spawn = { _, _ -> FakeProcess().also(spawned::set) })
        player.play(RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(60.0)))
        val decoder = await("nothing was decoded") { spawned.get() }

        val results = stopFrom(player, callers = 2)

        assertEquals(listOf(true, true), results, "a stop came back over a decoder that was still up")
        assertFalse(decoder.isAlive, "ffmpeg is still holding the part open")
        assertTrue(speaker.closed, "the line was left open")
        assertTrue(
            Thread.getAllStackTraces().keys.none { it.name == PLAYER_THREAD && it.isAlive },
            "the decoding thread outlived the stops",
        )
    }

    /**
     * The window stops on the gate and the bar is a frame behind it: a press that lands inside the
     * teardown must not install a second decoder beside the one still unwinding — the delete the
     * teardown was making way for would then run over a file this one has open.
     */
    @Test
    fun `a press during a teardown starts no decoder`() {
        val gate = CountDownLatch(1)
        val speaker = FakeSpeaker(closeGate = gate)
        val spawns = AtomicInteger()
        val player = RecordingPlayer(speaker = { speaker }, spawn = { _, _ -> FakeProcess().also { spawns.incrementAndGet() } })
        val selection = RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(60.0))
        player.play(selection)
        await("nothing was decoded") { spawns.get().takeIf { it > 0 } }
        // The stop is left mid-teardown: the thread is inside the line's close and has not ended.
        val stopping = Thread { player.stop() }.apply { start() }
        await("the teardown never got as far as the line") { speaker.closing.takeIf { it } }

        player.play(selection)

        assertEquals(1, spawns.get(), "a press during the teardown spawned a second ffmpeg")
        assertFalse(player.playing, "the bar says it is playing with nothing behind it")
        gate.countDown()
        stopping.join()
    }

    /**
     * docs/03 "앱에서 지우기": a decoder that will not go inside the bound is not a stop, and the
     * caller is told so rather than left to delete the rows out from under a live handle.
     */
    @Test
    fun `a teardown that outlives its bound is not a stop`() {
        val gate = CountDownLatch(1)
        val speaker = FakeSpeaker(closeGate = gate)
        val spawned = AtomicReference<FakeProcess>()
        val player = RecordingPlayer(
            speaker = { speaker },
            spawn = { _, _ -> FakeProcess().also(spawned::set) },
            teardownWaitMs = 50,
        )
        player.play(RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(60.0)))
        await("nothing was decoded") { spawned.get() }

        try {
            assertFalse(player.stop(), "a decoder still on the part was reported as gone")
        } finally {
            // The thread is a daemon, but the next test asks the JVM whether one is alive.
            gate.countDown()
            assertTrue(player.stop(), "the teardown never finished once the line was let go")
        }
    }

    /** [callers] threads into [RecordingPlayer.stop] at once, and what each of them came back with. */
    private fun stopFrom(player: RecordingPlayer, callers: Int): List<Boolean> {
        val together = CountDownLatch(callers)
        val results = AtomicReferenceArray<Boolean>(callers)
        val threads = List(callers) { index ->
            Thread {
                together.countDown()
                together.await()
                results.set(index, player.stop())
            }.apply { start() }
        }
        threads.forEach { it.join() }
        return List(callers) { results.get(it) }
    }

    // --- Scrubbing, on the player itself (docs/09 화면 원칙 2) --------------------------------------

    /**
     * docs/09 화면 원칙 2: a scrub before anything has been played is where the next press starts,
     * rather than something to start playing on its own — so the decoder it eventually spawns is
     * the part the finger landed in, seeked to the offset inside it.
     */
    @Test
    fun `a seek with nothing playing is where the next press starts`() {
        val spawns = CopyOnWriteArrayList<Pair<Path, Double>>()
        val player = RecordingPlayer(speaker = { FakeSpeaker() }, spawn = spawn(spawns))
        val audio = twoParts()

        player.seek(audio, 311.0)

        assertEquals(311.0, player.positionSec, "the bar shows the second the finger let go of")
        assertTrue(spawns.isEmpty(), "a scrub started a decoder of its own")

        player.play(audio)
        val started = await("nothing was decoded") { spawns.firstOrNull() }

        assertEquals(dir / "p002_mono.m4a", started.first)
        assertEquals(11.0, started.second, "the part is decoded from where the scrub left it")
        player.stop()
    }

    /**
     * The same scrub while a part is playing: the decoder that was running is killed where it is —
     * ffmpeg cannot be sent somewhere else — and the part the finger landed in is spawned at its
     * offset, without the press of Play the caller never made.
     */
    @Test
    fun `a seek while playing moves the decoder to the part it landed in`() {
        val spawns = CopyOnWriteArrayList<Pair<Path, Double>>()
        val processes = CopyOnWriteArrayList<FakeProcess>()
        val player = RecordingPlayer(
            speaker = { FakeSpeaker() },
            spawn = { path, seekSec ->
                spawns += path to seekSec
                FakeProcess().also(processes::add)
            },
        )
        val audio = twoParts()

        player.play(audio)
        await("nothing was decoded") { spawns.firstOrNull() }

        player.seek(audio, 311.0)
        val jumped = await("the seek started no decoder") { spawns.getOrNull(1) }

        assertEquals(dir / "p002_mono.m4a", jumped.first)
        assertEquals(11.0, jumped.second)
        assertFalse(processes[0].isAlive, "the decoder of the part left behind is still running")
        assertTrue(player.playing, "a scrub is not a pause")
        player.stop()
    }

    /**
     * The other half of the delete contract: the picture has an ffmpeg of its own over the same
     * files ([RecordingPlayer.prepare]), and a flag telling it to stop is not a file being let go
     * of. When [RecordingPlayer.stop] returns, that decoder and its thread are gone too — otherwise
     * `ShellModel.delete` takes the rows and leaves the directory behind an ffmpeg still reading it.
     */
    @Test
    fun `stop returns only once the waveform decoder and its thread are gone`() {
        val spawned = AtomicReference<FakeProcess>()
        val player = RecordingPlayer(spawn = { _, _ -> FakeProcess().also(spawned::set) })

        player.prepare(RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(60.0)))
        val decoder = await("nothing was decoded for the waveform") { spawned.get() }

        player.stop()

        assertFalse(decoder.isAlive, "ffmpeg is still holding the part open for the picture")
        assertTrue(
            Thread.getAllStackTraces().keys.none { it.name == WAVEFORM_THREAD && it.isAlive },
            "the waveform thread outlived the stop",
        )
    }

    /**
     * The last part has been decoded and the speaker is still emptying: the run is inside `drain`,
     * which is a moment long enough to drop a scrub in. What follows it has to be the seek and not
     * the end of the recording — ending here would put the clock back to 0:00 under a playhead the
     * finger just moved.
     */
    @Test
    fun `a seek while the speaker is emptying still lands`() {
        val spawns = CopyOnWriteArrayList<Pair<Path, Double>>()
        val speaker = FakeSpeaker(holdsTheDrain = true)
        val player = RecordingPlayer(
            speaker = { speaker },
            spawn = { path, seekSec ->
                spawns += path to seekSec
                // A second of PCM and then the end of the part; what the seek starts plays on, so
                // the run does not end underneath the assertions.
                if (spawns.size == 1) FakeProcess(bytes = 32_000) else FakeProcess()
            },
        )
        val audio = RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(300.0))

        player.play(audio)
        await("the speaker was never drained") { speaker.draining.takeIf { it } }

        player.seek(audio, 42.0)
        val jumped = await("the seek was lost at the end of the recording") { spawns.getOrNull(1) }

        assertEquals(dir / "p001_mono.m4a", jumped.first)
        assertEquals(42.0, jumped.second)
        assertTrue(player.playing, "the recording ended instead of following the scrub")
        assertTrue(player.positionSec >= 42.0, "the clock went back to the start: ${player.positionSec}")
        player.stop()
    }

    /** Another recording picked, or the window closed: the scrub goes with everything else. */
    @Test
    fun `stop clears where a seek left the playhead`() {
        val spawns = CopyOnWriteArrayList<Pair<Path, Double>>()
        val player = RecordingPlayer(speaker = { FakeSpeaker() }, spawn = spawn(spawns))
        val audio = twoParts()

        player.seek(audio, 311.0)
        player.stop()

        assertEquals(0.0, player.positionSec)

        player.play(audio)
        val started = await("nothing was decoded") { spawns.firstOrNull() }

        assertEquals(dir / "p001_mono.m4a", started.first, "the press after a stop starts the recording")
        assertEquals(0.0, started.second)
        player.stop()
    }

    /** A recording of two parts, five minutes and two, as `meta.json` recorded them. */
    private fun twoParts() = RecordingPlaylist.Selection(
        listOf(dir / "p001_mono.m4a", dir / "p002_mono.m4a"),
        listOf(300.0, 120.0),
    )

    /** A decoder that never runs out, and the arguments it was asked for. */
    private fun spawn(spawns: MutableList<Pair<Path, Double>>): (Path, Double) -> Process =
        { path, seekSec ->
            spawns += path to seekSec
            FakeProcess()
        }

    /** The one thing a test may wait for: a thread the player started getting as far as its work. */
    private fun <T : Any> await(what: String, read: () -> T?): T {
        val deadline = System.nanoTime() + AWAIT_NANOS
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(what)
    }

    private fun part(index: Int, track: Track, file: String, durationSec: Double) = Part(
        part = index,
        track = track,
        file = file,
        bytes = 1024,
        sha256 = "0".repeat(64),
        startOffsetSec = 0.0,
        durationSec = durationSec,
    )

    private companion object {
        /** [RecordingPlayer]'s own threads, by the names it gives them. */
        const val PLAYER_THREAD = "recly-player"
        const val WAVEFORM_THREAD = "recly-waveform"
        const val POLL_MS = 5L
        const val AWAIT_NANOS = 5_000_000_000L
    }
}

/**
 * A speaker with nothing behind it: it takes every byte it is offered and remembers being closed,
 * which is the half of [RecordingPlayer.stop]'s promise that is not the process.
 *
 * @param holdsTheDrain whether `drain()` waits, the way a real line's does while the last part is
 *   still being heard — and lets go when it is flushed, which is what aborts a drain on a real one.
 *   The end of the recording is a moment the player is *inside* a call, and one a seek can land in.
 */
private class FakeSpeaker(
    /**
     * Held inside [close], which is where the decoding thread spends the last of its teardown: it
     * is the one place a test can keep the player in STOPPING long enough to press Play at it.
     */
    private val closeGate: CountDownLatch? = null,
    private val holdsTheDrain: Boolean = false,
) : SourceDataLine {

    @Volatile var closed: Boolean = false
        private set

    /** The thread has reached the close and is inside [closeGate]. */
    @Volatile var closing: Boolean = false
        private set

    /** Whether the line is in a drain right now, which a test cannot ask a real one. */
    @Volatile var draining: Boolean = false
        private set

    @Volatile private var flushed: Boolean = false

    @Volatile private var open: Boolean = false

    override fun open(format: AudioFormat, bufferSize: Int) {
        open = true
    }

    override fun open(format: AudioFormat) = open(format, 0)

    override fun open() = open(FORMAT, 0)

    override fun write(b: ByteArray, off: Int, len: Int): Int = len

    override fun drain() {
        if (!holdsTheDrain) return
        draining = true
        while (!flushed) Thread.sleep(1)
        draining = false
    }

    override fun flush() {
        flushed = true
    }

    override fun start() = Unit

    override fun stop() = Unit

    override fun isRunning(): Boolean = open

    override fun isActive(): Boolean = open

    override fun getFormat(): AudioFormat = FORMAT

    override fun getBufferSize(): Int = BUFFER

    override fun available(): Int = BUFFER

    override fun getFramePosition(): Int = 0

    override fun getLongFramePosition(): Long = 0

    override fun getMicrosecondPosition(): Long = 0

    override fun getLevel(): Float = 0f

    override fun getLineInfo(): Line.Info = DataLine.Info(SourceDataLine::class.java, FORMAT)

    override fun close() {
        open = false
        closing = true
        closeGate?.await()
        closed = true
    }

    override fun isOpen(): Boolean = open

    override fun getControls(): Array<Control> = emptyArray()

    override fun isControlSupported(control: Control.Type): Boolean = false

    override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("$control")

    override fun addLineListener(listener: LineListener) = Unit

    override fun removeLineListener(listener: LineListener) = Unit

    private companion object {
        val FORMAT = AudioFormat(16_000f, 16, 1, true, false)
        const val BUFFER = 4096
    }
}

/**
 * An ffmpeg that never runs out of PCM, so the only way its reader ends is the one the test is
 * about — a `destroyForcibly` from [RecordingPlayer.stop].
 *
 * @param bytes how much PCM it has, for the tests that need a part to actually end: what happens
 *   after the last one is a `drain` the player is inside of.
 */
private class FakeProcess(private val bytes: Long = Long.MAX_VALUE) : Process() {

    @Volatile private var running = true

    private val pcm = object : InputStream() {
        private var written = 0L

        override fun read(): Int = if (running && written < bytes) 0 else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!running) return -1
            val left = bytes - written
            if (left <= 0) return -1
            // A chunk at a time rather than a spin: the reader is a thread the test is waiting on.
            Thread.sleep(1)
            val n = minOf(len.toLong(), left).toInt()
            b.fill(0, off, off + n)
            written += n
            return n
        }
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = pcm

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        while (running) Thread.sleep(1)
        return 0
    }

    // What `Process.isAlive` is built on: a process that is still running has no exit code yet.
    override fun exitValue(): Int = if (running) throw IllegalThreadStateException() else 0

    override fun destroy() {
        running = false
    }
}
