package recly.core.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Drive answers with more fields than we ask for on a bad day; never let that fail a parse. */
internal val driveJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** The `files.create` metadata part — the same JSON for multipart, resumable and folder creation. */
data class DriveFileMeta(
    val name: String,
    val parents: List<String>,
    val mimeType: String,
    val appProperties: Map<String, String> = emptyMap(),
    val description: String? = null,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        putJsonArray("parents") { parents.forEach { add(it) } }
        put("mimeType", mimeType)
        if (appProperties.isNotEmpty()) {
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }
        if (description != null) put("description", description)
    }
}

/** What we keep of a Drive file: [md5] is the upload's success condition (docs/03). */
data class DriveFile(
    val id: String,
    val name: String,
    val md5: String?,
    val webViewLink: String?,
) {
    internal companion object {
        /** Returns null when the payload carries no `id` — a session-start response, or an error. */
        fun from(json: JsonObject?): DriveFile? {
            val id = json?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
            return DriveFile(
                id = id,
                name = json["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                md5 = json["md5Checksum"]?.jsonPrimitive?.contentOrNull,
                webViewLink = json["webViewLink"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/** `step_run.state_json` for `drive.upload` (docs/10). */
@Serializable
data class DriveUploadState(
    val folderId: String? = null,
    val folderWebViewLink: String? = null,
    val files: Map<String, UploadState> = emptyMap(),
) {
    internal fun with(key: String, file: UploadState): DriveUploadState = copy(files = files + (key to file))

    internal fun toJson(): JsonObject = driveJson.encodeToJsonElement(serializer(), this).jsonObject

    internal companion object {
        fun from(json: JsonObject?): DriveUploadState =
            if (json == null) DriveUploadState() else driveJson.decodeFromJsonElement(serializer(), json)
    }
}

/**
 * One file's resume point. [sessionUri] plus [offset] is what lets a killed process continue a
 * chunk later; [fileId] means the file is already on Drive.
 */
@Serializable
data class UploadState(
    val sessionUri: String? = null,
    val offset: Long = 0,
    val fileId: String? = null,
    /** ISO-8601 UTC. Drive drops a resumable session after a week (docs/10). */
    val startedAt: String? = null,
    /**
     * A file we uploaded, found corrupt, and have not managed to delete yet. Written before the
     * DELETE goes out, so a crash or a failed DELETE cannot leave a wrong-content file sitting in
     * the folder under the right name, where the next run would find it by name.
     */
    val pendingDelete: String? = null,
)
