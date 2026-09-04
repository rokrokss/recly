package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path
import okio.Path.Companion.toPath
import recly.core.model.Part
import recly.core.model.Track

/**
 * docs/08 "결과 파일": what the detail plays, in what order, and what its clock counts in. The same
 * rules RecKit's `RecordingPlaylistTests` holds for the Apple shells — the two are meant to answer
 * a recording the same way.
 */
class RecordingPlaylistTest {

    private val dir: Path = "/rec/2026-09-03T10-00-00Z".toPath()

    @Test
    fun `a recording with a mix plays the mix, not the tracks it was made of`() {
        val parts = listOf(part(1, Track.MIC), part(1, Track.SYS), part(1, Track.MIX))
        assertEquals(Track.MIX, RecordingPlaylist.playedTrack(parts))
    }

    @Test
    fun `a memo plays the one track it has`() {
        assertEquals(Track.MONO, RecordingPlaylist.playedTrack(listOf(part(1, Track.MONO))))
    }

    @Test
    fun `the parts play in part order, whatever order meta lists them in`() {
        val parts = listOf(part(3, Track.MONO), part(1, Track.MONO), part(2, Track.MONO))
        val selection = RecordingPlaylist.select(parts, dir) { true }
        assertEquals(
            listOf(dir / "mono-001.m4a", dir / "mono-002.m4a", dir / "mono-003.m4a"),
            selection.paths,
        )
    }

    @Test
    fun `only the played track is in the playlist`() {
        val parts = listOf(part(1, Track.MIX), part(1, Track.MIC), part(2, Track.MIX))
        val selection = RecordingPlaylist.select(parts, dir) { true }
        assertEquals(listOf(dir / "mix-001.m4a", dir / "mix-002.m4a"), selection.paths)
    }

    @Test
    fun `a part the sweep took is not played as silence`() {
        val parts = listOf(part(1, Track.MONO), part(2, Track.MONO), part(3, Track.MONO))
        val selection = RecordingPlaylist.select(parts, dir) { it.name != "mono-002.m4a" }
        assertEquals(listOf(dir / "mono-001.m4a", dir / "mono-003.m4a"), selection.paths)
        assertEquals(120.0, selection.totalSec)
    }

    @Test
    fun `the total is what meta says the parts are, not what the files are`() {
        val selection = RecordingPlaylist.select(
            listOf(part(1, Track.MONO, durationSec = 42.5), part(2, Track.MONO, durationSec = 7.5)),
            dir,
        ) { true }
        assertEquals(50.0, selection.totalSec)
    }

    // --- the trip to Drive -------------------------------------------------------------------

    @Test
    fun `every part is here, so there is nothing to fetch`() {
        assertEquals(false, RecordingPlaylist.fetchesFromDrive(local = 3, track = 3, uploaded = true))
    }

    @Test
    fun `a gap Drive can fill is fetched`() {
        assertEquals(true, RecordingPlaylist.fetchesFromDrive(local = 1, track = 3, uploaded = true))
    }

    @Test
    fun `a recording that never reached Drive has nothing to ask for`() {
        assertEquals(false, RecordingPlaylist.fetchesFromDrive(local = 0, track = 3, uploaded = false))
    }

    @Test
    fun `a fetch that brought everything back plays the whole recording`() {
        val parts = listOf(part(1, Track.MONO), part(2, Track.MONO), part(3, Track.MONO))
        val selection = RecordingPlaylist.fetched(
            parts,
            setOf("mono-001.m4a", "mono-002.m4a", "mono-003.m4a"),
            dir,
        )
        assertEquals(3, selection.paths.size)
        assertEquals(180.0, selection.totalSec)
    }

    @Test
    fun `a fetch that stopped at a gap plays the parts up to it and no further`() {
        val parts = listOf(part(1, Track.MONO), part(2, Track.MONO), part(3, Track.MONO))
        val selection = RecordingPlaylist.fetched(parts, setOf("mono-001.m4a", "mono-003.m4a"), dir)
        assertEquals(listOf(dir / "mono-001.m4a"), selection.paths)
        assertEquals(60.0, selection.totalSec)
    }

