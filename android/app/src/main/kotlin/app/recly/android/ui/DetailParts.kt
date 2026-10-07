@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.recly.android.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.BlueprintMenu
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.component.GlyphIcon
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.component.MenuAction
import app.recly.android.ui.component.MenuOption
import app.recly.android.ui.component.SwitchTrack
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recly.core.processing.ProcessingTranscription
import recly.core.processing.TranscriptionMode
import recly.core.recording.ExportFormat
import recly.core.transcribe.SttProviders
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptNormalizer

/** What the More menu can do for this page, and why not where it cannot (docs/09 "Detail header and More menu"). */
internal class MoreActions(
    val onRename: () -> Unit,
    val onEdit: () -> Unit,
    val onRetranscribe: () -> Unit,
    val onAddHighlight: () -> Unit,
)

/**
 * docs/09 "Detail header and More menu": Rename · Edit transcript · Transcribe again · Add highlight at the
 * playhead, in that order. An item that cannot run is shown disabled with its reason under it.
 */
@Composable
internal fun MoreButton(detail: DetailState, transcription: ProcessingTranscription?, playheadSec: Double, actions: MoreActions) {
    var open by remember { mutableStateOf(false) }
    val transcript = detail.transcript?.takeIf { t -> t.segments.any { it.text.isNotBlank() } }
    val transcribing = stringResource(R.string.detail_transcribing)
    Box {
        GlyphButton(Glyph.MORE, stringResource(R.string.detail_more), { open = true }, Modifier.testTag("detail-more"))
        if (open) BlueprintMenu(onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit): () -> Unit = { open = false; action() }
            MenuAction(stringResource(R.string.detail_rename), pick(actions.onRename), modifier = Modifier.testTag("more-rename"))
            val editReason = when {
                transcript == null -> stringResource(R.string.detail_no_transcript)
                detail.transcribing -> transcribing
                else -> null
            }
            MenuAction(stringResource(R.string.detail_edit), pick(actions.onEdit), enabled = editReason == null, reason = editReason,
                modifier = Modifier.testTag("more-edit"))
            val againReason = when {
                transcription == null || transcription.mode == TranscriptionMode.OFF -> stringResource(R.string.detail_transcription_off)
                detail.transcribing -> transcribing
                else -> null
            }
            MenuAction(stringResource(R.string.detail_retranscribe), pick(actions.onRetranscribe), enabled = againReason == null, reason = againReason,
                modifier = Modifier.testTag("more-retranscribe"))
            val noAudio = detail.audio.isEmpty
            val stamp = hms(playheadSec.toLong())
            MenuAction(monoStamp(stringResource(R.string.highlight_add_at, stamp), stamp, mono.bodySmall), pick(actions.onAddHighlight), enabled = !noAudio,
                reason = if (noAudio) stringResource(R.string.player_no_audio) else null, modifier = Modifier.testTag("more-highlight"))
        }
    }
}

/** One row of the Share sheet: what it is, its format, and the export behind it (null: Copy all). */
private data class ShareRow(val glyph: Glyph, val label: Int, val format: Int?, val export: ExportFormat?)

private val SHARE_ROWS = listOf(
    ShareRow(Glyph.DOCUMENT, R.string.share_transcript, R.string.share_transcript_format, ExportFormat.TXT),
    ShareRow(Glyph.DOCUMENT, R.string.share_notes, R.string.share_notes_format, ExportFormat.MD),
    ShareRow(Glyph.SUBTITLES, R.string.share_subtitles, R.string.share_subtitles_format, ExportFormat.SRT),
    ShareRow(Glyph.SUBTITLES, R.string.share_web_subtitles, R.string.share_web_subtitles_format, ExportFormat.VTT),
    ShareRow(Glyph.AUDIO, R.string.share_audio, R.string.share_audio_format, ExportFormat.AUDIO),
    ShareRow(Glyph.COPY, R.string.transcript_copy, null, null),
)

/**
 * docs/08 "Exports" · docs/09 "Share / export": the sheet behind the header's Share. A file row asks the core
 * for the file — the row's end says `Preparing…` meanwhile — and hands it to the system share sheet; Copy all
 * puts the text with its times on the clipboard and says `✓ Copied`.
 */
