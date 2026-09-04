package recly.core.transcribe

import kotlin.math.round
import kotlinx.serialization.Serializable
import recly.core.model.Part
import recly.core.model.Track

/** `spec/transcript.schema.json`, mirrored 1:1. Times are seconds on the recording's own axis. */
@Serializable
data class Transcript(
    val schema: Int = SCHEMA,
    val recordingId: String,
    val track: Track,
    val language: String,
    val provider: TranscriptProvider,
    val createdAt: String,
    val durationSec: Double,
    val speakers: List<TranscriptSpeaker>,
    val segments: List<TranscriptSegment>,
) {
    companion object {
        const val SCHEMA = 1
    }
}

@Serializable
data class TranscriptProvider(val name: String, val model: String? = null, val jobRef: String? = null)

/** [name] is always null in v1 — user labelling is a follow-up (docs/08). */
@Serializable
data class TranscriptSpeaker(val id: String, val name: String? = null)

@Serializable
data class TranscriptSegment(
    val start: Double,
    val end: Double,
    val speaker: String,
    val text: String,
    /** Only when the provider gives word timings; omitted otherwise. */
    val words: List<TranscriptWord>? = null,
)

@Serializable
data class TranscriptWord(val start: Double, val end: Double, val text: String)

/**
 * Provider output → `transcript.json`.
 *
 * Two things happen here and nowhere else. The times a provider reports are on the *concatenated*
 * file's axis, which starts at zero and has no gaps; the recording's axis is what every other file
 * of the recording uses, so each part's `startOffsetSec` is put back (docs/08 "오디오 준비"). And
 * the provider's speaker labels — `A`/`B`, `1`/`2`, whatever it happens to use — are renamed to
 * `S1, S2, …` in order of first appearance, so a reader never has to know which provider ran.
 */
object TranscriptNormalizer {
    fun normalize(
        recordingId: String,
        track: Track,
        parts: List<Part>,
        result: SttResult,
        diarize: Boolean,
        provider: TranscriptProvider,
        createdAt: String,
        language: String,
    ): Transcript {
        val offsets = offsets(parts)
        val labels = LinkedHashMap<String, String>()
        val segments = result.segments.map { segment ->
            val speaker = if (diarize) {
                labels.getOrPut(segment.speaker ?: "") { "S${labels.size + 1}" }
            } else {
                FIRST_SPEAKER
            }
            TranscriptSegment(
                start = offsets.shift(segment.start),
                end = offsets.shift(segment.end),
                speaker = speaker,
                text = segment.text,
                words = segment.words?.map {
                    TranscriptWord(offsets.shift(it.start), offsets.shift(it.end), it.text)
                },
            )
        }
        val speakers = labels.values.map { TranscriptSpeaker(it) }.ifEmpty { listOf(TranscriptSpeaker(FIRST_SPEAKER)) }
        return Transcript(
            recordingId = recordingId,
            track = track,
            language = language,
            provider = provider,
            createdAt = createdAt,
            durationSec = parts.last().let { round3(it.startOffsetSec + it.durationSec) },
            speakers = speakers,
            segments = segments,
        )
    }

    /**
     * `[HH:MM:SS] S1: text`, one line per speaker turn — but never longer than [LINE_SEC] of
     * speech, so a monologue is still readable and an LLM sees timestamps throughout (docs/08).
     */
    fun text(transcript: Transcript): String = buildString {
        var lineSpeaker: String? = null
        var lineStart = 0.0
        transcript.segments.forEach { segment ->
            val newLine = segment.speaker != lineSpeaker || segment.end - lineStart > LINE_SEC
            if (newLine) {
                if (lineSpeaker != null) append('\n')
                lineSpeaker = segment.speaker
                lineStart = segment.start
                append("[${clock(segment.start)}] ${segment.speaker}: ")
            } else {
                append(' ')
            }
            append(segment.text.trim())
        }
        if (isNotEmpty()) append('\n')
    }

    /** `01:02:03` — hours are not wrapped at 24, a recording is not a clock. */
    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        return listOf(total / 3600, (total % 3600) / 60, total % 60)
            .joinToString(":") { it.toString().padStart(2, '0') }
    }

    private fun offsets(parts: List<Part>): Offsets {
        var concatStart = 0.0
        return Offsets(
            parts.map {
                val span = Span(concatStart, concatStart + it.durationSec, it.startOffsetSec - concatStart)
                concatStart += it.durationSec
                span
            },
        )
    }

    private fun round3(value: Double): Double = round(value * 1000) / 1000

    private class Span(val start: Double, val end: Double, val delta: Double)

    private class Offsets(private val spans: List<Span>) {
        /** A time past the last part's end belongs to that part: the provider heard it there. */
        fun shift(time: Double): Double {
            val span = spans.firstOrNull { time < it.end } ?: spans.last()
            return round3(time + span.delta)
        }
    }

    private const val FIRST_SPEAKER = "S1"
    private const val LINE_SEC = 60.0
}