    @Test
    fun `a fetch that brought nothing back plays nothing`() {
        val selection = RecordingPlaylist.fetched(listOf(part(1, Track.MONO)), emptySet(), dir)
        assertEquals(true, selection.isEmpty)
    }

    @Test
    fun `a fetch that threw part-way still plays the parts it had written`() {
        val parts = listOf(part(1, Track.MONO), part(2, Track.MONO), part(3, Track.MONO))
        val onDisk = setOf("mono-001.m4a", "mono-002.m4a")
        val selection = RecordingPlaylist.restored(parts, dir) { it.name in onDisk }
        assertEquals(listOf(dir / "mono-001.m4a", dir / "mono-002.m4a"), selection.paths)
        assertEquals(120.0, selection.totalSec)
    }

    @Test
    fun `a fetch that threw before part one plays nothing, whatever came after it`() {
        val parts = listOf(part(1, Track.MONO), part(2, Track.MONO), part(3, Track.MONO))
        val selection = RecordingPlaylist.restored(parts, dir) { it.name == "mono-003.m4a" }
        assertEquals(true, selection.isEmpty)
    }

    @Test
    fun `what is on disk is read as the played track alone`() {
        val parts = listOf(part(1, Track.MIX), part(1, Track.MIC), part(2, Track.MIX))
        val selection = RecordingPlaylist.restored(parts, dir) { true }
        assertEquals(listOf(dir / "mix-001.m4a", dir / "mix-002.m4a"), selection.paths)
    }

    // --- what Play may start -------------------------------------------------------------------

    @Test
    fun `Play starts when the microphone is free, the fetch is decided and audio is here`() {
        assertEquals(
            true,
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = true, hasAudio = true),
        )
    }

    @Test
    fun `Play starts nothing while this phone is recording`() {
        assertEquals(
            false,
            RecordingPlaylist.canPlay(recorderIdle = false, fetchDecided = true, hasAudio = true),
        )
    }

    @Test
    fun `Play starts nothing while the trip to Drive is still being decided`() {
        assertEquals(
            false,
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = false, hasAudio = true),
        )
    }

    @Test
    fun `Play starts nothing when no part of the recording is on this phone`() {
        assertEquals(
            false,
            RecordingPlaylist.canPlay(recorderIdle = true, fetchDecided = true, hasAudio = false),
        )
    }

    // --- the clock ---------------------------------------------------------------------------

    @Test
    fun `the clock counts the parts already played plus the current one`() {
        assertEquals(75.0, RecordingPlaylist.position(listOf(60.0, 60.0), finished = 1, itemSec = 15.0))
    }

    @Test
    fun `the first part's clock is the recording's own`() {
        assertEquals(15.0, RecordingPlaylist.position(listOf(60.0, 60.0), finished = 0, itemSec = 15.0))
    }

    @Test
    fun `a player with no position yet stands at the start`() {
        assertEquals(0.0, RecordingPlaylist.position(listOf(60.0), finished = 0, itemSec = -1.0))
    }

    // --- the seek ----------------------------------------------------------------------------

    @Test
    fun `a second inside the first part is that part's own second`() {
        assertEquals(0 to 15.0, RecordingPlayer.target(listOf(60.0, 60.0), 15.0))
    }

    @Test
    fun `a second past the first part is the next part's, counted from its start`() {
        assertEquals(1 to 15.0, RecordingPlayer.target(listOf(60.0, 60.0), 75.0))
    }

    @Test
    fun `the boundary belongs to the part that starts there`() {
        assertEquals(1 to 0.0, RecordingPlayer.target(listOf(60.0, 60.0), 60.0))
    }

    @Test
    fun `past the end is the end of the last part`() {
        assertEquals(1 to 60.0, RecordingPlayer.target(listOf(60.0, 60.0), 500.0))
    }

    @Test
    fun `a playlist with nothing in it has nowhere to seek to`() {
        assertEquals(0 to 0.0, RecordingPlayer.target(emptyList(), 30.0))
    }

    private fun part(number: Int, track: Track, durationSec: Double = 60.0) = Part(
        part = number,
        track = track,
        file = "${track.name.lowercase()}-%03d.m4a".format(number),
        bytes = 1024,
        sha256 = "0".repeat(64),
        startOffsetSec = (number - 1) * durationSec,
        durationSec = durationSec,
    )
}
