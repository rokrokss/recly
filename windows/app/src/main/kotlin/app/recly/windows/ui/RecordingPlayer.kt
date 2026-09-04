package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.helper.CaptureHelper
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import okio.Path
import recly.core.model.Part
import recly.core.model.Track
import recly.core.platform.Logger

/**
 * docs/08 "결과 파일": which of the files beside `meta.json` the detail plays back, and in what
 * order. A pure choice over `meta`, so it can be checked without a disk or a player — the shell
 * only hands it the recording's parts, their directory, and a way to ask whether a file is there.
 *
 * The same rules RecKit's `RecordingPlaylist` follows, so the two shells play the same thing.
 */
object RecordingPlaylist {

    /**
     * The local audio of one recording, in play order: what to play, and how long each part is.
     * The durations are the ones `meta.json` recorded, which is what the elapsed clock counts in —
     * asking ffmpeg would mean decoding every file before the first one could start.
     */
    data class Selection(
        val paths: List<Path> = emptyList(),
        val durations: List<Double> = emptyList(),
    ) {
        val isEmpty: Boolean get() = paths.isEmpty()

        val totalSec: Double get() = durations.sum()

        companion object {
            val EMPTY: Selection = Selection()
        }
    }

    /**
     * The one track a person means by "play this": the mix if the recording has one — a meeting's
     * mic and system audio already summed — and otherwise the single `mono` track a memo is.
     * `mic`/`sys` are the mix's own ingredients and are never played on their own here. The same
     * choice `recly.core.recording.AudioParts` makes when it fetches from Drive.
     */
    fun playedTrack(parts: List<Part>): Track =
        if (parts.any { it.track == Track.MIX }) Track.MIX else Track.MONO

    /**
     * That track's parts, in the order they were recorded — `meta.json` lists them in whatever
     * order they were added, and the parts are one recording end to end.
     */
    fun playedParts(parts: List<Part>): List<Part> {
        val track = playedTrack(parts)
        return parts.filter { it.track == track }.sortedBy { it.part }
    }

    /**
     * A part whose file is not on this PC is dropped rather than played as silence: what the
     * retention sweep took leaves a playlist with a gap in it (docs/03 ADR-017), which the detail
     * either fills from Drive ([fetchesFromDrive]) or says in words.
     */
    fun select(parts: List<Part>, dir: Path, exists: (Path) -> Boolean): Selection {
        val kept = playedParts(parts).filter { exists(dir / it.file) }
        return Selection(kept.map { dir / it.file }, kept.map { it.durationSec })
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
     * @param local how many parts of the played track this PC still has.
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
     * would put every later part on the clock at the wrong time — the transcript below reads
     * against that clock, so 10:00 would index some other moment of the recording.
     *
     * @param parts the played track's parts ([playedParts]), for the order and the durations.
     * @param files the file names the fetch handed back.
     */
    fun fetched(parts: List<Part>, files: List<String>, dir: Path): Selection {
        val present = files.toSet()
        val kept = playedParts(parts).takeWhile { it.file in present }
        return Selection(kept.map { dir / it.file }, kept.map { it.durationSec })
    }

    /**
     * Whether the detail's Play may start something, at the moment it is asked. Four things have
     * to be true at once, and the bar both draws the button by this and asks again at the press — a
     * recording started from the tray takes the microphone between the two often enough to matter,
     * and on this PC the capture takes the speakers with it (ADR-006), so what is playing would be
     * *in* the recording. Android's `RecordingPlaylist.canPlay` is the same rule, minus the gate:
     * only this shell deletes recordings while a window is open on them.
     *
     * @param recorderIdle nothing on this PC is capturing.
     * @param fetchDecided the trip to Drive is settled, so what would play is settled too.
     * @param hasAudio there is a part of this recording on this PC to play.
     * @param blocked [PlaybackGate.blocked]: a capture is opening, or a clean-up is removing the
     *   very files a press would start reading. [recorderIdle] is the helper's own answer and
     *   arrives only once the capture is up, so the two are not the same reading.
     */
    fun canPlay(recorderIdle: Boolean, fetchDecided: Boolean, hasAudio: Boolean, blocked: Boolean): Boolean =
        recorderIdle && fetchDecided && hasAudio && !blocked
}

/**
 * Why the detail may not play right now, held for as long as the reason lasts rather than checked
 * once at the top of it.
 *
 * Two things raise it, and both are things the shell *does* rather than states it is in, which is
 * why a single boolean read at the press was not enough:
 *
 * - [Reason.CAPTURE] — ADR-006: this PC's capture takes the system audio with it, so anything
 *   playing would be *in* the recording. The recorder publishes `onState(true)` only after the
 *   helper's Start has gone out, so a gate that waited for it would go up after the fact.
 * - [Reason.CLEANUP] — docs/03: a delete or a disconnect removes the files playback reads, and the
 *   work between the two (the revoke is a network round trip) is long enough for a press to land
 *   inside it and hand ffmpeg a part the core is about to remove.
 *
 * Reasons rather than a count, so a raise is idempotent: whoever raises one is the one that lowers
 * it, and a second raise of the same reason is not a second thing to undo.
 */
class PlaybackGate {

