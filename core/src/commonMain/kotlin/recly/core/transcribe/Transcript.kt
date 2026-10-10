package recly.core.transcribe

import kotlin.math.round
import kotlinx.serialization.Serializable
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.Track
import recly.core.model.recJson

/** `spec/transcript.schema.json`, mirrored 1:1. Times are seconds on the recording's own axis. */
@Serializable
data class Transcript(
    val schema: Int = SCHEMA,
    val recordingId: String,
    val track: Track,
    val language: String,
    val provider: TranscriptProvider,
    val createdAt: String,
    /** Set by every edit made in the app — text, speaker names, segment speakers (docs/08 "Editing"). */
    val editedAt: String? = null,
    val durationSec: Double,
    val speakers: List<TranscriptSpeaker>,
    val segments: List<TranscriptSegment>,
    /** v2: "unavailable" means no speaker identification was performed. Empty speaker IDs are unknown. */
    val speakerIdentification: String? = null,
    /** v2: timing supplied by the engine, never fabricated word alignment. */
    val timing: String? = null,
) {
    companion object {
        const val SCHEMA = 1
        const val LOCAL_SCHEMA = 2
    }
}

@Serializable
data class TranscriptProvider(val name: String, val model: String? = null, val jobRef: String? = null)

/**
 * [name] is what the user called the speaker, or null until somebody does (docs/08 "Editing"). [me] is true for
 * the person who made the recording, told apart on a desktop by its microphone track (docs/08 "Me and others");
 * absent otherwise.
 */
