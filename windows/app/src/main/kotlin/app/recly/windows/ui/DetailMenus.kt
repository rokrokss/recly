package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintDialogText
import app.recly.windows.ui.component.BlueprintMenu
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.SELECTION_MARK
import app.recly.windows.ui.component.SwitchTrack
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recly.core.processing.TranscriptionMode
import recly.core.recording.ExportFormat
import recly.core.transcribe.TranscriptAvailability
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.TranscriptSpeaker

/** Whether the detail has a transcript to show, edit or export. */
internal val RecordingDetail.hasTranscript: Boolean
    get() = transcript != null && availability != TranscriptAvailability.EMPTY

/** Whether the audio is here, or still may be: only a settled trip that brought nothing back means none. */
private val RecordingDetail.audioReachable: Boolean
    get() = !audio.isEmpty || driveFetch == DriveFetch.DECIDING || driveFetch == DriveFetch.FETCHING

/**
 * docs/08 "Exports": Export… — the five formats, each made by the core and saved under the name it gave the
 * file, and Copy all. While the core prepares one (joining the audio takes a moment) its row says so.
 */
@Composable
internal fun ExportButton(model: ShellModel, detail: RecordingDetail, strings: Strings) {
    var open by remember { mutableStateOf(false) }
    var preparing by remember(detail.recordingId) { mutableStateOf<ExportFormat?>(null) }
    var copied by remember(detail.recordingId) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    Box {
        BlueprintButton(if (copied) "$SELECTION_MARK ${strings[Str.TRANSCRIPT_COPIED]}" else strings[Str.EXPORT], { open = true }, tone = ButtonTone.QUIET)
        BlueprintMenu(open, { if (preparing == null) open = false }, alignment = Alignment.TopEnd) { MenuColumn {
            EXPORTS.forEach { (format, label, kind) ->
                val audio = format == ExportFormat.AUDIO
                val reason = when {
                    audio && !detail.audioReachable -> Str.PLAYER_NO_AUDIO
                    !audio && !detail.hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
                    else -> null
                }
                MenuRow(
                    label = strings[label],
                    onClick = {
                        preparing = format
                        scope.launch {
                            val file = model.exportFile(detail.recordingId, format)
                            preparing = null
                            open = false
                            file?.let { model.saveExport(it) }
                        }
                    },
                    enabled = reason == null && preparing == null,
                    secondary = strings[reason ?: kind],
                    trailing = if (preparing == format) {
                        { LoadingText(strings[Str.EXPORT_PREPARING], MaterialTheme.typography.bodySmall, blueprint.textMuted) }
                    } else {
                        null
                    },
                )
            }
            MenuRow(
                label = strings[Str.TRANSCRIPT_COPY],
                onClick = {
                    detail.transcript?.let { clipboard.setText(AnnotatedString(TranscriptNormalizer.text(it))) }
                    copied = true
                    open = false
                },
                enabled = detail.hasTranscript && preparing == null,
            )
        } }
    }
}

private val EXPORTS = listOf(
    Triple(ExportFormat.TXT, Str.EXPORT_TRANSCRIPT, Str.EXPORT_TRANSCRIPT_FORMAT),
    Triple(ExportFormat.MD, Str.EXPORT_NOTES, Str.EXPORT_NOTES_FORMAT),
    Triple(ExportFormat.SRT, Str.EXPORT_SUBTITLES, Str.EXPORT_SUBTITLES_FORMAT),
    Triple(ExportFormat.VTT, Str.EXPORT_WEB_SUBTITLES, Str.EXPORT_WEB_SUBTITLES_FORMAT),
    Triple(ExportFormat.AUDIO, Str.EXPORT_AUDIO, Str.EXPORT_AUDIO_FORMAT),
)

/**
 * docs/09 "Screen principles": the detail's ⋯ — Rename · Edit transcript · Transcribe again · Add highlight. What
 * cannot run now stays in its place, disabled, with the reason under it.
 */