    enum class Reason { CAPTURE, CLEANUP }

    private val raised = mutableSetOf<Reason>()

    /** Read by the bar to draw Play, and by the window to stop what is already going. */
    var blocked: Boolean by mutableStateOf(false)
        private set

    @Synchronized
    fun raise(reason: Reason) {
        raised += reason
        blocked = raised.isNotEmpty()
    }

    @Synchronized
    fun lower(reason: Reason) {
        raised -= reason
        blocked = raised.isNotEmpty()
    }

    /**
     * The order every destructive step keeps, and the whole of what makes it safe: the gate up
     * first, then the speaker off the files, and only then the step itself — with the gate still up
     * for all of it, so nothing pressed in the middle starts a second decoder.
     *
     * A [stop] that came back false is a decoder still alive on a part, and then [run] does not run
     * at all: `RecordingRepository.delete` commits the row deletion before it walks the directory,
     * so a delete made over a live ffmpeg takes the rows and leaves the audio behind — there is
     * nothing left to try again with. Refusing leaves both, and the user can press again.
     *
     * @return what [run] gave back, or null when playback would not stop.
     */
    suspend fun <T : Any> cleaning(stop: suspend () -> Boolean, run: suspend () -> T): T? {
        raise(Reason.CLEANUP)
        return try {
            if (stop()) run() else null
        } finally {
            lower(Reason.CLEANUP)
        }
    }
}

/**
 * docs/09 화면 원칙 2: the shape of the recording under the player bar's clock — what the detail
 * draws a playhead across, and what a drag on it seeks through. RecKit's `RecordingWaveform`, in
 * the same two halves, so the two shells draw the same picture.
 *
 * [bins] is the arithmetic the drawing is, and can be checked without a file or a screen; [peaks]
 * is the decode that has to open every part. What the bar holds between them is one `FloatArray` of
 * peaks on the recording's own timeline, 0…1, one per [WINDOW_SEC] window.
 */
object RecordingWaveform {

    /**
     * The default window: 0.25 s, the tick the clock already moves in. Finer would be more windows
     * than a bar can be drawn for on any screen this runs on.
     */
    const val WINDOW_SEC = 0.25

    /**
     * The peaks resampled to exactly the number of bars there is room for, and normalised so the
     * loudest one fills the row. A recording that was quiet throughout is still drawn as a shape
     * rather than as a flat line — the bars say where the sound is, not how many decibels it was.
     *
     * Nothing to draw (no peaks, or no room) is empty, which the bar answers with its baseline.
     * More bars than windows repeats a window across the bars that fall inside it, rather than
     * leaving a gap or reading off the end.
     */
    fun bins(peaks: FloatArray, count: Int): FloatArray {
        if (count <= 0 || peaks.isEmpty()) return FloatArray(0)
        // Each bar is the loudest window under it: a peak that survives downsampling is what makes
        // a waveform readable, where an average would flatten every transient into the same grey.
        val bins = FloatArray(count) { index ->
            val start = index * peaks.size / count
            val end = minOf(peaks.size, maxOf(start + 1, (index + 1) * peaks.size / count))
            var loudest = 0f
            for (window in start until end) loudest = maxOf(loudest, peaks[window])
            loudest
        }
        val loudest = bins.max()
        if (loudest <= 0f) return bins
        for (index in bins.indices) bins[index] /= loudest
        return bins
    }

    /**
     * The parts of one selection decoded end to end, as the loudest sample in each [windowSec]
     * window — with the player's own ffmpeg ([RecordingPlayer]'s `spawn`, from the start of each
     * part), because the JVM cannot decode AAC and this is the decoder that is already here.
     *
     * The windows are counted against the durations `meta.json` recorded and not against what
     * ffmpeg wrote, because those are the seconds the clock and the transcript below are on: each
     * part contributes exactly `durationSec / windowSec` windows, rounded up, padded with silence
     * or truncated. So the peaks are `selection.totalSec` long however the files decode.
     *
     * Asked between parts and between reads whether it is still wanted, because a part is fifteen
     * minutes (docs/03) — which is fifteen minutes of decoding for a bar that has already been
     * replaced. What it has got so far is handed back then, for the caller to throw away.
     *
     * @param onProcess the decoder of the part being read, and null once it is gone. A flag alone
     *   is not a teardown: the caller has to be able to kill the ffmpeg *now*, because a delete is
     *   waiting on the file it has open ([RecordingPlayer.stop]).
     */
    fun peaks(
        selection: RecordingPlaylist.Selection,
        spawn: (Path, Double) -> Process,
        cancelled: () -> Boolean = { false },
        onProcess: (Process?) -> Unit = {},
        windowSec: Double = WINDOW_SEC,
    ): FloatArray {
        val peaks = ArrayList<Float>()
        selection.paths.forEachIndexed { index, path ->
            if (cancelled()) return peaks.toFloatArray()
            val part = decode(path, spawn, cancelled, onProcess, windowSec)
            val windows = ceil(selection.durations[index] / windowSec).toInt()
            for (window in 0 until windows) peaks += part.getOrElse(window) { 0f }
        }
        return peaks.toFloatArray()
    }