@Serializable
data class TranscriptSpeaker(val id: String, val name: String? = null, val me: Boolean? = null)

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
 * of the recording uses, so each part's `startOffsetSec` is put back (docs/08 "Audio preparation"). And
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
     * speech, so a monologue is still readable and an LLM sees timestamps throughout (docs/08). A
     * speaker the user named is written by that name instead of the id (`[00:00:01] Minsu: text`).
     * Highlights stay out of it: agents parse these lines, and the marks are in `meta.json`.
     */
    fun text(transcript: Transcript): String = buildString {
        val labels = labels(transcript)
        var lineSpeaker: String? = null
        var lineStart = 0.0
        transcript.segments.forEach { segment ->
            val newLine = segment.speaker != lineSpeaker || segment.end - lineStart > LINE_SEC
            if (newLine) {
                if (lineSpeaker != null) append('\n')
                lineSpeaker = segment.speaker
                lineStart = segment.start
                append("[${clock(segment.start)}] ")
                if (segment.speaker.isNotEmpty()) append("${labels[segment.speaker] ?: segment.speaker}: ")
            } else {
                append(' ')
            }
            append(segment.text.trim())
        }
        if (isNotEmpty()) append('\n')
    }

    /**
     * `{base}.transcript.md` (docs/08 "Result files"): the lines of [text], one paragraph each — Markdown
     * runs lines together unless a blank line parts them — under front matter with the recording's
     * title, id and start, so a notes app such as Obsidian opens it as a note. The title is a JSON
     * string, which YAML reads as a double-quoted scalar whatever it holds.
     *
     * A recording with highlights (docs/03 "Metadata") also lists them: their clock times in the front
     * matter, and a "Highlights" section before the lines — each time with the words being said then.
     *
     * The share sheet's copy also carries the recording's [summary] (docs/08 "Exports"), as a "Summary" section
     * before a "Transcript" one; the file in the recording's folder is written without it.
     */
    fun markdown(transcript: Transcript, meta: RecordingMeta, summary: String? = null): String = buildString {
        append("---\n")
        meta.title?.takeIf { it.isNotBlank() }?.let { append("title: ").append(recJson.encodeToString(it)).append('\n') }
        append("recordingId: ").append(meta.recordingId).append('\n')
        append("startedAt: ").append(meta.startedAt).append('\n')
        if (meta.highlights.isNotEmpty()) {
            // Quoted: YAML 1.1 reads a bare 00:02:05 as a base-60 number.
            append("highlights:\n")
            meta.highlights.forEach { append("  - \"").append(clock(it.atSec)).append("\"\n") }
        }
        append("---\n")
        if (meta.highlights.isNotEmpty()) {
            append("\n## Highlights\n\n")
            meta.highlights.forEach { highlight ->
                append("- ").append(clock(highlight.atSec))
                spokenAt(transcript, highlight.atSec)?.let { append(" — ").append(it) }
                append('\n')
            }
        }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
            append("\n## Summary\n\n").append(markdownLines(notes)).append('\n')
            append("\n## Transcript\n")
        }
        val lines = text(transcript).trimEnd('\n')
        if (lines.isNotEmpty()) append('\n').append(lines.replace("\n", "\n\n")).append('\n')
    }

    /** A summary's plain lines as Markdown: "- " items stay one list, every other line is a paragraph of its own. */
    private fun markdownLines(text: String): String {
        val out = StringBuilder()
        var inList = false
        for (line in text.lines().map(String::trimEnd).filter(String::isNotBlank)) {
            val item = line.trimStart().startsWith("- ")
            if (out.isNotEmpty()) out.append(if (item && inList) "\n" else "\n\n")
            out.append(line)
            inList = item
        }
        return out.toString()
    }

    /**
     * SubRip subtitles for the share sheet (docs/08 "Exports"): one cue per segment, the speaker's name or
     * id in front when speakers were identified. A segment longer than [CUE_SEC] or [CUE_CHARS] is cut
     * at its word timings when it has them, and stays one cue when it does not.
     */
    fun srt(transcript: Transcript): String = buildString {
        cues(transcript).forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(cueTime(cue.start, ',')).append(" --> ").append(cueTime(cue.end, ',')).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    /** WebVTT, the same cues as [srt]. */
    fun vtt(transcript: Transcript): String = buildString {
        append("WEBVTT\n\n")
        cues(transcript).forEach { cue ->
            append(cueTime(cue.start, '.')).append(" --> ").append(cueTime(cue.end, '.')).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    /**
     * What each speaker id is written as: the name the user gave it, else [ME] for the person who made the
     * recording, else the id. [ME] is a fixed word, like the ids, for the agents that read these lines; the
     * shells show it in the app's language (docs/08 "Me and others").
     */
    private fun labels(transcript: Transcript): Map<String, String> =
        transcript.speakers.associate { it.id to (it.name?.trim()?.takeIf(String::isNotEmpty) ?: if (it.me == true) ME else it.id) }

    /** The label [text] writes for the person who made the recording when they have not named themselves. */
    const val ME: String = "Me"

    /** The words of the segment playing at [atSec], or of the last one before it. */
    internal fun spokenAt(transcript: Transcript, atSec: Double): String? {
        val spoken = transcript.segments.filter { it.text.isNotBlank() }
        val segment = spoken.firstOrNull { atSec >= it.start && atSec < it.end }
            ?: spoken.lastOrNull { it.start <= atSec }
            ?: return null
        return segment.text.trim()
    }

    private class Cue(val start: Double, val end: Double, val text: String)

    private fun cues(transcript: Transcript): List<Cue> {
        val labels = labels(transcript)
        val identified = transcript.speakers.isNotEmpty()
        return transcript.segments.filter { it.text.isNotBlank() }.flatMap { segment ->
            val prefix = if (identified && segment.speaker.isNotEmpty()) "${labels[segment.speaker] ?: segment.speaker}: " else ""
            pieces(segment).map { (start, end, text) -> Cue(start, maxOf(start, end), prefix + text) }
        }
    }

    /** One segment as cue-sized pieces, cut between words where it has word timings. */
    private fun pieces(segment: TranscriptSegment): List<Triple<Double, Double, String>> {
        val text = segment.text.trim()
        val words = segment.words?.filter { it.text.isNotBlank() }.orEmpty()
        if (words.isEmpty() || (segment.end - segment.start <= CUE_SEC && text.length <= CUE_CHARS)) {
            return listOf(Triple(segment.start, segment.end, text))
        }
        // A segment written without spaces (Japanese, Chinese) is joined back the same way.
        val separator = if (' ' in text) " " else ""
        val pieces = mutableListOf<Triple<Double, Double, String>>()
        var start = words.first().start
        var end = start
        val line = StringBuilder()
        for (word in words) {
            val next = word.text.trim()
            val longer = line.length + separator.length + next.length > CUE_CHARS || word.end - start > CUE_SEC
            if (line.isNotEmpty() && longer) {
                pieces += Triple(start, end, line.toString())
                line.clear()
                start = word.start
            }
            if (line.isNotEmpty()) line.append(separator)
            line.append(next)
            end = word.end
        }
        if (line.isNotEmpty()) pieces += Triple(start, end, line.toString())
        return pieces
    }

    /** `01:02:03,456` (SubRip) or `01:02:03.456` (WebVTT); hours are not wrapped. */
    private fun cueTime(seconds: Double, decimal: Char): String {
        val millis = round(seconds.coerceAtLeast(0.0) * 1000).toLong()
        val clock = listOf(millis / 3_600_000, (millis % 3_600_000) / 60_000, (millis % 60_000) / 1000)
            .joinToString(":") { it.toString().padStart(2, '0') }
        return clock + decimal + (millis % 1000).toString().padStart(3, '0')
    }

    /** `01:02:03` — hours are not wrapped at 24, a recording is not a clock. */
    internal fun clock(seconds: Double): String {
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

    /** The longest cue the subtitle exports make out of a segment with word timings. */
    private const val CUE_SEC = 7.0
    private const val CUE_CHARS = 84
}
