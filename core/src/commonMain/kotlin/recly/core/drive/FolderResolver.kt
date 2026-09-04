@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import recly.core.platform.CoreDeps

/**
 * Turns a rendered `folder` template (`recly/2026/2026-08`) into a Drive folder id, one segment at a
 * time from `root`. With `drive.file` scope the app only ever sees folders it made itself, so a
 * cached id is almost always still good — but the user can move or trash one, hence the daily
 * re-verify and the 404 → recreate path.
 */
class FolderResolver(
    private val api: DriveApi,
    private val store: DriveStore,
    private val deps: CoreDeps,
) {
    suspend fun resolve(path: String): String {
        var parent = ROOT
        var walked = ""
        for (segment in segments(path)) {
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            parent = resolveSegment(walked, segment, parent)
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

    private suspend fun resolveSegment(path: String, name: String, parent: String): String {
        val now = deps.clock.now()
        val cached = store.folder(path)
        if (cached != null) {
            if (now - cached.checkedAt < REVERIFY_AFTER) return cached.folderId
            if (api.getFile(cached.folderId, "id") != null) {
                store.putFolder(path, cached.folderId, now)
                return cached.folderId
            }
            store.forgetFolder(path)
        }
        val found = api.findChild(parent, name, DriveApi.FOLDER_MIME)
            ?: api.createFolder(name, parent)
        store.putFolder(path, found.id, now)
        return found.id
    }

    private companion object {
        /** Drive's alias for My Drive; it works both in `q` and as a `parents` entry. */
        const val ROOT = "root"
        val REVERIFY_AFTER = 24.hours
    }
}
