package recly.core.webhook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import recly.core.model.Context
import recly.core.model.Platform
import recly.core.model.Source
import recly.core.model.Track

/**
 * `spec/webhook.payload.schema.json`, mirrored 1:1.
 *
 * Unlike the rest of the core this serialises with `explicitNulls`: the schema *requires*
 * `data.folder` and `files[].drive` to be there and lets them be `null`, so dropping a null key
 * would emit a document that fails validation (docs/04 "없으면 null").
 */
internal val webhookJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class WebhookPayload(
    val type: String,
    val id: String,
    val timestamp: String,
    val data: WebhookData,
) {
    fun encode(): String = webhookJson.encodeToString(this)

    companion object {
        /** v1 has one event; receivers branch on it from day one so later types do not break them. */
        const val RECORDING_COMPLETED = "recording.completed"
    }
}

@Serializable
data class WebhookData(
    val recording: WebhookRecording,
    val files: List<WebhookFile>,
    val folder: WebhookFolder?,
    val workflow: WebhookWorkflow,
    val device: WebhookDevice,
)

@Serializable
data class WebhookRecording(
    val recordingId: String,
    val source: Source,
    val platform: Platform,
    val title: String? = null,
    val startedAt: String,
    val endedAt: String,
    val durationSec: Double,
    val timezone: String,
    val tracks: List<Track>,
    val context: Context? = null,
)

/** [track] is a string, not [Track]: `meta.json` rides along as `"meta"` (docs/04). */
@Serializable
data class WebhookFile(
    val part: Int,
    val track: String,
    val name: String,
    val bytes: Long,
    val sha256: String,
    val drive: WebhookDriveFile?,
)

@Serializable
data class WebhookDriveFile(val fileId: String, val webViewLink: String)

@Serializable
data class WebhookFolder(val path: String, val drive: WebhookDriveFolder?)

@Serializable
data class WebhookDriveFolder(val folderId: String, val webViewLink: String)

@Serializable
data class WebhookWorkflow(val id: String, val name: String)

@Serializable
data class WebhookDevice(val id: String, val platform: Platform, val name: String)