@Composable
internal fun ShareSheet(detail: DetailState, onExport: suspend (ExportFormat) -> String?, onDismiss: () -> Unit) {
    val palette = blueprint
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val transcript = detail.transcript?.takeIf { t -> t.segments.any { it.text.isNotBlank() } }
    var preparing by remember { mutableStateOf<ExportFormat?>(null) }
    var unavailable by remember { mutableStateOf(emptySet<ExportFormat>()) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(3000); copied = false } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.surface,
        shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.m).testTag("share-sheet")) {
            Text(stringResource(R.string.detail_share), Modifier.padding(horizontal = Space.m, vertical = Space.s),
                style = MaterialTheme.typography.titleMedium, color = palette.text)
            HairLine()
            SHARE_ROWS.forEach { row ->
                val format = row.export
                val noAudio = detail.audio.isEmpty && detail.driveFetch == DriveFetch.IDLE
                val reason = when {
                    format == ExportFormat.AUDIO && (noAudio || format in unavailable) -> stringResource(R.string.player_no_audio)
                    format != ExportFormat.AUDIO && transcript == null -> stringResource(R.string.detail_no_transcript)
                    else -> null
                }
                ShareLine(
                    glyph = row.glyph,
                    label = stringResource(row.label),
                    secondary = reason ?: row.format?.let { stringResource(it) },
                    enabled = reason == null && preparing == null,
                    trailing = {
                        when {
                            format != null && preparing == format ->
                                LoadingText(stringResource(R.string.share_preparing), MaterialTheme.typography.bodySmall, palette.textMuted)
                            format == null && copied ->
                                Text("${stringResource(R.string.action_done)} ${stringResource(R.string.transcript_copied)}",
                                    style = MaterialTheme.typography.bodySmall, color = palette.success)
                        }
                    },
                    onClick = {
                        if (format == null) {
                            transcript?.let { clipboard.setText(AnnotatedString(TranscriptNormalizer.text(it))) }
                            copied = true
                        } else {
                            preparing = format
                            scope.launch {
                                val path = onExport(format)
                                preparing = null
                                if (path == null) unavailable = unavailable + format else context.share(path, format)
                            }
                        }
                    },
                    modifier = Modifier.testTag("share-${format?.name?.lowercase() ?: "copy"}"),
                )
            }
        }
    }
}

@Composable
private fun ShareLine(
    glyph: Glyph,
    label: String,
    secondary: String?,
    enabled: Boolean,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (enabled) palette.textMuted else palette.grid)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) palette.text else palette.textMuted)
            secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
        }
        trailing()
    }
}

/** The system share sheet with one exported file, readable through the app's own provider. */
private fun Context.share(path: String, format: ExportFormat) {
    val uri = FileProvider.getUriForFile(this, "$packageName.exports", File(path))
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(format))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(File(path).name, uri)
    startActivity(Intent.createChooser(send, null))
}

private fun mimeOf(format: ExportFormat): String = when (format) {
    ExportFormat.TXT -> "text/plain"
    ExportFormat.MD -> "text/markdown"
    ExportFormat.SRT -> "application/x-subrip"
    ExportFormat.VTT -> "text/vtt"
    ExportFormat.AUDIO -> "audio/mp4"
}

/** docs/09 "Playback": the speeds the chip offers. */
internal val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** `1×`, `1.25×` — the speed as the chip writes it, in every language. */
internal fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

/**
 * docs/09 "Playback": speed and Skip silence in one quiet chip. The speed in monospace; a small accent dot at
 * its top-end corner while Skip silence is on, which the screen reader says too.
 */
@Composable
internal fun SpeedChip(speed: Float, skipSilence: Boolean, onSpeed: (Float) -> Unit, onSkipSilence: (Boolean) -> Unit) {
    val palette = blueprint
    var open by remember { mutableStateOf(false) }
    val description = stringResource(if (skipSilence) R.string.playback_speed_skip else R.string.playback_speed_value, speedLabel(speed))
    Box {
        Box(
            Modifier
                .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
                .border(palette.line, palette.grid, RoundedCornerShape(Radius.node))
                .clickable(role = Role.Button) { open = true }
                .clearAndSetSemantics { contentDescription = description }
                .testTag("speed-chip"),
            contentAlignment = Alignment.Center,
        ) {
            Text(speedLabel(speed), Modifier.padding(horizontal = Space.s), style = mono.bodySmall, color = palette.textMuted)
            if (skipSilence) {
                Box(Modifier.align(Alignment.TopEnd).offset((-4).dp, 4.dp).size(6.dp).background(palette.accent, RoundedCornerShape(Radius.badge)))
            }
        }
        // Tall enough for the six speeds and the switch together: the switch is the one line that must not scroll away.
        if (open) BlueprintMenu(onDismissRequest = { open = false }, maxHeight = 400.dp) {
            PLAYBACK_SPEEDS.forEach { option ->
                MenuOption(speedLabel(option), option == speed, onSelect = { open = false; if (option != speed) onSpeed(option) }, monospace = true)
            }
            HairLine()
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = skipSilence, role = Role.Switch, onValueChange = onSkipSilence)
                    .padding(horizontal = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.playback_skip_silence), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = palette.text)
                SwitchTrack(skipSilence)
            }
        }
    }
}

/** docs/09 "Highlights": what a tick or a flag offers — go there, or take the mark away (not red: no recording is deleted). */
@Composable
internal fun HighlightMenu(atSec: Double, onGo: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    BlueprintMenu(onDismissRequest = onDismiss) {
        val stamp = hms(atSec.toLong())
        MenuAction(monoStamp(stringResource(R.string.transcript_seek, stamp), stamp, mono.bodySmall), { onDismiss(); onGo() }, modifier = Modifier.testTag("highlight-go"))
        MenuAction(stringResource(R.string.highlight_remove), { onDismiss(); onRemove() }, modifier = Modifier.testTag("highlight-remove"))
    }
}

