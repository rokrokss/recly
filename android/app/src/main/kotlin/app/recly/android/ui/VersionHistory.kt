package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import recly.core.transcribe.TranscriptVersion
import recly.core.transcribe.TranscriptVersionKind

/** docs/09 "Version history": what made a version, in the row's own words. */
@StringRes
internal fun TranscriptVersionKind.label(): Int = when (this) {
    TranscriptVersionKind.TRANSCRIBED -> R.string.version_transcribed
    TranscriptVersionKind.EDITED -> R.string.version_edited
    TranscriptVersionKind.CORRECTED -> R.string.version_corrected
    TranscriptVersionKind.RESTORED -> R.string.version_restored
    TranscriptVersionKind.RECEIVED -> R.string.version_received
}

/** When a version was made, in [locale]'s own words and [zone]'s time — or the stamp as it came when it cannot be read. */
internal fun versionStamp(isoUtc: String, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.parse(isoUtc).atZone(zone))
}.getOrDefault(isoUtc)

/**
 * docs/09 "Version history": the transcripts this device kept of the recording, newest first — what made each over when
 * — and `Restore` on every one but the first, the transcript shown. No confirmation: the version a restore replaces is
 * kept, and the list's first row then undoes it. [versions] is null while they are read; [restoring] is the id of the one
 * being restored; [refusal] is why the last restore did not happen, under the list.
 */
@Composable
internal fun VersionHistoryDialog(
    versions: List<TranscriptVersion>?,
    restoring: String?,
    refusal: RestoreRefusal?,
    onRestore: (TranscriptVersion) -> Unit,
    onClose: () -> Unit,
) {
    val palette = blueprint
    val locale = LocalConfiguration.current.locales[0]
    BlueprintDialog(
        title = stringResource(R.string.versions_title),
        onDismissRequest = onClose,
        actions = null,
        onClose = onClose,
        modifier = Modifier.testTag("version-history"),
    ) {
        versions.orEmpty().forEachIndexed { index, version ->
            val stamp = versionStamp(version.at, locale)
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = MinTouch).testTag("version-$index"),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(version.kind.label()), style = MaterialTheme.typography.bodyMedium, color = palette.text)
                    Text(stamp, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                }
                when {
                    version.current -> Unit
                    // In the button's own height, so the row does not move when it comes back.
                    version.id == restoring -> Box(Modifier.defaultMinSize(minHeight = MinTouch), contentAlignment = Alignment.Center) {
                        LoadingText(stringResource(R.string.version_restoring), MaterialTheme.typography.bodySmall, palette.textMuted)
                    }
                    else -> {
                        val label = stringResource(R.string.version_restore_from, stamp)
                        BlueprintButton(stringResource(R.string.version_restore), { onRestore(version) }, tone = ButtonTone.QUIET,
                            enabled = restoring == null,
                            modifier = Modifier.semantics { contentDescription = label }.testTag("version-restore-$index"))
                    }
                }
            }
        }
        refusal?.let {
            Text(stringResource(it.text), Modifier.testTag("version-refusal"), style = MaterialTheme.typography.bodySmall,
                color = if (it.failed) palette.danger else palette.textMuted)
        }
        Text(stringResource(R.string.versions_footnote), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
    }
}
