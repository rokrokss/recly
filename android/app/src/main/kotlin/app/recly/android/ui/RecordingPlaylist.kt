package app.recly.android.ui

import okio.Path
import recly.core.model.Part
import recly.core.model.Track

/**
 * docs/08 "결과 파일": which of the files beside `meta.json` the detail plays back, and in what
 * order. A pure choice over `meta`, so it can be checked without a disk or a player — the caller
 * only hands it the recording's directory and a way to ask whether a file is there. RecKit's
 * `RecordingPlaylist` is the same object, and the two are meant to stay the same.
 */
object RecordingPlaylist {

    /**
     * The local audio of one recording, in play order: what to play, and how long each part is.
     * The durations are the ones `meta.json` recorded, which is what the elapsed clock counts in —
     * asking the extractor would mean opening every file before the first one could start.
     */
    data class Selection(val paths: List<Path>, val durations: List<Double>) {
        val isEmpty: Boolean get() = paths.isEmpty()
        val totalSec: Double get() = durations.sum()

        companion object {
            val EMPTY = Selection(emptyList(), emptyList())
        }
    }

    /**
     * The one track a person means by "play this": the mix if the recording has one — a meeting's
     * mic and system audio already summed — and otherwise the single `mono` track a memo is.
     * `mic`/`sys` are the mix's own ingredients and are never played on their own here.
     */
    fun playedTrack(parts: List<Part>): Track =
        if (parts.any { it.track == Track.MIX }) Track.MIX else Track.MONO

    /** That track's parts, in part order — what the clock and the playlist are both built from. */
    fun played(parts: List<Part>): List<Part> {
        val track = playedTrack(parts)
        return parts.filter { it.track == track }.sortedBy { it.part }
    }

    /**
     * A part whose file is not on this phone is dropped rather than played as silence: what the
     * retention sweep took leaves a playlist with a gap in it (docs/03 ADR-017), which the detail
     * either fills from Drive ([fetchesFromDrive]) or says in words.
     */
    fun select(parts: List<Part>, dir: Path, exists: (Path) -> Boolean): Selection {
        val kept = played(parts).map { it to dir / it.file }.filter { (_, path) -> exists(path) }
        return Selection(kept.map { it.second }, kept.map { it.first.durationSec })
    }

    /**
     * docs/03 ADR-017: the local parts are a seven-day cache now, so a gap in [select]'s playlist
     * is not necessarily a gap in the recording — Drive keeps every part it was given, and the
     * detail fetches back what the sweep took.
     *
     * The trip is only made when there is a gap *and* Drive has the parts to fill it with: a
     * recording that never reached Drive has nothing there to ask for, and "No audio on this
     * device" is still the true sentence about it.
     *
     * @param local how many parts of the played track this phone still has.
     * @param track how many the played track has in `meta.json`.
     * @param uploaded whether Drive holds every part (`ReclyCore.uploaded`).
     */
    fun fetchesFromDrive(local: Int, track: Int, uploaded: Boolean): Boolean = local < track && uploaded

    /**
     * The same playlist, rebuilt out of what `ReclyCore.audio` came back with. A fetched part is
     * written into the recording's own directory under the name its row gives it, so the durations
     * the clock counts in are still `meta.json`'s.
     *
     * A fetch that could not bring every part back stops at the gap: the playlist is the parts from
     * the first one up to the one that stayed missing, and nothing after it. Playing on past a gap
     * would put every later part on the clock at the wrong time — the transcript below reads against
     * that clock, so 10:00 would index some other moment of the recording. The contiguous prefix is
     * what can be played correctly, and the bar says in words that the rest could not be fetched.
     *
     * @param parts the played track's parts, for the order and the durations.
     * @param files the file names `ReclyCore.audio` handed back.
     */
    fun fetched(parts: List<Part>, files: Set<String>, dir: Path): Selection {
        val kept = played(parts).takeWhile { it.file in files }
        return Selection(kept.map { dir / it.file }, kept.map { it.durationSec })
    }

    /**
     * What a fetch that threw part-way left behind. `ReclyCore.audio` writes each part as it comes
     * back, so a trip that failed on part k+1 still put 1..k in the recording's directory — the
     * selection the page had before the trip says neither that those arrived nor, for a later part
     * that was already local, that the ones before it are still missing.
     *
     * The disk is asked rather than the failure, and the answer goes through [fetched]: a prefix is
     * all that can be played on the recording's own clock either way.
     */
    fun restored(parts: List<Part>, dir: Path, exists: (Path) -> Boolean): Selection =
        fetched(parts, played(parts).map { it.file }.filter { exists(dir / it) }.toSet(), dir)

    /**
     * Whether the detail's Play may start something, at the moment it is asked. Three things have
     * to be true at once, and the bar both draws the button by this and asks again at the press —
     * a recorder started from a tile or a widget can take the microphone between the two.
     *
     * @param recorderIdle nothing on this phone is capturing (`RecorderState.Idle`).
     * @param fetchDecided the trip to Drive is settled, so what would play is settled too.
     * @param hasAudio there is a part of this recording on this phone to play.
     */
    fun canPlay(recorderIdle: Boolean, fetchDecided: Boolean, hasAudio: Boolean): Boolean =
        recorderIdle && fetchDecided && hasAudio

    /**
     * Where the recording's own clock is: the parts already played, plus how far into the current
     * one the player is. Pure, because it is the one piece of the player worth checking without a
     * file.
     */
    fun position(durations: List<Double>, finished: Int, itemSec: Double): Double =
        durations.take(finished.coerceAtLeast(0)).sum() + itemSec.coerceAtLeast(0.0)
}
