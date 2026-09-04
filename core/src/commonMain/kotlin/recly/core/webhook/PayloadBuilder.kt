@file:OptIn(ExperimentalTime::class)

package recly.core.webhook

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import recly.core.drive.DriveUploadRunner
import recly.core.drive.string
import recly.core.job.StepOutput
import recly.core.job.priorOutput
import recly.core.job.type
import recly.core.model.RecordingMeta
import recly.core.model.Workflow
import recly.core.model.isoUtc
import recly.core.model.wire
import recly.core.platform.DeviceInfo
import recly.core.transcribe.TranscribeRunner

/**
 * The docs/04 payload. Pure: everything it needs is already in the recording's meta and in the
 * output the `drive.upload` step left behind, so a retry rebuilds a byte-identical body except for
 * its timestamp.
 */
object PayloadBuilder {
    /**
     * [prior] is the executor's map of outputs from the steps that already succeeded. The Drive
     * fields come from the last `drive.upload` among them — "last" in workflow order, which is the
     * one whose files are freshest. Without such a step, `folder` and every `files[].drive` are
     * null and `files` falls back to the parts the recording holds locally.
     */
    fun build(
        meta: RecordingMeta,
        workflow: Workflow,
        prior: Map<String, StepOutput>,
        device: DeviceInfo,
        stepRunId: String,
        now: Instant,
    ): WebhookPayload {
        val upload = workflow.priorOutput(prior, DriveUploadRunner.TYPE)
        // docs/08: the transcript rides along in `files[]` when its step ran, and its output carries
        // the same `files` shape the upload does — one reader for both.
        val results = workflow.steps
            .filter { it.type == TranscribeRunner.TYPE && it.id in prior }
            .flatMap { uploadedFiles(prior.getValue(it.id).json) }
        return WebhookPayload(
            type = WebhookPayload.RECORDING_COMPLETED,
            id = stepRunId,
            timestamp = now.isoUtc(),
            data = WebhookData(
                recording = WebhookRecording(
                    recordingId = meta.recordingId,
                    source = meta.source,
                    platform = meta.platform,
                    title = meta.title,
                    startedAt = meta.startedAt,
                    // The schema needs both; a webhook only fires for a finalized recording, so
                    // this is a defensive floor rather than a case the executor reaches.
                    endedAt = meta.endedAt ?: meta.startedAt,
                    durationSec = meta.durationSec ?: 0.0,
                    timezone = meta.timezone,
                    tracks = meta.tracks,
                    context = meta.context,
                ),
                files = (upload?.let(::uploadedFiles) ?: localFiles(meta)) + results,
                folder = upload?.let(::folder),
                workflow = WebhookWorkflow(workflow.id, workflow.name),
                device = WebhookDevice(device.deviceId, device.platform, device.name),
            ),
        )
    }

    /** The `drive.upload` output shape, flattened the other way: its `fileId`/`webViewLink` pair
     * becomes the nested `drive` object docs/04 promises. */
    private fun uploadedFiles(upload: JsonObject): List<WebhookFile> =
        upload["files"]?.jsonArray.orEmpty().map { element ->
            val file = element.jsonObject
            val fileId = file.string("fileId")
            val link = file.string("webViewLink")
            WebhookFile(
                part = file.getValue("part").jsonPrimitive.int,
                track = file.string("track").orEmpty(),
                name = file.string("name").orEmpty(),
                bytes = file.getValue("bytes").jsonPrimitive.long,
                sha256 = file.string("sha256").orEmpty(),
                drive = if (fileId != null && link != null) WebhookDriveFile(fileId, link) else null,
            )
        }

    /** No upload ran, so the receiver gets the file list without anywhere to fetch it from. The
     * meta file is not among them: its size and hash are only known once something reads it. */
    private fun localFiles(meta: RecordingMeta): List<WebhookFile> = meta.parts.map {
        WebhookFile(it.part, it.track.wire, it.file, it.bytes, it.sha256, null)
    }

    private fun folder(upload: JsonObject): WebhookFolder? {
        val path = upload.string("path") ?: return null
        val folderId = upload.string("folderId")
        val link = upload.string("folderWebViewLink")
        return WebhookFolder(
            path = path,
            drive = if (folderId != null && link != null) WebhookDriveFolder(folderId, link) else null,
        )
    }
}
