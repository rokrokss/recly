package app.recly.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import java.util.Locale
import kotlinx.coroutines.launch
import recly.core.transcribe.EditResult
import recly.core.transcribe.TranscriptVersion
import recly.core.transcribe.TranscriptVersionKind

/** docs/09 "Version history": what made a version, as its row says it. */
internal fun versionKindLabel(kind: TranscriptVersionKind): Str = when (kind) {
    TranscriptVersionKind.TRANSCRIBED -> Str.VERSIONS_TRANSCRIBED
    TranscriptVersionKind.EDITED -> Str.VERSIONS_EDITED
    TranscriptVersionKind.CORRECTED -> Str.VERSIONS_CORRECTED
    TranscriptVersionKind.RESTORED -> Str.VERSIONS_RESTORED
    TranscriptVersionKind.RECEIVED -> Str.VERSIONS_RECEIVED
}

/**
 * Why a restore — a row's Restore, or Undo after a correction — did not bring the version back, or null when it did: the
 * job in the way by More's own reason for it ([busy]), and for anything else that it could not. A null [result] is a
 * call that threw.
 */
internal fun restoreRefusal(result: EditResult?, busy: Str): Str? = when (result) {
    is EditResult.Edited -> null
    EditResult.Busy -> busy
    else -> Str.VERSIONS_RESTORE_FAILED
}

/**
 * docs/09 "Version history" (docs/08 "Versions"): the transcripts this PC kept of the recording, newest first, in a modal
 * dialog over the detail. Read again when the transcript changes under it. A restore closes it and shows the transcript
 * ([ShellModel.restoreVersion]); refused, it says why under the list.
 */
@Composable
internal fun VersionsDialog(
    model: ShellModel,
    detail: RecordingDetail,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
) {
    val versions by produceState<List<TranscriptVersion>?>(null, detail.recordingId, detail.transcript) {
        value = model.transcriptVersions(detail.recordingId)
    }
    DisposableEffect(detail.recordingId) { onDispose { model.closeVersions() } }
    val scope = rememberCoroutineScope()
    var restoring by remember(detail.recordingId) { mutableStateOf<String?>(null) }
    var refused by remember(detail.recordingId) { mutableStateOf<Str?>(null) }
    BlueprintDialog(
        title = strings[Str.VERSIONS_TITLE],
        onDismissRequest = model::closeVersions,
        actions = null,
        height = VERSIONS_OPENING_HEIGHT,
        theme = theme,
        fitContent = true,
        maxHeight = VERSIONS_HEIGHT,
        closeLabel = strings[Str.CLOSE],
    ) {
        VersionsPanel(
            versions = versions,
            restoring = restoring,
            refused = refused,
            onRestore = { version ->
                scope.launch {
                    restoring = version.id
                    refused = null
                    try {
                        refused = model.restoreVersion(detail.recordingId, version.id)
                    } finally {
                        restoring = null
                    }
                }
            },
            strings = strings,
        )
    }
}

/**
 * The rows — what made each version over when, in the app's language and this PC's time zone — with a quiet Restore on
 * every row but the first, which is the transcript shown; then why a restore did not happen, and the note. Nothing
 * until the list has been read. One restore at a time.
 */
@Composable
internal fun VersionsPanel(
    versions: List<TranscriptVersion>?,
    /** The version being brought back, while it is. */
    restoring: String?,
    refused: Str?,
    onRestore: (TranscriptVersion) -> Unit,
    strings: Strings,
) {
    versions ?: return
    val locale = Locale.forLanguageTag(strings.language)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            versions.forEach { version ->
                val at = LedgerFormat.spoken(version.at, locale)
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = MinTouch),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                        Text(strings[versionKindLabel(version.kind)], style = MaterialTheme.typography.bodyMedium, color = blueprint.text)
                        Text(at, style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
                    }
                    // The first is the transcript shown: nothing to bring back, and no word to say so.
                    if (!version.current) {
                        val label = strings[Str.VERSIONS_RESTORE_LABEL, at]
                        BlueprintButton(
                            strings[if (restoring == version.id) Str.VERSIONS_RESTORING else Str.VERSIONS_RESTORE],
                            { onRestore(version) },
                            modifier = Modifier.semantics { contentDescription = label },
                            tone = ButtonTone.QUIET,
                            enabled = restoring == null || restoring == version.id,
                            busy = restoring == version.id,
                        )
                    }
                }
            }
        }
        refused?.let { Text(strings[it], style = MaterialTheme.typography.bodySmall, color = refusalInk(it)) }
        Text(strings[Str.VERSIONS_NOTE], style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted)
    }
}

/** docs/09 "Every state is color + text": a restore that failed is red; one a job is in the way of, the warning ink. */
@Composable
internal fun refusalInk(reason: Str) = if (reason == Str.VERSIONS_RESTORE_FAILED) blueprint.danger else blueprint.warningInk

/** The tallest the dialog grows before its rows scroll under the title: about ten versions. */
private val VERSIONS_HEIGHT = 620.dp

/** Where the window opens before it takes the panel's own height. */
private val VERSIONS_OPENING_HEIGHT = 320.dp
