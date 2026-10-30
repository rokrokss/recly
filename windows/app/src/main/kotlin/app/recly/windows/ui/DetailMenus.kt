package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.jobs.RecentItem
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintDialogText
import app.recly.windows.ui.component.BACK_MARK
import app.recly.windows.ui.component.BlueprintMenu
import app.recly.windows.ui.component.BlueprintMenuColumn
import app.recly.windows.ui.component.BlueprintMenuDivider
import app.recly.windows.ui.component.BlueprintMenuRow
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.CompactControl
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.SELECTION_MARK
import app.recly.windows.ui.component.SwitchTrack
import app.recly.windows.ui.component.focusRing
import app.recly.windows.ui.component.onLabelBaseline
import app.recly.windows.ui.component.pressCursor
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.FillNeeds
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.model.Step
import recly.core.processing.TranscriptionMode
import recly.core.recording.ExportFormat
import recly.core.transcribe.TranscriptAvailability
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.TranscriptSpeaker

/** Whether the detail has a transcript to show, edit or export. */
internal val RecordingDetail.hasTranscript: Boolean
    get() = transcript != null && availability != TranscriptAvailability.EMPTY

/**
 * The recording's whole length, which picks the format of every time drawn on its screen so they all have one
 * width (2026-10-08): the meta's, the audio's, or the transcript's; null when none of them knows.
 */
internal val RecordingDetail.spanSec: Double?
    get() = lengthSec?.takeIf { it > 0 } ?: audio.totalSec.takeIf { it > 0 } ?: transcript?.durationSec?.takeIf { it > 0 }

/**
 * Whether [job] has reached its transcription: the next step it has to run is a `transcribe` or a
 * `local.transcribe`, queued or running. An upload still to happen in front of it is not a transcription yet.
 */
internal fun transcriptionInFlight(job: Job, steps: List<StepRun>): Boolean {
    if (job.status != JobStatus.PENDING && job.status != JobStatus.RUNNING && job.status != JobStatus.WAITING) return false
    // In the workflow's order; a step with no run yet has not started.
    val next = job.workflow?.steps?.firstOrNull { step ->
        val status = steps.firstOrNull { it.stepId == step.id }?.status
        status != StepStatus.SUCCEEDED && status != StepStatus.SKIPPED
    } ?: return false
    val status = steps.firstOrNull { it.stepId == next.id }?.status ?: StepStatus.PENDING
    return (next is Step.Transcribe || next is Step.LocalTranscribe) &&
        (status == StepStatus.RUNNING || status == StepStatus.PENDING)
}

/**
 * The reason More gives for Edit transcript and Transcribe again while a job of the recording is unsettled
 * (2026-10-08). Which items are disabled does not change — a pending job may still rewrite the transcript
 * (docs/08 "Editing") — only what they say: `Transcribing…` while a transcription is really under way, the
 * badge's `Waiting for Drive` for a job parked on the connection, `Not uploaded yet` for an upload still to
 * happen, and `Transcribing…` for whatever else is left.
 */
internal fun busyReason(transcribing: Boolean, waitsForDrive: Boolean, uploaded: Boolean): Str = when {
    transcribing -> Str.DETAIL_TRANSCRIBING
    waitsForDrive -> Str.DRIVE_PENDING
    !uploaded -> Str.DETAIL_NOT_UPLOADED
    else -> Str.DETAIL_TRANSCRIBING
}

/** Whether the audio is here, or still may be: only a settled trip that brought nothing back means none. */
private val RecordingDetail.audioReachable: Boolean
    get() = !audio.isEmpty || driveFetch == DriveFetch.DECIDING || driveFetch == DriveFetch.FETCHING