@Composable
internal fun MoreButton(
    model: ShellModel,
    detail: RecordingDetail,
    positionSec: Double,
    onEdit: () -> Unit,
    strings: Strings,
) {
    var open by remember { mutableStateOf(false) }
    val stamp = LedgerFormat.elapsed((positionSec * 1000).toLong())
    Box {
        BlueprintButton(
            MORE_MARK,
            { open = true },
            modifier = Modifier.semantics { contentDescription = strings[Str.DETAIL_MORE] },
            tone = ButtonTone.QUIET,
        )
        BlueprintMenu(open, { open = false }, alignment = Alignment.TopEnd) { MenuColumn {
            MenuRow(strings[Str.DETAIL_RENAME], { open = false; model.askToRename() }, enabled = !detail.writing)
            val editBlocked = when {
                !detail.hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
                detail.transcribing -> Str.DETAIL_TRANSCRIBING
                else -> null
            }
            MenuRow(strings[Str.DETAIL_EDIT], { open = false; onEdit() }, enabled = editBlocked == null, secondary = editBlocked?.let { strings[it] })
            val againBlocked = when {
                model.processing?.summary?.mode == TranscriptionMode.OFF -> Str.DETAIL_TRANSCRIPTION_OFF
                detail.transcribing -> Str.DETAIL_TRANSCRIBING
                detail.notUploaded -> Str.DETAIL_NOT_UPLOADED
                else -> null
            }
            MenuRow(
                strings[Str.DETAIL_RETRANSCRIBE],
                { open = false; model.askToRetranscribe() },
                enabled = againBlocked == null && !detail.writing,
                secondary = againBlocked?.let { strings[it] },
            )
            MenuRow(
                strings[Str.HIGHLIGHT_ADD_AT, stamp],
                { open = false; model.setHighlights(detail.recordingId, detail.highlights + positionSec) },
                enabled = !detail.audio.isEmpty,
                secondary = if (detail.audio.isEmpty) strings[Str.PLAYER_NO_AUDIO] else null,
            )
        } }
    }
}

/** docs/03 "Metadata": a highlight's own menu — go there, or take it away (not red: no recording is deleted). */
@Composable
internal fun HighlightMenu(atSec: Double, onDismiss: () -> Unit, onGoTo: () -> Unit, onRemove: () -> Unit, strings: Strings) {
    BlueprintMenu(true, onDismiss) { MenuColumn {
        MenuRow(strings[Str.TRANSCRIPT_SEEK, LedgerFormat.elapsed((atSec * 1000).toLong())], { onDismiss(); onGoTo() })
        MenuRow(strings[Str.HIGHLIGHT_REMOVE], { onDismiss(); onRemove() })
    } }
}

/** docs/03 "Metadata": a highlighted moment's mark — a small filled accent square, Blueprint's record-square motif. */
@Composable
internal fun HighlightMark(modifier: Modifier = Modifier) {
    Box(modifier.size(HIGHLIGHT_MARK).background(blueprint.accent).clearAndSetSemantics { })
}

/** The mark's side, on the waveform's ticks and beside a group's time alike. */
internal val HIGHLIGHT_MARK = 6.dp

/**
 * docs/08 "Editing": who says a group — the name the user gave, or the id in monospace — as a quiet badge
 * that opens the speaker menu. Speakers are told apart by this label alone.
 */
@Composable
internal fun SpeakerBadge(id: String, name: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = blueprint
    Text(
        name ?: id,
        modifier = Modifier
            .border(palette.line, palette.textMuted, RoundedCornerShape(Radius.badge))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = if (name != null) MaterialTheme.typography.labelSmall else mono.small,
        color = palette.textMuted,
        maxLines = 1,
    )
}

/**
 * docs/08 "Editing": Rename speaker, or Change speaker for this line — which turns the menu into the list of
 * speakers and New speaker. [current] is "" on a line nobody was identified on.
 */
@Composable
internal fun SpeakerMenu(
    speakers: List<TranscriptSpeaker>,
    current: String,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onChange: (String?) -> Unit,
    strings: Strings,
) {
    var choosing by remember { mutableStateOf(current.isEmpty()) }
    BlueprintMenu(true, onDismiss) { MenuColumn {
        if (!choosing) {
            MenuRow(strings[Str.SPEAKER_RENAME], { onDismiss(); onRename() })
            MenuRow(strings[Str.SPEAKER_CHANGE], { choosing = true })
        } else {
            speakers.forEach { speaker ->
                val label = speaker.name ?: speaker.id
                MenuRow(if (speaker.id == current) "$SELECTION_MARK $label" else label, { onDismiss(); onChange(speaker.id) })
            }
            MenuRow(strings[Str.SPEAKER_NEW], { onDismiss(); onChange(null) })
        }
    } }
}

