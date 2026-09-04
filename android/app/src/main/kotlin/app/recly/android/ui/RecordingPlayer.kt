package app.recly.android.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * The detail's playback: the parts of one track, end to end, as the one thing the recording was.
 *
 * An ExoPlayer playlist is what makes that one thing out of several files — it advances itself,
 * with no gap to hear between two parts of the same take, and it is also what makes a [seek] one
 * call however many parts the second asked for is across. What a caller has of it is "playing or
 * not" and how far in it is. RecKit's `RecordingPlayer` is the same object over `AVQueuePlayer`.
 *
 * The clock is not self-winding: [tick] is called by whoever draws it, four times a second while
 * something is playing (`RecordingDetailScreen`). A player nobody is looking at is not one whose
 * clock anybody needs.
 */
@Stable
@androidx.annotation.OptIn(UnstableApi::class)
class RecordingPlayer(context: Context) {

    var isPlaying by mutableStateOf(false)
        private set

    /** Seconds from the start of the *recording*, not of the part being played. */
    var positionSec by mutableDoubleStateOf(0.0)
        private set

    private var selection = RecordingPlaylist.Selection.EMPTY

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // docs/09: speech, played back — not the recorder's own capture and not a notification.
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        repeatMode = Player.REPEAT_MODE_OFF
    }

    // Registered here rather than in the `apply` above, where `stop()` would be the *player's* and
    // not this object's — a call that leaves the recording sitting at its own end with a Pause on
    // the bar, which is what it did until it was moved out.
    init {
        player.addListener(
            object : Player.Listener {
                // The end of the whole recording is the end of its last part, and there is nothing
                // after it: the clock goes back to the start rather than standing at the end.
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) stop()
                }
            },
        )
    }

    /**
     * A different playlist (or none): whatever was playing stops first. Building the queue is left
     * to [play], so opening a recording does not touch the audio output.
     *
     * Called again at the press of Play, because [stop] leaves the player holding nothing: the
     * selection that plays is always the one the bar is showing at that moment.
     */
    fun load(selection: RecordingPlaylist.Selection) {
        if (selection == this.selection) return
        stop()
        this.selection = selection
    }

    fun play() {
        if (selection.isEmpty) return
        queue()
        player.play()
        isPlaying = true
    }

    /**
     * docs/09 화면 원칙 2: the second the finger let go of on the waveform, wherever in the
     * recording it falls. One `seekTo` even when it is in another part, because the playlist is one
     * thing to the player — [RecordingPlaylist.position] and [target] are the same arithmetic read
     * the two ways round.
     *
     * A seek is not a press of Play: a queue is built for a player that is holding nothing, so
     * there is somewhere for the playhead to be, but nothing starts. The clock is moved here rather
     * than left to [tick], which only runs while something is playing.
     */
    fun seek(selection: RecordingPlaylist.Selection, sec: Double) {
        if (selection.isEmpty) return
        val at = sec.coerceIn(0.0, selection.totalSec)
        load(selection)
        queue()
        val (index, offsetSec) = target(selection.durations, at)
        player.seekTo(index, (offsetSec * 1000).toLong())
        positionSec = at
    }

    /** The parts as the player's own playlist, where it is not already holding them. */
    private fun queue() {
        if (player.mediaItemCount > 0) return
        player.setMediaItems(selection.paths.map { MediaItem.fromUri(Uri.fromFile(File(it.toString()))) })
        player.prepare()
    }

    fun pause() {
        player.pause()
        isPlaying = false
    }

    /**
     * Everything that ends playback ends here: the end of the last part, another recording opened,
     * the page left. Idempotent — a player with nothing going has nothing to stop.
     *
     * The next [play] builds the queue again rather than seeking a drained one, which is also what
     * makes the end of the recording a return to its start. What it was going to play goes too, so
     * nothing plays that the caller has not just [load]ed.
     */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        selection = RecordingPlaylist.Selection.EMPTY
        positionSec = 0.0
        isPlaying = false
    }

    /**
     * The recording's own clock, as of now — the parts already played plus the current one.
     *
     * Only while something is playing: the caller's ticking coroutine is cancelled a frame or two
     * after [stop], and a stray tick in between would put the clock back where the recording ended
     * instead of at its start.
     */
    fun tick() {
        if (!isPlaying) return
        positionSec = RecordingPlaylist.position(
            durations = selection.durations,
            // The current item is the one after all the finished ones.
            finished = player.currentMediaItemIndex,
            itemSec = player.currentPosition / 1000.0,
        )
    }

    /** The player is gone after this. Nothing else may be called on it. */
    fun release() {
        player.release()
        isPlaying = false
    }

    companion object {
        /**
         * How often the playhead is moved while playing: 30 steps a second, so the bar slides rather
         * than steps — the clock beside it only counts whole seconds, but the playhead on the
         * waveform is drawn at this position, and at four steps a second it jumps rather than moves
         * (docs/09 "모션"). Finer would be redraws no screen this runs on can show.
         */
        const val TICK_MS: Long = 33

        /**
         * Which part a second of the recording is in, and how far into it — the item and the offset
         * a [seek] is made of. Pure, because it is the whole of what a scrub across a part boundary
         * has to get right; the Windows shell's `RecordingPlayer.target` is the same arithmetic.
         *
         * The boundary belongs to the part that starts there rather than to the one that ends
         * there, so a drag through it lands on the first frame of the next part instead of the last
         * of the previous. Past the end is the end of the last part, and no parts at all is the
         * start of a playlist that has nothing in it to be anywhere else in.
         */
        fun target(durations: List<Double>, sec: Double): Pair<Int, Double> {
            val last = durations.lastIndex
            if (last < 0) return 0 to 0.0
            var offsetSec = sec.coerceAtLeast(0.0)
            durations.forEachIndexed { index, durationSec ->
                if (offsetSec < durationSec) return index to offsetSec
                offsetSec -= durationSec
            }
            return last to durations[last]
        }
    }
}