/**
 * docs/08 "Exports": Export… — the formats, each made by the core and saved under the name it gave the file, and
 * Copy all. While the core prepares one (joining the audio takes a moment) its row says so. The notes are one of
 * them wherever ChatGPT is offered (docs/09 "AI view").
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
        val notes = model.chatGpt?.connection != ChatGptConnection.Unavailable
        BlueprintMenu(open, { if (preparing == null) open = false }, end = true, maxHeight = DETAIL_MENU_HEIGHT) { BlueprintMenuColumn {
            EXPORTS.filter { (format) -> format != ExportFormat.NOTES || notes }.forEach { (format, label, kind) ->
                val audio = format == ExportFormat.AUDIO
                val reason = when {
                    // Nothing of a take still being written is whole enough to export.
                    audio && detail.writing -> Str.DETAIL_STILL_RECORDING
                    audio && !detail.audioReachable -> Str.PLAYER_NO_AUDIO
                    format == ExportFormat.NOTES -> if (detail.notes?.isEmpty != false) Str.NOTES_NONE else null
                    !audio && !detail.hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
                    else -> null
                }
                BlueprintMenuRow(
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
            BlueprintMenuRow(
                label = strings[Str.TRANSCRIPT_COPY],
                onClick = {
                    detail.transcript?.let { clipboard.setText(AnnotatedString(TranscriptNormalizer.text(it))) }
                    copied = true
                    open = false
                },
                enabled = detail.hasTranscript && preparing == null,
                // A disabled item says why (2026-10-08).
                secondary = if (detail.hasTranscript) null else strings[Str.DETAIL_NO_TRANSCRIPT],
            )
        } }
    }
}

private val EXPORTS = listOf(
    Triple(ExportFormat.TXT, Str.EXPORT_TRANSCRIPT, Str.EXPORT_TRANSCRIPT_FORMAT),
    Triple(ExportFormat.MD, Str.EXPORT_NOTES, Str.EXPORT_NOTES_FORMAT),
    // The notes as text, under the chip's own name; the .md carries them too (docs/08 "Exports").
    Triple(ExportFormat.NOTES, Str.NOTES_TAB, Str.EXPORT_TRANSCRIPT_FORMAT),
    Triple(ExportFormat.SRT, Str.EXPORT_SUBTITLES, Str.EXPORT_SUBTITLES_FORMAT),
    Triple(ExportFormat.VTT, Str.EXPORT_WEB_SUBTITLES, Str.EXPORT_WEB_SUBTITLES_FORMAT),
    Triple(ExportFormat.AUDIO, Str.EXPORT_AUDIO, Str.EXPORT_AUDIO_FORMAT),
)

/**
 * docs/09 "Screen principles": the detail's ⋯, in groups with a hairline between them (2026-10-10) — Rename ·
 * Edit transcript · Transcribe again · Memo while the recording has none (docs/08 "Memo"), then Organize (or Organize
 * again) · Edit notes · Ask about this recording… · Fill in title · Fill in speaker names where ChatGPT is offered
 * (docs/09 "AI view"), then Open in Drive or Open the folder once the recording has a folder there (D24), and last
 * Delete, in red (2026-10-15). What cannot run now stays in its place, disabled, with the reason under it — `Still
 * recording` for every one of them but Memo while the take is being written.
 */
