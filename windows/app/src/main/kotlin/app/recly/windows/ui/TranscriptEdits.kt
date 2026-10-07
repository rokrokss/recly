package app.recly.windows.ui

import recly.core.recording.RecordingSearch
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptBlock
import recly.core.transcribe.TranscriptEdit

/**
 * docs/08 "Editing": the core's edit for a transcript as the editor left it — [texts] and [speakers] one per
 * segment, [names] by speaker id. A speaker id the transcript does not have is a new speaker: the core
 * names new speakers itself (`S{highest + 1}`, in the order they are made), so new ids are renumbered
 * that way and each is made by its first segment. Null when nothing changed.
 */
internal fun transcriptEdit(
    original: Transcript,
    texts: List<String>,
    speakers: List<String>,
    names: Map<String, String?> = emptyMap(),
): TranscriptEdit? {
    val known = original.speakers.map { it.id }.toSet()
    val highest = original.speakers.mapNotNull { it.id.removePrefix("S").toIntOrNull() }.maxOrNull() ?: 0
    val made = speakers.filter { it.isNotEmpty() && it !in known }.distinct().sortedBy { it.removePrefix("S").toIntOrNull() ?: Int.MAX_VALUE }
    val renamed = made.withIndex().associate { (index, id) -> id to "S${highest + 1 + index}" }
    val wanted = speakers.map { renamed[it] ?: it }
    val edits = mutableListOf<TranscriptEdit>()
    // What each segment says after the edits so far, as the core will have it.
    val now = original.segments.map { it.speaker }.toMutableList()
    var unidentified = original.speakers.isEmpty()
    renamed.values.forEach { id ->
        val first = wanted.indexOf(id)
        edits += TranscriptEdit.SetSpeaker(first, null)
        // On a transcript nobody was identified in, the first speaker made covers every segment.
        if (unidentified) now.indices.forEach { now[it] = id } else now[first] = id
        unidentified = false
    }
    wanted.forEachIndexed { index, id -> if (id.isNotEmpty() && now[index] != id) edits += TranscriptEdit.SetSpeaker(index, id) }
    names.forEach { (id, name) ->
        val final = renamed[id] ?: id
        val before = original.speakers.firstOrNull { it.id == id }?.name
        if ((name?.trim()?.ifEmpty { null }) != before && final in wanted) edits += TranscriptEdit.RenameSpeaker(final, name?.trim()?.ifEmpty { null })
    }
    texts.forEachIndexed { index, text ->
        if (text.trim() != original.segments[index].text.trim()) edits += TranscriptEdit.SetText(index, text)
    }
    return edits.takeIf { it.isNotEmpty() }?.let(TranscriptEdit::Batch)
}

/** The id a new speaker gets in a draft: one past the highest there is, the way the core numbers them. */
internal fun nextSpeakerId(ids: Collection<String>): String =
    "S${(ids.mapNotNull { it.removePrefix("S").toIntOrNull() }.maxOrNull() ?: 0) + 1}"

/**
 * Which segments each reading block holds. `TranscriptDocument` builds its blocks from the segments in
 * order, so a block runs from the segment it starts at to the one the next block starts at.
 */
internal fun blockSegments(transcript: Transcript, blocks: List<TranscriptBlock>): List<IntRange> {
    val segments = transcript.segments
    var at = 0
    val starts = blocks.map { block ->
        while (at < segments.size && (segments[at].text.isBlank() || segments[at].start.coerceAtLeast(0.0) != block.start)) at++
        at.also { at++ }
    }
    return starts.mapIndexed { index, start -> start until (starts.getOrNull(index + 1) ?: segments.size) }
}

/** One occurrence of a find query in a block's text. */
internal data class FindMatch(val block: Int, val start: Int, val length: Int)

/** docs/10 "Search": every occurrence of [query] in the blocks, folded as the search folds it, in reading order. */
internal fun findMatches(blocks: List<TranscriptBlock>, query: String): List<FindMatch> =
    blocks.flatMapIndexed { index, block -> RecordingSearch.findRanges(block.text, query).map { FindMatch(index, it.offset, it.length) } }

