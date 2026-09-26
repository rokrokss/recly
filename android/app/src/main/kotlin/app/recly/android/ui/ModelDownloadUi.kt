@file:OptIn(ExperimentalLayoutApi::class)

package app.recly.android.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintDialogText
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.work.ModelDownloadPhase
import app.recly.android.work.ModelDownloadState
import app.recly.android.work.downloadedBytes
import app.recly.android.work.modelPercent
import app.recly.android.work.modelSize
import recly.core.processing.TranscriptionMode
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus

/**
 * The Record tab's first-run card: only while the saved mode is on-device, this build has the
 * engine, the model is missing, nobody said "Not now", and nothing is being recorded — and only
 * while no recording waits for the model yet: then the list's banner, with its count, is the one
 * prompt.
 */
internal fun modelPromptVisible(
    mode: TranscriptionMode?,
    engineInstalled: Boolean,
    status: LocalEngineStatus?,
    dismissed: Boolean,
    capturing: Boolean,
    recordingsWaiting: Boolean,
): Boolean = mode == TranscriptionMode.LOCAL && engineInstalled && status == LocalEngineStatus.MODEL_REQUIRED &&
    !dismissed && !capturing && !recordingsWaiting

/** Whether the download would go over a connection the user pays by the byte for. */
private fun Context.metered(): Boolean = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

/**
 * A gigabyte on a metered connection is the user's to agree to; Wi-Fi needs no question. The
 * returned function runs a download start through that question — `true` is "Download on Wi-Fi".
 * [modelBytes] is what the question says the download is.
 */
@Composable
fun rememberMeteredGate(modelBytes: Long?): ((onWifi: Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    pending?.let { start ->
        BlueprintDialog(title = stringResource(R.string.processing_cellular_title), onDismissRequest = { pending = null }, actions = {
            BlueprintButton(stringResource(R.string.action_cancel), { pending = null }, tone = ButtonTone.QUIET)
            BlueprintButton(stringResource(R.string.processing_download_on_wifi), { pending = null; start(true) },
                modifier = Modifier.testTag("download-on-wifi"))
            BlueprintButton(stringResource(R.string.processing_download), { pending = null; start(false) }, tone = ButtonTone.PRIMARY)
        }) { modelBytes?.let { BlueprintDialogText(stringResource(R.string.processing_cellular_body, modelSize(it))) } }
    }
    return { start -> if (context.metered()) pending = start else start(false) }
}

/** "Downloading model… 42%" or "Waiting for Wi-Fi" while the download is under way; null otherwise. */
@Composable
fun modelDownloadStatus(download: ModelDownloadState): String? = when (download.phase) {
    ModelDownloadPhase.DOWNLOADING -> stringResource(R.string.processing_download_progress, modelPercent(download.info?.progress))
    ModelDownloadPhase.WAITING_FOR_WIFI -> stringResource(R.string.processing_waiting_for_wifi)
    ModelDownloadPhase.IDLE, ModelDownloadPhase.FAILED -> null
}

/** "412 MB of 988 MB" while it downloads, or while part of the model is already here. */
@Composable
fun modelDownloadBytes(info: LocalEngineInfo?, downloading: Boolean): String? {
    val total = info?.modelBytes ?: return null
    val done = downloadedBytes(info) ?: if (downloading) 0L else return null
    return stringResource(R.string.processing_download_bytes, modelSize(done), modelSize(total))
}

/** The start button's words: "Resume download" once part of the model is on disk. */
@Composable
fun modelDownloadLabel(info: LocalEngineInfo?): String =
    stringResource(if (info?.progress != null) R.string.processing_resume_download else R.string.processing_prepare)

/** The progress line and the bytes line under it — what Settings, the card and the banner show. */
@Composable
fun ModelDownloadLines(download: ModelDownloadState, info: LocalEngineInfo?) {
    val palette = blueprint
    modelDownloadStatus(download)?.let { status ->
        if (download.phase == ModelDownloadPhase.DOWNLOADING) {
            LoadingText(status, MaterialTheme.typography.bodySmall, palette.textMuted, Modifier.testTag("model-progress"))
        } else {
            Text(status, style = MaterialTheme.typography.bodySmall, color = palette.textMuted, modifier = Modifier.testTag("model-progress"))
        }
    }
    modelDownloadBytes(info, download.phase == ModelDownloadPhase.DOWNLOADING)?.let { bytes ->
        Text(bytes, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
    }
}

/**
 * The first-run card: what on-device transcription needs, once, and the two answers — end-aligned,
 * the committing one last (docs/09 화면 원칙 8). While the download runs it is the progress and a
 * quiet "Cancel download"; it goes away with the model's arrival (see [modelPromptVisible]).
 */
@Composable
fun ModelPromptCard(
    download: ModelDownloadState,
    /** The Record tab's own reading of the engine for the saved language; it knows the size. */
    info: LocalEngineInfo,
    onDownload: () -> Unit,
    onNotNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    // The engine reading that moves while the download runs; the card's own one otherwise.
    val reading = download.info ?: info
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(palette.line, palette.grid, shape)
            .background(palette.surface, shape)
            .padding(Space.m)
            .testTag("model-prompt"),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(stringResource(R.string.model_prompt_title), style = MaterialTheme.typography.titleMedium, color = palette.text)
        info.modelBytes?.let { size ->
            Text(
                stringResource(R.string.model_prompt_body, modelSize(size)),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
            )
        }
        ModelDownloadLines(download, reading)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            if (download.active) {
                BlueprintButton(stringResource(R.string.processing_cancel_download), onCancel, tone = ButtonTone.QUIET)
            } else {
                BlueprintButton(stringResource(R.string.action_not_now), onNotNow, tone = ButtonTone.QUIET,
                    modifier = Modifier.testTag("model-prompt-not-now"))
                BlueprintButton(modelDownloadLabel(reading), onDownload, tone = ButtonTone.PRIMARY,
                    modifier = Modifier.testTag("model-prompt-download"))
            }
        }
    }
}
