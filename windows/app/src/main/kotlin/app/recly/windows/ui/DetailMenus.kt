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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
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
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.Summary
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
 * Copy all. While the core prepares one (joining the audio takes a moment) its row says so. The summary is one of
 * them wherever summaries are offered.
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
        val summaries = model.chatGpt?.connection != ChatGptConnection.Unavailable
        BlueprintMenu(open, { if (preparing == null) open = false }, end = true, maxHeight = DETAIL_MENU_HEIGHT) { BlueprintMenuColumn {
            EXPORTS.filter { (format) -> format != ExportFormat.SUMMARY || summaries }.forEach { (format, label, kind) ->
                val audio = format == ExportFormat.AUDIO
                val reason = when {
                    // Nothing of a take still being written is whole enough to export.
                    audio && detail.writing -> Str.DETAIL_STILL_RECORDING
                    audio && !detail.audioReachable -> Str.PLAYER_NO_AUDIO
                    format == ExportFormat.SUMMARY -> if (detail.summary.saved() == null) Str.SUMMARY_NONE else null
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
    // The summary as text, under the chip's own name; the .md carries it too (docs/08 "Exports").
    Triple(ExportFormat.SUMMARY, Str.SUMMARY_TAB, Str.EXPORT_TRANSCRIPT_FORMAT),
    Triple(ExportFormat.SRT, Str.EXPORT_SUBTITLES, Str.EXPORT_SUBTITLES_FORMAT),
    Triple(ExportFormat.VTT, Str.EXPORT_WEB_SUBTITLES, Str.EXPORT_WEB_SUBTITLES_FORMAT),
    Triple(ExportFormat.AUDIO, Str.EXPORT_AUDIO, Str.EXPORT_AUDIO_FORMAT),
)

/**
 * docs/09 "Screen principles": the detail's ⋯, in three groups with a hairline between them (2026-10-10) — Rename ·
 * Edit transcript · Transcribe again, then the summary's Summarize · Summarize as… · Edit summary · Ask about this
 * recording… where ChatGPT is offered, then Add highlight. What cannot run now stays in its place, disabled, with the
 * reason under it — `Still recording` for every one of them while the take is being written. Summarize as… turns the
 * menu into the formats under a heading that goes back, the way Change speaker turns the speaker's menu into the
 * speakers.
 */
@Composable
internal fun MoreButton(
    model: ShellModel,
    detail: RecordingDetail,
    positionSec: Double,
    onEdit: () -> Unit,
    onEditSummary: (Summary) -> Unit,
    strings: Strings,
) {
    var open by remember { mutableStateOf(false) }
    var choosingFormat by remember { mutableStateOf(false) }
    val stamp = LedgerFormat.clock(positionSec, detail.spanSec)
    Box {
        BlueprintButton(
            MORE_MARK,
            {
                choosingFormat = false
                open = true
            },
            modifier = Modifier.semantics { contentDescription = strings[Str.DETAIL_MORE] },
            tone = ButtonTone.QUIET,
        )
        BlueprintMenu(open, { open = false }, end = true, maxHeight = DETAIL_MENU_HEIGHT) { BlueprintMenuColumn {
            if (choosingFormat) {
                BlueprintMenuRow(strings[Str.SUMMARY_AS], { choosingFormat = false }, mark = BACK_MARK)
                // The formats Settings offers; the summary on show has its own marked.
                val current = detail.summary.saved()?.summaryFormat
                model.chatGpt?.preferences?.formats.orEmpty().forEach { format ->
                    BlueprintMenuRow(
                        strings[summaryFormatLabel(format)],
                        { open = false; model.askToSummarize(format) },
                        mark = if (format == current) SELECTION_MARK else "",
                    )
                }
                return@BlueprintMenuColumn
            }
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
            // docs/08 "Summaries": asked for here, one recording at a time; not offered where ChatGPT is not — and then
            // the group and its hairline are not there at all.
            val connection = model.chatGpt?.connection
            if (connection != null && connection != ChatGptConnection.Unavailable) {
                BlueprintMenuDivider()
                val summaryBlocked = summarizeBlocked(detail.writing, detail.hasTranscript, detail.transcriptionRunning, connection, detail.summary)
                BlueprintMenuRow(
                    strings[summarizeLabel(detail.summary)],
                    { open = false; model.askToSummarize() },
                    enabled = summaryBlocked == null,
                    secondary = summaryBlocked?.let { strings[it] },
                )
                BlueprintMenuRow(
                    strings[Str.SUMMARY_AS],
                    { choosingFormat = true },
                    enabled = summaryBlocked == null,
                    secondary = summaryBlocked?.let { strings[it] },
                )
                summaryEditItem(detail.summary)?.let { item ->
                    val editSummaryBlocked = recording ?: item.blocked
                    BlueprintMenuRow(
                        strings[Str.SUMMARY_EDIT],
                        { open = false; onEditSummary(item.summary) },
                        enabled = editSummaryBlocked == null,
                        secondary = editSummaryBlocked?.let { strings[it] },
                    )
                }
                // docs/08 "Ask": one question about this recording, in a panel over the detail.
                val askBlocked = askBlocked(detail.writing, detail.hasTranscript, detail.transcriptionRunning, connection)
                BlueprintMenuRow(
                    strings[Str.ASK_MENU],
                    { open = false; model.openAsk() },
                    enabled = askBlocked == null,
                    secondary = askBlocked?.let { strings[it] },
                )
            }
            BlueprintMenuDivider()
            // The playhead is the player's, and a take still being written has no player yet.
            val highlightBlocked = recording ?: Str.PLAYER_NO_AUDIO.takeIf { detail.audio.isEmpty }
            BlueprintMenuRow(
                // The time is data, in the monospace every other time on this page is in (2026-10-10).
                monoStamp(strings[Str.HIGHLIGHT_ADD_AT, stamp], stamp, mono.small.fontFamily),
                { open = false; model.setHighlights(detail.recordingId, detail.highlights + positionSec) },
                enabled = highlightBlocked == null,
                secondary = highlightBlocked?.let { strings[it] },
            )
        } }
    }
}

/** [sentence] with [stamp] in it set in [family] — a time inside a translated sentence, wherever the language puts it. */
internal fun monoStamp(sentence: String, stamp: String, family: FontFamily?): AnnotatedString = buildAnnotatedString {
    append(sentence)
    val at = sentence.indexOf(stamp)
    if (at >= 0) addStyle(SpanStyle(fontFamily = family), at, at + stamp.length)
}

/** docs/03 "Metadata": a highlight's own menu — go there, or take it away (not red: no recording is deleted). */
@Composable
internal fun HighlightMenu(
    atSec: Double,
    /** The recording's length, for the time's format ([LedgerFormat.clock]). */
    totalSec: Double?,
    onDismiss: () -> Unit,
    onGoTo: () -> Unit,
    onRemove: () -> Unit,
    strings: Strings,
) {
    BlueprintMenu(true, onDismiss) { BlueprintMenuColumn {
        BlueprintMenuRow(strings[Str.TRANSCRIPT_SEEK, LedgerFormat.clock(atSec, totalSec)], { onDismiss(); onGoTo() })
        BlueprintMenuRow(strings[Str.HIGHLIGHT_REMOVE], { onDismiss(); onRemove() })
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
internal fun SpeakerBadge(id: String, name: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = blueprint
    // The time button's box beside it (2026-10-09): the same 44 height, corner and edge, and the same 12 type, so
    // the two share one height, one centre and one baseline.
    Box(
        Modifier
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            .border(palette.line, palette.inputBorder, RoundedCornerShape(Radius.node))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name ?: id,
            style = if (name != null) MaterialTheme.typography.labelSmall else mono.small,
            color = palette.textMuted,
            maxLines = 1,
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
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.SAVE], { onSave(name) }, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintTextField(name, { name = it }, strings[Str.SPEAKER_NAME_PLACEHOLDER], placeholder = strings[Str.SPEAKER_NAME_PLACEHOLDER], monospace = false)
    }
}

/**
 * docs/08 "Editing" · "Summaries": leaving an editor with changes in it — [body] says whose. Discard is not red: no
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

/** docs/08 "Summaries": Summarize again over a summary the user edited. Replace is not red: no recording goes. */
@Composable
internal fun SummaryReplaceDialog(
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onReplace: () -> Unit,
) {
    BlueprintDialog(
        title = strings[Str.SUMMARY_REPLACE_TITLE],
        onDismissRequest = onCancel,
        theme = theme,
        fitContent = true,
        actions = {
            BlueprintButton(strings[Str.CANCEL], onCancel, tone = ButtonTone.QUIET)
            BlueprintButton(strings[Str.SUMMARY_REPLACE], onReplace, tone = ButtonTone.PRIMARY)
        },
    ) {
        BlueprintDialogText(strings[Str.SUMMARY_REPLACE_BODY])
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
                // A quiet control's edge is the input border, 3:1 against the page in light and dark.
                .border(palette.line, palette.inputBorder, RoundedCornerShape(Radius.node))
                .clickable(role = Role.Button) { open = true }
                .semantics {
                    contentDescription = strings[Str.PLAYER_SPEED]
                    stateDescription = strings[if (skipSilence) Str.PLAYER_SPEED_VALUE_SKIP else Str.PLAYER_SPEED_VALUE, label]
                },
            contentAlignment = Alignment.Center,
        ) {
            // The buttons' 14 beside it, in monospace: one baseline along the bar (2026-10-09). A number, left to right
            // in every language (2026-10-10).
            Text(label, style = mono.bodySmall.copy(textDirection = TextDirection.Ltr), color = palette.textMuted, modifier = Modifier.padding(horizontal = Space.s))
            if (skipSilence) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(6.dp).background(palette.accent, RoundedCornerShape(1.dp)))
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
                    .toggleable(value = skipSilence, role = Role.Switch, onValueChange = onSkipSilence)
                    .padding(start = Space.s + Space.xs, end = Space.xs),
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
 * Export's seven items and More's eight whole, each with a second line under it, and More's two hairlines — none
 * behind a scroll while the window has the room ([BlueprintMenu] keeps it inside the window).
 */
private val DETAIL_MENU_HEIGHT = 560.dp
