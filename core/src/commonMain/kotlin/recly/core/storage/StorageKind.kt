package recly.core.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * docs/03 "Storage location" (ADR-024): where a recording's files go — the user's Google Drive, the app's
 * folder in the user's iCloud Drive (iPhone and Mac only), or a local folder the user picked on this
 * device (iPhone, Mac, Windows and the Android phone).
 *
 * Every file and folder id carries its storage ([ofId]): a Drive id is Drive's own, an iCloud id is
 * [ICLOUD_PREFIX] and a path under the container's `Documents`, and a local folder id is
 * [FOLDER_PREFIX] and a path under the folder the user picked. An id written into a row or a step
 * output therefore finds its way back to where it came from, whatever the setting says now.
 */
@Serializable
enum class StorageKind {
    @SerialName("drive")
    DRIVE,

    @SerialName("icloud")
    ICLOUD,

    @SerialName("folder")
    FOLDER,
    ;

    companion object {
        const val ICLOUD_PREFIX: String = "icloud:"
        const val FOLDER_PREFIX: String = "folder:"

        /** Drive ids are letters, digits, `-` and `_`, so neither prefix can be one. */
        fun ofId(id: String): StorageKind = when {
            id.startsWith(ICLOUD_PREFIX) -> ICLOUD
            id.startsWith(FOLDER_PREFIX) -> FOLDER
            else -> DRIVE
        }
    }
}
