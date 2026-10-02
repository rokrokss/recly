@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import recly.core.platform.CoreDeps
import recly.core.storage.CloudFiles
import recly.core.storage.StorageKind

/**
 * Turns a rendered `folder` template (`recly/2026/2026-08`) into a folder id, one segment at a time
 * from the storage's root. With `drive.file` scope the app only ever sees the Drive folders it made
 * itself, so a cached id is almost always still good — but the user can move or trash one, hence the
 * daily re-verify and the 404 → recreate path.
 *
 * An iCloud folder's id is its path (docs/03 "Storage location"), so there is nothing to cache: each segment
 * is looked at on the device and made when it is missing.
 */
class FolderResolver(
    private val api: CloudFiles,
    private val store: DriveStore,
    private val deps: CoreDeps,
) {
    suspend fun resolve(path: String, files: CloudFiles = api): String {
        val cached = StorageKind.ofId(files.rootId) == StorageKind.DRIVE
        var parent = files.rootId
        var walked = ""
        for (segment in segments(path)) {
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            parent = if (cached) {
                resolveSegment(walked, segment, parent, files)
            } else {
                (files.findChild(parent, segment, DriveApi.FOLDER_MIME) ?: files.createFolder(segment, parent)).id
            }
        }
        return parent
    }

    /**
     * Drops every cached id along [path]. Called when Drive answered 404 for one of these folders
     * inside a run: the 24 h re-verify is the routine check, this is the one that reacts now.
     */
    suspend fun invalidate(path: String) {
        var walked = ""
        for (segment in segments(path)) {
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            store.forgetFolder(walked)
        }
    }

    private fun segments(path: String): List<String> =
        path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    private suspend fun resolveSegment(path: String, name: String, parent: String, files: CloudFiles): String {
        val now = deps.clock.now()
        val cached = store.folder(path)
        if (cached != null) {
            if (now - cached.checkedAt < REVERIFY_AFTER) return cached.folderId
            if (files.getFile(cached.folderId, "id") != null) {
                store.putFolder(path, cached.folderId, now)
                return cached.folderId
            }
            store.forgetFolder(path)
        }
        val found = files.findChild(parent, name, DriveApi.FOLDER_MIME)
            ?: files.createFolder(name, parent)
        store.putFolder(path, found.id, now)
        return found.id
    }

    private companion object {
        val REVERIFY_AFTER = 24.hours
    }
}