@Composable
internal fun MoreButton(
    model: ShellModel,
    detail: RecordingDetail,
    onEdit: () -> Unit,
    onEditNotes: () -> Unit,
    strings: Strings,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        BlueprintButton(
            MORE_MARK,
            { open = true },
            modifier = Modifier.semantics { contentDescription = strings[Str.DETAIL_MORE] },
            tone = ButtonTone.QUIET,
            // A mark, not a word (docs/09 "Shape").
            minWidth = MinTouch,
        )
        BlueprintMenu(open, { open = false }, end = true, maxHeight = DETAIL_MENU_HEIGHT) { BlueprintMenuColumn {
            // A take still being written: every item waits for it, and says so (2026-10-10).
            val recording = Str.DETAIL_STILL_RECORDING.takeIf { detail.writing }
            // The core refuses to rename a take still being written, and the item says so.
            BlueprintMenuRow(
                strings[Str.DETAIL_RENAME],
                { open = false; model.askToRename() },
                enabled = recording == null,
                secondary = recording?.let { strings[it] },
            )
            val editBlocked = recording ?: when {
                !detail.hasTranscript -> Str.DETAIL_NO_TRANSCRIPT
                detail.transcribing -> detail.busyReason
                else -> null
            }
            BlueprintMenuRow(strings[Str.DETAIL_EDIT], { open = false; onEdit() }, enabled = editBlocked == null, secondary = editBlocked?.let { strings[it] })
            val againBlocked = recording ?: when {
                model.processing?.summary?.mode == TranscriptionMode.OFF -> Str.DETAIL_TRANSCRIPTION_OFF
                detail.transcribing -> detail.busyReason
                detail.notUploaded -> Str.DETAIL_NOT_UPLOADED
                else -> null
            }
            BlueprintMenuRow(
                strings[Str.DETAIL_RETRANSCRIBE],
                { open = false; model.askToRetranscribe() },
                enabled = againBlocked == null,
                secondary = againBlocked?.let { strings[it] },
            )
            // docs/08 "Memo": where a recording without one gets its memo — the Memo chip once there is one. A memo can be
            // written while it records, so nothing waits here.
            if (detail.memo != null && !hasMemo(detail.memo)) {
                BlueprintMenuRow(strings[Str.MEMO_TITLE], { open = false; model.showMemo() })
            }
            // docs/09 "AI view": asked for here, one recording at a time; not offered where ChatGPT is not — and then
            // the group and its hairline are not there at all.
            val connection = model.chatGpt?.connection
            if (connection != null && connection != ChatGptConnection.Unavailable) {
                BlueprintMenuDivider()
                // Any job of it not settled yet, with that job's reason, as Edit transcript says it (D16).
                val busy = detail.busyReason.takeIf { detail.transcribing }
                val notes = detail.notes
                val organizeBlocked = organizeBlocked(detail.writing, detail.hasTranscript, busy, connection, notes?.organizing == true)
                BlueprintMenuRow(
                    strings[organizeLabel(notes)],
                    { open = false; model.organize() },
                    enabled = organizeBlocked == null,
                    secondary = organizeBlocked?.let { strings[it] },
                )
                // Only while there are notes to edit.
                if (notes != null && !notes.isEmpty) {
                    val editNotesBlocked = editNotesBlocked(notes, detail.writing)
                    BlueprintMenuRow(
                        strings[Str.NOTES_EDIT],
                        { open = false; onEditNotes() },
                        enabled = editNotesBlocked == null,
                        secondary = editNotesBlocked?.let { strings[it] },
                    )
                }
                // docs/08 "Ask": one question about this recording, in a panel over the detail.
                val askBlocked = askBlocked(detail.writing, detail.hasTranscript, busy, connection)
                BlueprintMenuRow(
                    strings[Str.ASK_MENU],
                    { open = false; model.openAsk() },
                    enabled = askBlocked == null,
                    secondary = askBlocked?.let { strings[it] },
                )
                // docs/08 "Titles and speaker names": two items, the title and the names apart, each offered by what the
                // recording still lacks, by the rule the core asks by.
                val needs = detail.transcript?.let { FillNeeds.of((detail.title as? UiMessage.Text)?.value, it) }
                val run = model.fills.firstOrNull { it.recordingId == detail.recordingId }
                FillHalf.entries.forEach { half ->
                    val fillBlocked = fillBlocked(half, detail.writing, detail.hasTranscript, busy, connection, half.runs(run),
                        needs?.let(half::needed) ?: false)
                    BlueprintMenuRow(
                        strings[half.item],
                        { open = false; model.fill(half) },
                        enabled = fillBlocked == null,
                        secondary = fillBlocked?.let { strings[it] },
                    )
                }
            }
            // D24: where the recording is kept — since 2026-10-28 the only place it is opened from.
            val place = when {
                detail.driveUrl != null -> Str.RECENT_OPEN_DRIVE
                detail.folderPath != null -> Str.SETTINGS_OPEN_FOLDER
                else -> null
            }
            if (place != null) {
                BlueprintMenuDivider()
                BlueprintMenuRow(strings[place], { open = false; model.openDetailStorage() })
            }
            // docs/03 "Deleting in the app": last and on its own, by the list row's rule — never over a recording still
            // being written — and with no shortcut, so a delete is never one key away (2026-10-15).
            val row = detail.row
            val deleteBlocked = row?.let(::deleteBlocked)
            BlueprintMenuDivider()
            BlueprintMenuRow(
                strings[Str.DELETE],
                { open = false; row?.let(model::askToDelete) },
                // Not until the row has been read, a moment after the detail opens.
                enabled = row != null && deleteBlocked == null,
                secondary = deleteBlocked?.let { strings[it] },
                danger = true,
            )
        } }
    }
}

/**
 * Why More → Delete waits (2026-10-15): the list row's own rule ([RecentItem.deletable]), said as `Still recording` the
 * way every other item says it, or as the row's own state word, `Importing`.
 */
internal fun deleteBlocked(item: RecentItem): Str? = when {
    item.deletable -> null
    (item.state as? UiMessage.Res)?.key == Str.STATUS_RECORDING -> Str.DETAIL_STILL_RECORDING
    else -> (item.state as? UiMessage.Res)?.key
}

/**
 * docs/08 "Editing" · "Me and others": what a speaker is called — the name the user gave, else `Me` for the person
 * who made the recording, else null, which the label shows as the id.
 */
internal fun speakerName(speaker: TranscriptSpeaker?, strings: Strings): String? =
    speaker?.name?.takeIf { it.isNotBlank() } ?: if (speaker?.me == true) strings[Str.SPEAKER_ME] else null

/**
 * docs/08 "Editing": who says a group — the name the user gave ([speakerName]), or the id in monospace — as a quiet
 * control that opens the speaker menu. Speakers are told apart by this label alone.
 */
@Composable
internal fun SpeakerBadge(id: String, name: String?, enabled: Boolean = true, reason: String? = null, onClick: () -> Unit) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    // The compact time button's box beside it (2026-10-09, 2026-10-28): the status badge's size in the same corner and
    // edge, and the same 12 type (docs/09 "Component measurements" — Time and speaker button), so the two share one
    // height, one centre and one baseline; the target around it is still the touch minimum.
    CompactControl(
        onClick = onClick,
        // Why it is off, for whoever cannot see that it is (D13).
        modifier = Modifier.semantics { if (!enabled && reason != null) stateDescription = reason },
        face = Modifier.border(palette.line, if (enabled) palette.inputBorder else palette.disabled, shape),
        shape = shape,
        enabled = enabled,
    ) {
        val sans = MaterialTheme.typography.labelMedium
        Text(
            name ?: id,
            style = if (name != null) sans else mono.small,
            color = palette.textMuted,
            maxLines = 1,
            modifier = if (name != null) Modifier else Modifier.onLabelBaseline(sans),
        )
    }
}

