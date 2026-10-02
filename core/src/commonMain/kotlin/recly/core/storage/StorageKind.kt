package recly.core.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * docs/03 "저장 위치" (ADR-024): where a recording's files go — the user's Google Drive, or the app's
 * folder in the user's iCloud Drive (iPhone and Mac only).
 *
 * Every file and folder id carries its storage ([ofId]): a Drive id is Drive's own, an iCloud id is
 * [ICLOUD_PREFIX] and a path under the container's `Documents`. An id written into a row or a step
 * output therefore finds its way back to where it came from, whatever the setting says now.
 */
@Serializable
enum class StorageKind {
    @SerialName("drive")
    DRIVE,

    @SerialName("icloud")
    ICLOUD,
    ;

    companion object {
        const val ICLOUD_PREFIX: String = "icloud:"

        /** Drive ids are letters, digits, `-` and `_`, so the prefix cannot be one. */
        fun ofId(id: String): StorageKind = if (id.startsWith(ICLOUD_PREFIX)) ICLOUD else DRIVE
    }
}
