@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.StatusBadge
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import java.util.Locale

/**
 * The download's own lines and buttons, as settings and the first-run card both draw them: while it
 * runs, the percentage and the bytes with a quiet Cancel; partly here, the bytes and "Resume
 * download"; and why the last one stopped, when it failed. [quiet] is the card's "Not now", drawn
 * before the start button so the commit is last (docs/09 화면 원칙 8).
 */
@Composable
fun ModelDownloadControls(
    download: ModelDownload,
    strings: Strings,
    enabled: Boolean,
    onStart: () -> Unit,
    tone: ButtonTone,
    quiet: (@Composable () -> Unit)? = null,
) {
    val palette = blueprint
    val info = download.info
    if (download.running) {
        LoadingText(downloadingText(strings, info?.progress), MaterialTheme.typography.bodySmall, palette.textMuted)
    }
    downloadedBytesText(strings, info)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
    }
    download.failure?.let {
        Text(it.text(strings), style = MaterialTheme.typography.bodySmall, color = palette.danger)
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (download.running) {
            // Its own words: in settings it sits right above the form's Cancel.
            BlueprintButton(strings[Str.PROCESSING_MODEL_CANCEL], download::cancel, tone = ButtonTone.QUIET)
        } else {
            quiet?.invoke()
            BlueprintButton(
                label = strings[downloadLabel(info)],
                onClick = onStart,
                tone = tone,
                enabled = enabled && download.meteredPrompt == null,
            )
        }
    }
}

/**
 * The first-run card at the top of the tray popup, only while the model is really missing
 * ([ShellModel.modelCardShown]). The dialog card's shape — hairline, 4dp corners, the surface — so it
 * is not a new kind of thing on the screen.
 */
@Composable
fun ModelCard(model: ShellModel, strings: Strings) {
    val download = model.modelDownload ?: return
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.m, vertical = Space.s)
            .border(palette.line, palette.grid, shape)
            .background(palette.surface, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(strings[Str.PROCESSING_MODEL_CARD_TITLE], style = MaterialTheme.typography.titleSmall, color = palette.text)
        download.info?.modelBytes?.let { bytes ->
            Text(
                strings[Str.PROCESSING_MODEL_CARD_BODY, ByteFormat.format(bytes, Locale.forLanguageTag(strings.language))],
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
            )
        }
        ModelDownloadControls(
            download = download,
            strings = strings,
            enabled = model.modelDownloadAllowed,
            onStart = { model.downloadModel() },
            tone = ButtonTone.PRIMARY,
            quiet = {
                BlueprintButton(strings[Str.PROCESSING_MODEL_NOT_NOW], model::dismissModelPrompt, tone = ButtonTone.QUIET)
            },
        )
    }
}

/**
 * A recording waiting for the model: its first action is the download, in the row's own [language]
 * and in the filled tone. Nothing while a download runs — the banner carries the progress then.
 */
@Composable
fun ModelDownloadChip(model: ShellModel, strings: Strings, language: String?) {
    val download = model.modelDownload ?: return
    if (!rowOffersDownload(download.running)) return
    BlueprintButton(
        label = strings[Str.PROCESSING_PREPARE],
        onClick = { model.downloadModel(language) },
        tone = ButtonTone.PRIMARY,
        enabled = model.modelDownloadAllowed && download.meteredPrompt == null,
    )
}

/**
 * The banner's line for the recordings waiting for the model — the one prompt once any is waiting
 * ([showsModelCard]). Idle, it says what is missing and how many wait, and starts the download in the
 * saved settings' language. While the download runs the line *is* the download — the percentage and
 * the bytes — and the button stops it; "isn't downloaded yet" is never said over a download.
 *
 * The code wears the job's status in the warning tone of a wait, and the sentence stays in the body
 * colour: red is for failures (docs/09 "모든 상태는 색 + 텍스트").
 */
@Composable
fun ModelBanner(model: ShellModel, strings: Strings, alert: JobAlert) {
    val download = model.modelDownload ?: return
    val palette = blueprint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(alert.reason.badge())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (download.running) {
                LoadingText(downloadingText(strings, download.info?.progress), MaterialTheme.typography.bodyMedium, palette.text)
                downloadedBytesText(strings, download.info)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                }
            } else {
                Text(strings[alert.reason.label], style = MaterialTheme.typography.bodyMedium, color = palette.text)
                Text(strings[Str.ALERT_WAITING, alert.count], style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                // A download that stopped short is a failure, and says so in the failure colour.
                download.failure?.let {
                    Text(it.text(strings), style = MaterialTheme.typography.bodySmall, color = palette.danger)
                }
            }
        }
        if (download.running) {
            BlueprintButton(strings[Str.PROCESSING_MODEL_CANCEL], download::cancel, tone = ButtonTone.QUIET)
        } else {
            BlueprintButton(
                label = strings[Str.PROCESSING_PREPARE],
                onClick = { model.downloadModel() },
                enabled = model.modelDownloadAllowed && download.meteredPrompt == null,
            )
        }
    }
}
