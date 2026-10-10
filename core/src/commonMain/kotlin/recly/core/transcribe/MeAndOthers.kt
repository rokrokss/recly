package recly.core.transcribe

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import okio.Path
import recly.core.model.RecordingMeta
import recly.core.model.Track
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.SilenceRanges
import recly.core.recording.WaveformPeaks

/**
 * docs/08 "Me and others": on a desktop recording, the person who made it is the voice in the `mic` track while
 * the `sys` track — everyone on the call — is quiet. The two tracks are on scales of their own (microphone gain,
 * system volume, echo cancellation or none), so loudness is never compared across them; only whether each one is
 * sounding, by the same rule "Skip silence" uses ([SilenceRanges]).
 *
 * A transcript whose speakers were identified keeps them: the one speaker whose speech is mostly the user's
 * becomes [TranscriptSpeaker.me] — never two, and nobody is split. A transcript without speakers gets two, the
 * user's lines and everyone else's. Nothing changes when one track is silent throughout — a room recorded in a
 * desktop's meeting mode has no call to tell apart — or when either track's parts are not on this device.
 */
internal object MeAndOthers {
    const val WINDOW_SEC: Double = WaveformPeaks.WINDOW_SEC

    /** A speaker is the user when this much of the speech that could be told apart is theirs. */
    private const val SPEAKER_SHARE = 0.7

    /** A line is the user's when this much of it is. */
    private const val LINE_SHARE = 0.6

    /** Less of the user's speech than this in a speaker is not enough to call them the user. */
    private const val MIN_SPEAKER_SEC = 3.0

    /** [transcript] with the user marked, from the recording's `mic` and `sys` parts in [dir]; as it was when it cannot be. */
    suspend fun mark(deps: CoreDeps, dir: Path, meta: RecordingMeta, transcript: Transcript): Transcript {
        if (Track.MIC !in meta.tracks || Track.SYS !in meta.tracks) return transcript
        val mic = levels(deps, dir, meta, Track.MIC)
        val sys = levels(deps, dir, meta, Track.SYS)
        if (mic == null || sys == null) {
            deps.logger.log(Logger.Level.INFO, "transcribe.me.skipped", mapOf("recordingId" to meta.recordingId, "reason" to "levels"))
            return transcript
        }
        val marked = assign(transcript, mic, sys, WINDOW_SEC)
        deps.logger.log(
            Logger.Level.INFO, "transcribe.me",
            mapOf("recordingId" to meta.recordingId, "found" to (marked.speakers.any { it.me == true })),
        )
        return marked
    }

    /** One track's levels on the recording's own axis, its parts placed at their offsets; null when any part is missing or unreadable. */
    private suspend fun levels(deps: CoreDeps, dir: Path, meta: RecordingMeta, track: Track): List<Float>? {
        val parts = meta.parts.filter { it.track == track }.sortedBy { it.part }
        if (parts.isEmpty()) return null
        val total = parts.maxOf { it.startOffsetSec + it.durationSec }
        val out = FloatArray(ceil(total / WINDOW_SEC).toInt())
        for (part in parts) {
            val file = dir / part.file
            if (!deps.fileSystem.exists(file)) return null
            val peaks = try {
                deps.audio.levels(file, WINDOW_SEC)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.logger.log(Logger.Level.WARN, "transcribe.me.levels.failed", mapOf("recordingId" to meta.recordingId), e)
                null
            } ?: return null
            val first = floor(part.startOffsetSec / WINDOW_SEC).toInt()
            peaks.forEachIndexed { index, peak ->
                val at = first + index
                if (at in out.indices) out[at] = max(out[at], peak)
            }
        }
        return out.toList()
    }

    /** The marking itself, on two tracks' levels in [windowSec] windows. */
    fun assign(transcript: Transcript, mic: List<Float>, sys: List<Float>, windowSec: Double): Transcript {
        val micOn = sounding(mic)
        val sysOn = sounding(sys)
        if (micOn.none { it } || sysOn.none { it }) return transcript
        // Per window: +1 the user alone, -1 the call, 0 neither or both.
        val side = IntArray(max(micOn.size, sysOn.size)) { index ->
            val m = micOn.getOrElse(index) { false }
            val s = sysOn.getOrElse(index) { false }
            when {
                m && !s -> 1
                s -> -1
                else -> 0
            }
        }
        fun share(segment: TranscriptSegment): Pair<Double, Double> {
            val from = floor(segment.start / windowSec).toInt().coerceAtLeast(0)
            val to = min(ceil(segment.end / windowSec).toInt(), side.size)
            var mine = 0
            var theirs = 0
            for (i in from until to) when (side[i]) {
                1 -> mine++
                -1 -> theirs++
            }
            return mine * windowSec to theirs * windowSec
        }
        val spoken = transcript.segments.filter { it.text.isNotBlank() }
        val speakers = spoken.map { it.speaker }.filter { it.isNotEmpty() }.distinct()
        return if (speakers.size >= 2) bySpeaker(transcript, spoken, ::share) else byLine(transcript, ::share)
    }

    private fun bySpeaker(transcript: Transcript, spoken: List<TranscriptSegment>, share: (TranscriptSegment) -> Pair<Double, Double>): Transcript {
        val totals = LinkedHashMap<String, Pair<Double, Double>>()
        for (segment in spoken) {
            if (segment.speaker.isEmpty()) continue
            val (mine, theirs) = share(segment)
            val (m, t) = totals[segment.speaker] ?: (0.0 to 0.0)
            totals[segment.speaker] = (m + mine) to (t + theirs)
        }
        val me = totals.entries
            .filter { (_, v) -> v.first >= MIN_SPEAKER_SEC && v.first / (v.first + v.second) >= SPEAKER_SHARE }
            .maxByOrNull { (_, v) -> v.first / (v.first + v.second) }
            ?.key ?: return transcript
        return transcript.copy(speakers = transcript.speakers.map { if (it.id == me) it.copy(me = true) else it.copy(me = null) })
    }

    /** No speakers to keep: the user's lines and the rest become two, numbered in order of first appearance. */
    private fun byLine(transcript: Transcript, share: (TranscriptSegment) -> Pair<Double, Double>): Transcript {
        val mine = transcript.segments.map { segment ->
            val (m, t) = share(segment)
            m > 0 && m / (m + t) >= LINE_SHARE
        }
        if (mine.none { it }) return transcript
        val ids = LinkedHashMap<Boolean, String>()
        val segments = transcript.segments.mapIndexed { index, segment ->
            segment.copy(speaker = ids.getOrPut(mine[index]) { "S${ids.size + 1}" })
        }
        val speakers = ids.map { (me, id) -> TranscriptSpeaker(id, me = if (me) true else null) }
        return transcript.copy(
            speakers = speakers,
            segments = segments,
            speakerIdentification = transcript.speakerIdentification?.let { "identified" },
        )
    }

    /** Which windows of one track are sounding: at or above its own silence threshold ([SilenceRanges]). */
    private fun sounding(peaks: List<Float>): List<Boolean> {
        val threshold = SilenceRanges.threshold(peaks)
        return peaks.map { it >= threshold && it > 0f }
    }
}