    /**
     * The raw PCM of one part — 16-bit little-endian mono at [RATE], which is what the player's
     * `-f s16le` writes — as one peak per window. Pure over a stream, so what the windows are cut
     * at can be checked with bytes rather than with a file.
     *
     * The samples are counted into windows across the read boundaries, so a window is [windowSec]
     * of the part and not of whatever the pipe happened to hand over; the tail of the part is a
     * window like any other, however short it came out.
     */
    fun peaks(
        pcm: InputStream,
        windowSec: Double = WINDOW_SEC,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        val perWindow = maxOf(1, (RATE * windowSec).roundToInt())
        val peaks = ArrayList<Float>()
        val buffer = ByteArray(CHUNK_BYTES)
        var loudest = 0f
        var counted = 0
        // The low half of a sample the read stopped in the middle of: two bytes are one number
        // however the pipe splits them.
        var low = -1
        while (!cancelled()) {
            val read = pcm.read(buffer)
            if (read < 0) break
            for (index in 0 until read) {
                val byte = buffer[index].toInt() and 0xFF
                if (low < 0) {
                    low = byte
                    continue
                }
                // `abs` of the widened sample and not of the `Short`: -32768 has no positive of its
                // own, and full scale is what it is.
                val sample = ((byte shl 8) or low).toShort().toInt()
                low = -1
                loudest = maxOf(loudest, abs(sample) / 32768f)
                counted += 1
                if (counted == perWindow) {
                    peaks += loudest
                    loudest = 0f
                    counted = 0
                }
            }
        }
        if (counted > 0) peaks += loudest
        return peaks.toFloatArray()
    }

    /**
     * One part, decoded from its start. A part that ffmpeg could not read at all is a hole in the
     * timeline rather than a short waveform — half a shape under a whole clock would put the
     * recording at the wrong seconds — so it is thrown, and the bar draws its baseline instead.
     */
    private fun decode(
        path: Path,
        spawn: (Path, Double) -> Process,
        cancelled: () -> Boolean,
        onProcess: (Process?) -> Unit,
        windowSec: Double,
    ): FloatArray {
        val decoder = spawn(path, 0.0)
        onProcess(decoder)
        try {
            decoder.outputStream.close()
            val peaks = decoder.inputStream.use { peaks(it, windowSec, cancelled) }
            if (cancelled()) return peaks
            check(decoder.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) && decoder.exitValue() == 0) {
                "waveform decode failed: $path"
            }
            return peaks
        } finally {
            decoder.destroyForcibly()
            decoder.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            onProcess(null)
        }
    }

    /** The `-ar` the player's ffmpeg writes, and so what a window of seconds is counted in. */
    private const val RATE = 16_000

    private const val CHUNK_BYTES = 8192

    private const val EXIT_WAIT_MS = 1_000L
}

/**
 * The detail's playback: the parts of one track, end to end, as the one thing the recording was.
 * What a caller has of it is "playing or not", how far in it is, and — since a second of the
 * recording is a second of one particular part — a [seek] that may have to start another decoder.
 *
 * **Why ffmpeg and not `javax.sound`**: the parts are AAC in an MP4 container (docs/08 "오디오"),
 * which the JDK's own audio system cannot decode at all. The bundled ffmpeg (ADR-019) is already
 * here for `audio.concat` ([app.recly.windows.core.FfmpegAudioTools]) and is resolved the same way,
 * so one part at a time is decoded to raw 16 kHz mono PCM on its stdout and fed straight into a
 * [SourceDataLine]. Nothing is written to disk and nothing is decoded ahead of what is playing.
 */