/**
 * docs/08 "Editing": Rename speaker, or Change speaker for this line — which turns the menu into the list of
 * speakers and New speaker, under a heading that goes back (2026-10-10). [current] is "" on a line nobody was
 * identified on, which opens on the list and has nothing to go back to.
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
    BlueprintMenu(true, onDismiss) { BlueprintMenuColumn {
        if (!choosing) {
            BlueprintMenuRow(strings[Str.SPEAKER_RENAME], { onDismiss(); onRename() })
            BlueprintMenuRow(strings[Str.SPEAKER_CHANGE], { choosing = true })
        } else {
            if (current.isNotEmpty()) BlueprintMenuRow(strings[Str.SPEAKER_CHANGE], { choosing = false }, mark = BACK_MARK)
            speakers.forEach { speaker ->
                val label = speakerName(speaker, strings) ?: speaker.id
                BlueprintMenuRow(label, { onDismiss(); onChange(speaker.id) }, mark = if (speaker.id == current) SELECTION_MARK else "")
            }
            // In the column the names are in.
            BlueprintMenuRow(strings[Str.SPEAKER_NEW], { onDismiss(); onChange(null) }, mark = "")
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
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(strings[Str.SAVE], { onSave(name) }, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintTextField(name, { name = it }, strings[Str.SPEAKER_NAME_PLACEHOLDER], placeholder = strings[Str.SPEAKER_NAME_PLACEHOLDER], monospace = false)
    }
}

/**
 * docs/08 "Editing" · "AI requests": leaving an editor with changes in it — [body] says whose. Discard is not red: no
 * recording goes.
 */
@Composable
internal fun DiscardEditsDialog(
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    body: Str = Str.EDIT_DISCARD_BODY,
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
        BlueprintDialogText(strings[body])
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
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
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
                // A quiet control's edge is the input border, 3:1 against the page in light and dark.
                .border(palette.line, palette.inputBorder, RoundedCornerShape(Radius.node))
                .focusRing(RoundedCornerShape(Radius.node))
                .pressCursor()
                .clickable(role = Role.Button) { open = true }
                .semantics {
                    contentDescription = strings[Str.PLAYER_SPEED]
                    stateDescription = strings[if (skipSilence) Str.PLAYER_SPEED_VALUE_SKIP else Str.PLAYER_SPEED_VALUE, label]
                },
            contentAlignment = Alignment.Center,
        ) {
            // The buttons' 14 beside it, in monospace: one baseline along the bar (2026-10-09). A number, left to right
            // in every language (2026-10-10).
            Text(label, style = mono.bodySmall.copy(textDirection = TextDirection.Ltr), color = palette.textMuted, modifier = Modifier.padding(horizontal = Space.s).onLabelBaseline())
            if (skipSilence) {
                Box(Modifier.align(Alignment.TopEnd).padding(Space.xs).size(6.dp).background(palette.accent, RoundedCornerShape(Radius.badge)))
            }
        }
        BlueprintMenu(open, { open = false }) { BlueprintMenuColumn {
            SPEEDS.forEach { choice ->
                BlueprintMenuRow(speedLabel(choice), { open = false; onSpeed(choice) }, mark = if (choice == speed) SELECTION_MARK else "", ltr = true)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MinTouch)
                    .focusRing()
                    .pressCursor()
                    .toggleable(value = skipSilence, role = Role.Switch, onValueChange = onSkipSilence)
                    .padding(start = Space.sm, end = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // In the column the speeds are in, past their mark's.
                Text(
                    strings[Str.PLAYER_SKIP_SILENCE],
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.text,
                    modifier = Modifier.weight(1f).padding(start = SPEED_MARK_INSET),
                )
                SwitchTrack(checked = skipSilence)
            }
        } }
    }
}

/** `1×`, `1.25×`: a number, so not translated. */
internal fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

internal val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** The detail's More button: a mark, with "More" for whoever cannot see it. */
private const val MORE_MARK = "⋯"

private const val COPIED_MS = 3_000L

/** Where a menu row's label starts past its mark column: the column and the gap after it. */
private val SPEED_MARK_INSET = 16.dp + Space.s

/**
 * Export's seven items and More's eleven whole, each with a second line under it, and More's four hairlines — none
 * behind a scroll while the window has the room ([BlueprintMenu] keeps it inside the window).
 */
private val DETAIL_MENU_HEIGHT = 620.dp