/** docs/08 "Editing": a speaker's name; saved empty, the id shows again. */
@Composable
internal fun SpeakerNameDialog(
    initial: String,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    BlueprintDialog(
        title = strings[Str.SPEAKER_NAME],
        onDismissRequest = onCancel,
        theme = theme,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.SAVE], { onSave(name) }, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintTextField(name, { name = it }, strings[Str.SPEAKER_NAME_PLACEHOLDER], placeholder = strings[Str.SPEAKER_NAME_PLACEHOLDER], monospace = false)
    }
}

/** docs/08 "Editing": leaving the editor with changes in it. Discard is not red: no recording goes. */
@Composable
internal fun DiscardEditsDialog(
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.EDIT_DISCARD_TITLE],
        onDismissRequest = onKeep,
        theme = theme,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.EDIT_KEEP], onKeep, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.EDIT_DISCARD], onDiscard, tone = ButtonTone.ACCENT)
        },
    ) {
        BlueprintDialogText(strings[Str.EDIT_DISCARD_BODY])
    }
}

/**
 * docs/10 "Re-transcription": the method and the language it will run with now, and — when the transcript
 * was edited or its speakers named — that those edits are replaced.
 */
@Composable
fun RetranscribeDialog(
    request: RetranscribeRequest,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (RetranscribeRequest) -> Unit,
) {
    val method = request.provider ?: strings[Str.RETRANSCRIBE_LOCAL]
    val body = strings[Str.RETRANSCRIBE_BODY, method, transcriptionLanguageLabel(request.language, strings)] +
        if (request.replacesEdits) " " + strings[Str.RETRANSCRIBE_EDITS] else ""
    BlueprintDialog(
        title = strings[Str.RETRANSCRIBE_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.RETRANSCRIBE_CONFIRM], { onConfirm(request) }, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintDialogText(body)
    }
}

/**
 * docs/09 "Screen principles": the speed as a quiet chip (`1.5×`), with a small accent square at its corner while
 * Skip silence is on; its menu has the speeds and the Skip silence switch.
 */
@Composable
internal fun SpeedChip(speed: Float, skipSilence: Boolean, onSpeed: (Float) -> Unit, onSkipSilence: (Boolean) -> Unit, strings: Strings) {
    val palette = blueprint
    var open by remember { mutableStateOf(false) }
    val label = speedLabel(speed)
    Box {
        Box(
            Modifier
                .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
                .border(palette.line, palette.grid, RoundedCornerShape(Radius.node))
                .clickable(role = Role.Button) { open = true }
                .semantics {
                    contentDescription = strings[Str.PLAYER_SPEED]
                    stateDescription = strings[if (skipSilence) Str.PLAYER_SPEED_VALUE_SKIP else Str.PLAYER_SPEED_VALUE, label]
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = mono.small, color = palette.textMuted, modifier = Modifier.padding(horizontal = Space.s))
            if (skipSilence) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(6.dp).background(palette.accent, RoundedCornerShape(1.dp)))
            }
        }
        BlueprintMenu(open, { open = false }) { MenuColumn {
            SPEEDS.forEach { choice ->
                MenuRow(if (choice == speed) "$SELECTION_MARK ${speedLabel(choice)}" else speedLabel(choice), { open = false; onSpeed(choice) })
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MinTouch)
                    .toggleable(value = skipSilence, role = Role.Switch, onValueChange = onSkipSilence)
                    .padding(start = Space.s + Space.xs, end = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings[Str.PLAYER_SKIP_SILENCE], style = MaterialTheme.typography.labelLarge, color = palette.text, modifier = Modifier.weight(1f))
                SwitchTrack(checked = skipSilence)
            }
        } }
    }
}

/**
 * One line of the detail's menus: the label at the start, a quieter [secondary] line under it — what the
 * row makes, or why it cannot run now — and [trailing] at the end. [MinTouch] tall.
 */
@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    secondary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = blueprint
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s + Space.xs, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) palette.text else palette.textMuted)
            secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
        }
        trailing?.invoke()
    }
}

/** A menu as wide as its widest row, so the rows' backgrounds and targets line up. */
@Composable
private fun MenuColumn(content: @Composable () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = MENU_WIDTH)) { content() }
}

/** `1×`, `1.25×`: a number, so not translated. */
internal fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

internal val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** The detail's More button: a mark, with "More" for whoever cannot see it. */
private const val MORE_MARK = "⋯"

private const val COPIED_MS = 3_000L

/** The narrowest a menu is, as [BlueprintMenu] draws it. */
private val MENU_WIDTH = 200.dp