class RecordingPlayer(
    private val ffmpeg: String = CaptureHelper.ffmpeg(),
    /** Read at the failure rather than held: the window can be open before the core is (docs/14). */
    private val logger: () -> Logger? = { null },
    /**
     * The speaker, and the decoder that feeds it, as functions rather than as calls: what [stop]
     * promises about them is worth pinning (`RecordingPlaylistTest`), and neither a sound card nor
     * ffmpeg is something a unit test has.
     */
    private val speaker: () -> SourceDataLine = { AudioSystem.getSourceDataLine(FORMAT) },
    private val spawn: (Path, Double) -> Process = { path, seekSec ->
        ProcessBuilder(
            ffmpeg,
            "-v", "error",
            "-nostdin",
            // Before `-i`, which is ffmpeg's accurate input seek: it decodes from the keyframe
            // before the second asked for and writes from that second, so what a scrub hears and
            // what the clock says are the same instant.
            "-ss", seekSec.toString(),
            "-i", path.toString(),
            "-f", "s16le",
            "-ac", CHANNELS.toString(),
            "-ar", RATE.toString(),
            "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
    /**
     * How long [stop] waits for the decoding thread before saying it did not go. The default is the
     * one the shell runs on; a test pins the *answer* rather than the wall clock, and 2 s of real
     * waiting per case is not something a suite should spend.
     */
    private val teardownWaitMs: Long = TEARDOWN_WAIT_MS,
) {

    var playing: Boolean by mutableStateOf(false)
        private set

    /** Seconds from the start of the *recording*, not of the part being played. */
    var positionSec: Double by mutableStateOf(0.0)
        private set

    /**
     * docs/09 화면 원칙 2: the recording as a shape, one peak per [RecordingWaveform.WINDOW_SEC]
     * window of `meta.json`'s own timeline — empty until [prepare]'s decode is through, and empty
     * for good if it could not be.
     */
    var waveform: FloatArray by mutableStateOf(FloatArray(0))
        private set

    /**
     * The player is one of three things, and both fields are read and written under this object's
     * own lock:
     *
     * - **IDLE** — `playback == null`, `tearingDown == null`. Nothing is going.
     * - **PLAYING** — `playback != null`. A decoder thread is running (paused counts: the thread is
     *   still there, and so is its ffmpeg).
     * - **STOPPING** — `playback == null`, `tearingDown != null`. Someone has detached the playback
     *   and is waiting for its thread to end; the ffmpeg still has the part open.
     *
     * IDLE → PLAYING is [start], PLAYING → STOPPING is [detach], and STOPPING → IDLE is the [stop]
     * that was waiting, once the thread is gone. PLAYING → IDLE without the middle state is the
     * thread's own exit ([ended]), which has nothing to wait for.
     *
     * STOPPING is a state and not a moment because the wait happens *outside* the lock — the
     * decoder takes this same lock to move the clock, so holding it across the join would deadlock.
     * Without the field a second [stop] would find `playback == null`, call itself done and hand a
     * live ffmpeg to the delete it was making way for; a [play] would install a second decoder
     * beside the one still unwinding. Both now see STOPPING: the stop waits on the same thread, and
     * the play is refused.
     */
    private var playback: Playback? = null

    private var tearingDown: Playback? = null

    /**
     * Where the next [play] of [Pending.selection] starts, put there by a [seek] with nothing on.
     * Not to be confused with [pending], which is the teardown a second [stop] waits on.
     */
    private var pendingSeek: Pending? = null

    private var decoding: WaveformDecode? = null

    /** The selection [waveform] was last asked for, so the bar can ask at every composition. */
    private var prepared: RecordingPlaylist.Selection? = null

    /**
     * The picture the bar draws while there is nothing playing: every part decoded once, on a
     * thread of its own, for whatever selection the bar is showing. Idempotent per selection —
     * the bar asks at every composition, and a recording's audio only changes when the trip to
     * Drive comes back.
     *
     * The decode of the recording before this one is ended the way playback is: killed and waited
     * for, so that no ffmpeg of the last pick is still holding a file when this returns. [stop]
     * leaves a waveform that already arrived alone — the shape stays between one press and the
     * next, and only another recording replaces it.
     */
    fun prepare(selection: RecordingPlaylist.Selection) {
        if (isPrepared(selection)) return
        // Outside the lock, because what it waits for is a thread that takes the lock ([drew]).
        stopDecoding()
        startDecoding(selection)
    }

    @Synchronized
    private fun isPrepared(selection: RecordingPlaylist.Selection): Boolean = prepared == selection

    @Synchronized
    private fun startDecoding(selection: RecordingPlaylist.Selection) {
        prepared = selection
        waveform = FloatArray(0)
        if (selection.isEmpty) return
        decoding = WaveformDecode(selection).also { it.start() }
    }

    /**
     * The picture's decoder, gone the way [stop] leaves the sound's: killed, and waited for. The
     * peaks it may already have published stay — what is dropped is a decode still in flight, and
     * with it the claim that this selection has been prepared, so that a bar which asks again gets
     * a new decode rather than an empty row for ever.
     *
     * @return whether it is gone, which is half of what [stop] promises a delete.
     */
    private fun stopDecoding(): Boolean {
        val going = detachDecode() ?: return true
        going.cancel()
        return going.await()
    }

    @Synchronized
    private fun detachDecode(): WaveformDecode? {
        val going = decoding ?: return null
        decoding = null
        prepared = null
        return going
    }

    /** The decode that finished, if the bar is still on the recording it was for. */
    @Synchronized
    private fun drew(decode: WaveformDecode, peaks: FloatArray) {
        if (decoding !== decode) return
        waveform = peaks
    }

    /**
     * Starts [selection], or resumes it where [pause] left it. The caller hands in what the bar is
     * showing at the press, because the player holds nothing between one recording and the next
     * (see [stop]) — a player still holding the last pick would answer this press with it.
     *
     * A [seek] made while nothing was playing is where this starts, rather than the beginning: the
     * press after a scrub plays the second the scrub left the playhead on.
     */
    fun play(selection: RecordingPlaylist.Selection) {
        if (selection.isEmpty) return
        // Resuming is the one press that keeps what is running. Everything else starts by stopping,
        // and [stop] waits for the decoder — which takes this object's own lock to move the clock,
        // so waiting for it under that lock would be a deadlock rather than a stop.
        if (resume(selection)) return
        // A teardown already in flight is refused rather than queued behind: this is the UI thread,
        // and [stop] would park it on someone else's join for as long as the bound. The press is
        // lost, which is a button that did nothing — a second one lands on an idle player.
        if (stopping()) return
        // Before the teardown that clears it, and only ever the one this selection was scrubbed on.
        val from = take(selection)
        // The sound alone: a press of Play is not a reason to throw away the picture the bar is
        // waiting for, and the decode of *this* recording is what would be killed. A teardown of
        // this player's own that timed out leaves an ffmpeg alive on a part; a second decoder
        // beside it is not something to start.
        if (!stopPlaying()) return
        start(selection, from)
    }

    /** The start position a [seek] left for this selection, taken so it is used once. */
    @Synchronized
    private fun take(selection: RecordingPlaylist.Selection): Pair<Int, Double> {
        val start = pendingSeek?.takeIf { it.selection == selection } ?: return 0 to 0.0
        pendingSeek = null
        return start.index to start.offsetSec
    }

    /**
     * docs/09 화면 원칙 2: a drag or a tap on the waveform. The second is the *recording's*, so the
     * first thing it is turned into is a part and an offset into it ([target]).
     *
     * Two cases, and the difference is only whether there is a decoder to move. What is running for
     * this selection — playing or paused — jumps where it stands, on its own thread, so a scrub
     * never waits for ffmpeg. A player with nothing going (or one holding another recording) only
     * remembers where the finger left it, and the next [play] starts there.
     */
    fun seek(selection: RecordingPlaylist.Selection, sec: Double) {
        if (selection.isEmpty) return
        val at = sec.coerceIn(0.0, selection.totalSec)
        jump(selection, at, target(selection.durations, at))
    }

    @Synchronized
    private fun jump(
        selection: RecordingPlaylist.Selection,
        sec: Double,
        target: Pair<Int, Double>,
    ) {
        // Ahead of the next chunk, which is a poll away and does not come at all while the player
        // is paused: the bar shows the second the finger let go of.
        positionSec = sec
        val running = playback?.takeIf { it.selection == selection }
        if (running == null) {
            pendingSeek = Pending(selection, target.first, target.second)
            return
        }
        running.jumpTo(target)
    }

    /** The press that landed on what is already loaded: the paused line simply starts again. */
    @Synchronized
    private fun resume(selection: RecordingPlaylist.Selection): Boolean {
        val running = playback ?: return false
        if (running.selection != selection) return false
        running.resume()
        playing = true
        return true
    }

    @Synchronized
    private fun start(selection: RecordingPlaylist.Selection, from: Pair<Int, Double>) {
        // The lock was let go between the stop above and here, so this is the reading that counts:
        // another caller may have taken the player into STOPPING, or started something of its own.
        if (playback != null || tearingDown != null) return
        playback = Playback(selection, from).also { it.start() }
        playing = true
    }

    /** STOPPING: a teardown someone else started is still waiting for its thread. */
    @Synchronized
    private fun stopping(): Boolean = tearingDown != null

    @Synchronized
    fun pause() {
        playback?.pause()
        playing = false
    }

    /**
     * Everything that ends playback ends here: the end of the last part, another recording picked,
     * the window closed. Idempotent — a player with nothing going has nothing to stop, and calling
     * it twice is calling it once.
     *
     * What it was going to play goes too, so the next [play] starts from the recording the bar is
     * showing then, and from its beginning.
     *
     * **It waits for every decoder to be gone** — the sound's and the picture's ([prepare] runs an
     * ffmpeg of its own over the same files) — because deleting the recording is one of the things
     * that happens next: Windows will not remove a file ffmpeg still has open, and the core's
     * `RecordingRepository.delete` commits the row deletion before it walks the directory — so a
     * delete that started a millisecond early takes the rows and leaves the audio behind
     * ([ShellModel.delete]). When this returns there is no process and no thread of this player's
     * left alive.
     *
     * Each is taken out of its field under the lock and awaited outside it; see [play]. A caller
     * that arrives while someone else is already awaiting the playback joins the same thread rather
     * than returning early — its own promise is the same one, and the delete behind it cannot tell
     * the two callers apart.
     *
     * @return whether every decoder is gone. False is the bound running out with a thread still
     *   alive, and it is the caller's to act on: what comes next is usually a delete, and a delete
     *   made over a live ffmpeg is the one that cannot be retried.
     */
    fun stop(): Boolean {
        // Both halves, and both answers: the picture's ffmpeg has the same part open as the
        // sound's, and a delete cannot go over either of them. Neither is skipped for the other.
        val drawn = stopDecoding()
        val heard = stopPlaying()
        return drawn && heard
    }

    /** The half of [stop] that is the sound, which is all a press of Play has to end. */
    private fun stopPlaying(): Boolean {
        val stopping = detach() ?: pending() ?: return true
        stopping.cancel()
        val gone = stopping.await()
        // A teardown that never finished stays in the field: the ffmpeg is still on the part, so the
        // player is not free, and the next stop joins the same thread rather than declaring it over.
        if (gone) settle(stopping)
        return gone
    }

    /** PLAYING → STOPPING, which is all of [stop] the lock is for. */
    @Synchronized
    private fun detach(): Playback? {
        // A [seek] with nothing playing is a start position and not playback, and this ends that
        // too: the next press starts the recording the bar is showing, from its beginning.
        pendingSeek = null
        positionSec = 0.0
        val running = playback ?: return null
        playback = null
        tearingDown = running
        playing = false
        return running
    }

    /** The teardown to wait on when this caller was not the one that started it. */
    @Synchronized
    private fun pending(): Playback? = tearingDown

    /** STOPPING → IDLE, once the thread the caller was waiting on is gone. */
    @Synchronized
    private fun settle(finished: Playback) {
        if (tearingDown === finished) tearingDown = null
    }

    /**
     * The end of the last part: the thread's own way out, which is a [stop] with no caller — and
     * with nothing to wait for, since what [stop] waits for is this thread. PLAYING → IDLE, never
     * through STOPPING: a teardown nobody is awaiting would leave the field set for ever.
     *
     * A playback that is no longer the one has already been detached by a [stop], and that caller's
     * join is what clears the field.
     */
    @Synchronized
    private fun ended(finished: Playback) {
        if (playback !== finished) return
        playback = null
        playing = false
        positionSec = 0.0
    }

    /**
     * The clock, moved by the decoding thread. Snapshot state is safe to write from any thread, but
     * *which* playback is writing is not: a chunk handed over just as [stop] ran would leave the bar
     * of the next recording holding the last one's seconds. Under the same lock as [stop], a write
     * from a playback that is no longer the one is simply dropped.
     */
    @Synchronized
    private fun at(playback: Playback, seconds: Double) {
        if (this.playback !== playback) return
        positionSec = seconds
    }

    /**
     * One run through one selection, on a thread of its own: an ffmpeg per part, and a single line
     * open across all of them — the parts have the same format, so nothing is heard between two of
     * them.
     *
     * Pause **keeps the ffmpeg process alive** and simply stops taking its stdout: the pipe fills,
     * ffmpeg blocks on its write, and resuming carries on from the exact byte the speaker stopped
     * at. Re-spawning with `-ss` at the paused offset would have to seek an AAC stream to a byte
     * boundary the clock agrees with, and would drop whatever the line had already buffered.
     *
     * A [seek] is the one thing that does re-spawn: there the point *is* to leave where the pipe
     * is, and `-ss` on the way back in is what makes another second of the recording playable at
     * all ([jumpTo]).
     *
     * @param from the part and the offset into it this run starts at — (0, 0.0) for a press of
     *   Play, and wherever a scrub left the playhead for a press after one.
     */
    private inner class Playback(
        val selection: RecordingPlaylist.Selection,
        private val from: Pair<Int, Double>,
    ) {

        @Volatile private var cancelled = false

        @Volatile private var paused = false

        /**
         * Where a [seek] wants this run to carry on from, read by the decode loop between writes:
         * the scrub is over within a poll interval, and the UI thread never waits for ffmpeg.
         */
        @Volatile private var jump: Pair<Int, Double>? = null

        @Volatile private var line: SourceDataLine? = null

        @Volatile private var process: Process? = null

        private val thread = Thread({ run() }, "recly-player").apply { isDaemon = true }

        fun start() = thread.start()

        fun pause() {
            paused = true
            // Stops the speaker without discarding what is already queued for it, so the resume
            // does not lose the tail the decoder was ahead by.
            line?.let { runCatching { it.stop() } }
        }

        fun resume() {
            line?.let { runCatching { it.start() } }
            paused = false
        }

        /**
         * The scrub, from the thread that took the press: the decoder is killed where it is, so its
         * read ends now rather than at the end of the part, and the speaker is emptied so what is
         * heard is the new second and not half a buffer of the old one. A paused line is left
         * stopped — a seek is not a press of Play.
         */
        fun jumpTo(target: Pair<Int, Double>) {
            jump = target
            process?.destroyForcibly()
            line?.let { runCatching { it.stop(); it.flush(); if (!paused) it.start() } }
        }

        fun cancel() {
            cancelled = true
            paused = false
            // The decoder is either blocked reading this or blocked writing to it; killing it ends
            // both. The line is emptied so the speaker goes quiet now rather than a buffer later,
            // and the thread closes it as it unwinds.
            process?.destroyForcibly()
            line?.let { runCatching { it.stop(); it.flush() } }
        }

        /**
         * Until the decoding thread is gone, which is the whole teardown: it reaps ffmpeg and
         * closes the line as it unwinds. Bounded, because a caller left waiting for ever on a
         * thread that will not die is worse than one that hears about it — so the bound running out
         * is said in the log *and* handed back, and what the caller does with it is [stop]'s
         * contract.
         *
         * Safe from more than one caller: [Thread.join] is, and the answer is read from the thread
         * rather than kept, so both get the same one.
         */
        fun await(): Boolean {
            try {
                thread.join(teardownWaitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                logger()?.log(Logger.Level.WARN, "shell.play.stop.slow")
                return false
            }
            return true
        }

        private fun run() {
            var opened: SourceDataLine? = null
            try {
                opened = speaker().apply {
                    open(FORMAT, BUFFER_BYTES)
                    start()
                }
                line = opened
                var index = from.first
                var offsetSec = from.second
                while (!cancelled) {
                    while (index < selection.paths.size && !cancelled) {
                        decode(selection.paths[index], offsetSec, opened) { bytes ->
                            at(this, position(selection.durations, index, offsetSec, bytes))
                        }
                        // A part that ended because the playhead was dragged elsewhere is not a
                        // part that finished: the run carries on wherever the scrub put it instead.
                        val jumped = took(opened)
                        if (jumped != null) {
                            index = jumped.first
                            offsetSec = jumped.second
                            continue
                        }
                        index += 1
                        offsetSec = 0.0
                        at(this, position(selection.durations, index, 0.0, 0))
                    }
                    if (cancelled) break
                    // The last part is still in the speaker's buffer when its decoder ends.
                    opened.drain()
                    // And a scrub that landed while it was emptying is still a scrub: without this
                    // the run would end here and [ended] would put the clock back to zero, on a bar
                    // whose playhead is where the finger just left it.
                    val jumped = took(opened) ?: break
                    index = jumped.first
                    offsetSec = jumped.second
                }
            } catch (e: Throwable) {
                logger()?.log(Logger.Level.ERROR, "shell.play.failed", error = e)
            } finally {
                opened?.let { runCatching { it.stop(); it.flush(); it.close() } }
                line = null
                ended(this)
            }
        }

        /**
         * The scrub this run stopped for, if there was one, taken so it is followed once — and the
         * speaker emptied a second time on the way. [jumpTo] flushed it from the pressing thread,
         * but the decoder may have been inside a write of the chunk it had already read: that chunk
         * is a moment of the part being left behind, and without this it would be the first thing
         * heard after the jump.
         */
        private fun took(out: SourceDataLine): Pair<Int, Double>? {
            val jumped = jump ?: return null
            jump = null
            runCatching { out.stop(); out.flush(); if (!paused) out.start() }
            return jumped
        }

        /**
         * One part from [seekSec] into it, decoded straight into the speaker. [onBytes] is the PCM
         * handed over so far, which is the offset's own seconds and not the part's.
         */
        private fun decode(path: Path, seekSec: Double, out: SourceDataLine, onBytes: (Long) -> Unit) {
            val decoder = spawn(path, seekSec)
            process = decoder
            try {
                decoder.outputStream.close()
                val buffer = ByteArray(CHUNK_BYTES)
                var written = 0L
                decoder.inputStream.use { pcm ->
                    while (!cancelled && jump == null) {
                        if (paused) {
                            idle()
                            continue
                        }
                        val read = pcm.read(buffer)
                        if (read < 0) break
                        var offset = 0
                        while (offset < read && !cancelled && jump == null) {
                            if (paused) {
                                idle()
                                continue
                            }
                            // The loop's own condition is the guard on this write: a scrub that
                            // arrives mid-chunk stops the rest of it here rather than playing the
                            // part being left behind out to the end of the buffer. What it cannot
                            // take back is the one write already in flight, which is why [took]
                            // empties the line again before the next part starts.
                            //
                            // Only as much as fits: a blocking write could not be interrupted by a
                            // pause or by the window closing.
                            val room = out.available()
                            if (room <= 0) {
                                idle()
                                continue
                            }
                            val n = out.write(buffer, offset, minOf(room, read - offset))
                            offset += n
                            written += n
                            onBytes(written)
                        }
                    }
                }
            } finally {
                decoder.destroyForcibly()
                decoder.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
                process = null
            }
        }

        private fun idle() = Thread.sleep(POLL_MS)
    }

    /** Where a [seek] with nothing playing left the playhead, for the [play] that comes after it. */
    private data class Pending(
        val selection: RecordingPlaylist.Selection,
        val index: Int,
        val offsetSec: Double,
    )

    /**
     * One selection's peaks, on a thread of its own: every part is decoded end to end, which is why
     * this is not something the bar can be kept waiting for.
     *
     * A decode that could not read a part leaves the waveform empty rather than drawing a shape
     * that is not the recording's — the bar keeps its baseline, and the failure is in the log.
     */
    private inner class WaveformDecode(val selection: RecordingPlaylist.Selection) {

        @Volatile private var cancelled = false

        /** The part being read, so that [cancel] is a teardown and not only a request to stop. */
        @Volatile private var process: Process? = null

        private val thread = Thread({ run() }, "recly-waveform").apply { isDaemon = true }

        fun start() = thread.start()

        /**
         * The flag *and* the process: the decode checks the flag between reads, but a read that is
         * waiting on a pipe ffmpeg has stopped filling would notice it only when the part ended.
         * Killing the decoder ends the read now, which is what a delete is waiting for.
         */
        fun cancel() {
            cancelled = true
            process?.destroyForcibly()
        }

        /**
         * Until the thread is gone, bounded, and the answer handed back — all for the same reasons
         * as [Playback.await]: what waits on this is a delete, and a bound that ran out is a file
         * still open rather than a slow one.
         */
        fun await(): Boolean {
            try {
                thread.join(teardownWaitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                logger()?.log(Logger.Level.WARN, "shell.play.waveform.stop.slow")
                return false
            }
            return true
        }

        private fun run() {
            try {
                val peaks = RecordingWaveform.peaks(
                    selection,
                    spawn,
                    cancelled = { cancelled },
                    onProcess = { process = it },
                )
                if (!cancelled) drew(this, peaks)
            } catch (e: Throwable) {
                logger()?.log(Logger.Level.ERROR, "shell.play.waveform.failed", error = e)
            }
        }
    }

    companion object {

        /**
         * Where the recording's own clock is: the parts already finished, on the seconds
         * `meta.json` recorded, plus where the current part was started at ([offsetSec], which is
         * 0 for every part a scrub did not land in), plus the PCM of it that has been handed to the
         * speaker. Pure, because it is the one piece of this worth checking without a file.
         *
         * The transcript below the bar is indexed against `meta.json`'s clock, so the finished
         * parts count in its seconds rather than in the decoder's — a part whose file is a few
         * frames longer than the row says must not walk the two apart.
         */
        fun position(durations: List<Double>, finished: Int, offsetSec: Double, itemBytes: Long): Double =
            durations.take(finished.coerceAtLeast(0)).sum() +
                offsetSec.coerceAtLeast(0.0) +
                itemBytes.coerceAtLeast(0) / BYTES_PER_SEC.toDouble()

        /**
         * The other half of [position], and its inverse: which part a second of the recording is
         * in, and how far into that part. The end of a part is the next part's 0 rather than that
         * part's last instant, so a scrub to a boundary plays on instead of stopping there. Past
         * the end clamps to the end of the last part, which is where [seek]'s own clamp already
         * puts it.
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

        private const val RATE = 16_000
        private const val CHANNELS = 1

        /** 16-bit little-endian mono, which is what `-f s16le` writes. */
        private val FORMAT =
            AudioFormat(AudioFormat.Encoding.PCM_SIGNED, RATE.toFloat(), 16, CHANNELS, 2, RATE.toFloat(), false)

        /** What one second of that is, and so what the elapsed clock divides by. */
        private const val BYTES_PER_SEC = RATE * 2 * CHANNELS

        /**
         * Half a second in the speaker: enough not to stutter, little enough that the clock — which
         * counts what has been handed over — is never a whole second ahead of what is heard.
         */
        private const val BUFFER_BYTES = BYTES_PER_SEC / 2

        private const val CHUNK_BYTES = 4096

        /** How long a paused or full-buffered writer waits before looking again. */
        private const val POLL_MS = 10L

        private const val EXIT_WAIT_MS = 1_000L

        /**
         * What [stop] waits for the decoding thread. Longer than [EXIT_WAIT_MS], which that thread
         * spends reaping ffmpeg on its way out — a shorter wait would return before the very thing
         * it is waiting for.
         */
        private const val TEARDOWN_WAIT_MS = 2_000L
    }
}
