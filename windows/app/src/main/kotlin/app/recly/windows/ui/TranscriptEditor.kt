package app.recly.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.fieldBox
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/08 "Editing": the transcript as the editor holds it — one text and one speaker per segment, and the
 * names given — until Save sends it as one edit ([transcriptEdit]).
 */
internal class TranscriptDraft(val original: Transcript) {
    val texts = mutableStateListOf(*original.segments.map { it.text }.toTypedArray())
    val speakers = mutableStateListOf(*original.segments.map { it.speaker }.toTypedArray())
    private val names = mutableStateMapOf<String, String?>()

    /** The draft's speakers, the original's first, as the menu lists them. */
    val people: List<TranscriptSpeaker>
        get() = (original.speakers.map { it.id } + speakers).filter { it.isNotEmpty() }.distinct().map(::speakerOf)

    /** Speaker [id] as the draft has it: its name so far, and still the person who made the recording if it was. */
    fun speakerOf(id: String): TranscriptSpeaker = TranscriptSpeaker(id, nameOf(id), original.speakers.firstOrNull { it.id == id }?.me)

    val edit: TranscriptEdit? by derivedStateOf { transcriptEdit(original, texts, speakers, names) }

    fun nameOf(id: String): String? = if (id in names) names[id] else original.speakers.firstOrNull { it.id == id }?.name

    fun rename(id: String, name: String) {
        names[id] = name.trim().ifEmpty { null }
    }

    /**
     * Segment [index] to [id], or to a new speaker. On a transcript nobody was identified in, the first
     * speaker given covers every line, as the core does it; the others are split off after.
     */
    fun assign(index: Int, id: String?) {
        val target = id ?: nextSpeakerId(people.map { it.id })
        if (speakers.all { it.isEmpty() }) speakers.indices.forEach { speakers[it] = target } else speakers[index] = target
    }
}

/**
 * docs/08 "Editing": one plain field per segment, with its time (which seeks; the player stays usable) and its
 * speaker (the speaker menu, on the draft — or `Add speaker` where nobody was identified). Under the
 * lines, what saving does: the files in storage change, and on Drive the agent is not run again.
 */
@Composable
internal fun TranscriptEditor(
    draft: TranscriptDraft,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    onRenameSpeaker: (String) -> Unit,
    drive: Boolean,
    /** False while the edit saves: the speakers wait for it. */
    speakersEnabled: Boolean,
    strings: Strings,
    modifier: Modifier = Modifier,
    /** The recording's length, which picks the format of the times ([LedgerFormat.clock]). */
    spanSec: Double? = draft.original.durationSec,
) {
    val palette = blueprint
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(draft.original.segments.indices.toList(), key = { it }) { index ->
                val segment = draft.original.segments[index]
                val stamp = LedgerFormat.clock(segment.start, spanSec)
                // What a screen reader hears is unchanged: hours always said.
                val spoken = LedgerFormat.elapsed((segment.start * 1000).toLong())
                Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        BlueprintButton(stamp, { onSeek(segment.start) }, enabled = canSeek, tone = ButtonTone.QUIET, monospace = true,
                            modifier = Modifier.semantics { contentDescription = strings[Str.TRANSCRIPT_SEEK, spoken] })
                        val speaker = draft.speakers[index]
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            if (speaker.isEmpty()) {
                                BlueprintButton(strings[Str.SPEAKER_ADD], { draft.assign(index, null) }, tone = ButtonTone.QUIET, enabled = speakersEnabled)
                            } else {
                                SpeakerBadge(speaker, speakerName(draft.speakerOf(speaker), strings), speakersEnabled) { menu = true }
                                if (menu) SpeakerMenu(draft.people, speaker, { menu = false }, { onRenameSpeaker(speaker) }, { draft.assign(index, it) }, strings)
                            }
                        }
                    }
                    BasicTextField(
                        value = draft.texts[index],
                        onValueChange = { draft.texts[index] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = spoken }
                            // The accent while it has the focus, and the direction of its own words (2026-10-10).
                            .fieldBox()
                            .padding(horizontal = Space.s, vertical = Space.s),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text, textDirection = TextDirection.Content),
                        cursorBrush = SolidColor(palette.accent),
                    )
                }
            }
        }
        HairLine()
        Text(
            strings[if (drive) Str.EDIT_NOTE_DRIVE else Str.EDIT_NOTE],
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
    }
}