/**
 * docs/09 "Editing and speakers": Rename speaker · Change speaker for this line, the second opening the list of
 * speakers (name or id) and New speaker. [current] is the speaker of the line; null on a transcript nobody
 * was identified in, where only New speaker is there to choose.
 */
@Composable
internal fun SpeakerMenu(
    transcript: Transcript,
    current: String?,
    onRename: (String) -> Unit,
    onChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by remember { mutableStateOf(current == null) }
    BlueprintMenu(onDismissRequest = onDismiss) {
        if (!choosing) {
            MenuAction(stringResource(R.string.speaker_rename), { onDismiss(); current?.let(onRename) }, modifier = Modifier.testTag("speaker-rename"))
            MenuAction(stringResource(R.string.speaker_change), { choosing = true }, modifier = Modifier.testTag("speaker-change"))
        } else {
            transcript.speakers.forEach { speaker ->
                MenuOption(speaker.name ?: speaker.id, speaker.id == current, onSelect = {
                    onDismiss()
                    if (speaker.id != current) onChange(speaker.id)
                }, monospace = speaker.name == null)
            }
            MenuAction(stringResource(R.string.speaker_new), { onDismiss(); onChange(null) }, modifier = Modifier.testTag("speaker-new"))
        }
    }
}

/** docs/09 "Editing and speakers": what a speaker is called; empty is no name, and the id shows again. */
@Composable
internal fun SpeakerNameDialog(name: String?, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(name.orEmpty()) }
    BlueprintDialog(
        title = stringResource(R.string.speaker_name_title),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(stringResource(R.string.action_cancel), onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(stringResource(R.string.action_save), { onSave(text) }, tone = ButtonTone.PRIMARY, modifier = Modifier.testTag("speaker-name-save"))
        },
    ) {
        OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.speaker_name_placeholder)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("speaker-name-field"))
    }
}

/**
 * docs/10 "Re-transcription" · docs/09: the question, in one line built from the settings as they are now —
 * the method and the language — and, where the user changed the transcript, that those changes go.
 */
@Composable
internal fun RetranscribeDialog(transcription: ProcessingTranscription, edited: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val method = when (transcription.mode) {
        TranscriptionMode.EXTERNAL -> transcription.external?.provider?.let(SttProviders::displayName).orEmpty()
        else -> stringResource(R.string.retranscribe_local)
    }
    val body = stringResource(R.string.retranscribe_body, method, transcriptionLanguageLabel(transcription.language)) +
        if (edited) " " + stringResource(R.string.retranscribe_edits) else ""
    BlueprintDialog(
        title = stringResource(R.string.retranscribe_title),
        onDismissRequest = onCancel,
        actions = {
            BlueprintButton(stringResource(R.string.action_cancel), onCancel, tone = ButtonTone.QUIET, minWidth = MinTouch)
            BlueprintButton(stringResource(R.string.retranscribe_confirm), onConfirm, tone = ButtonTone.PRIMARY, modifier = Modifier.testTag("retranscribe-confirm"))
        },
    ) { BlueprintDialogText(body) }
}

/** docs/09 "Editing and speakers": leaving the editor with changes in it. Discard is not red — nothing saved is lost. */
@Composable
internal fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    BlueprintDialog(
        title = stringResource(R.string.edit_discard_title),
        onDismissRequest = onKeep,
        actions = {
            BlueprintButton(stringResource(R.string.edit_keep), onKeep, tone = ButtonTone.QUIET)
            BlueprintButton(stringResource(R.string.edit_discard), onDiscard, modifier = Modifier.testTag("edit-discard"))
        },
    ) { BlueprintDialogText(stringResource(R.string.edit_discard_body)) }
}

/**
 * docs/09 "Search": the find bar at the top of a transcript opened from a search — `‹ 2 / 7 ›` and a close.
 * The arrows move between matches without touching where playback is.
 */
@Composable
internal fun FindBar(current: Int, total: Int, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val palette = blueprint
    val count = stringResource(R.string.processing_download_bytes, (current + 1).coerceAtMost(total).toString(), total.toString())
    Row(
        Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.s).testTag("find-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlueprintButton("‹", onPrevious, enabled = total > 0, tone = ButtonTone.QUIET, minWidth = MinTouch,
            modifier = Modifier.findLabel(stringResource(R.string.find_previous)).testTag("find-previous"))
        Text("${(current + 1).coerceAtMost(total)} / $total", Modifier.padding(horizontal = Space.s).clearAndSetSemantics { contentDescription = count },
            style = mono.bodySmall, color = palette.textMuted)
        BlueprintButton("›", onNext, enabled = total > 0, tone = ButtonTone.QUIET, minWidth = MinTouch,
            modifier = Modifier.findLabel(stringResource(R.string.find_next)).testTag("find-next"))
        Box(Modifier.weight(1f))
        GlyphButton(Glyph.CLOSE, stringResource(R.string.find_close), onClose, Modifier.testTag("find-close"))
    }
    HairLine()
}

private fun Modifier.findLabel(label: String): Modifier = semantics { contentDescription = label }
