package app.recly.android.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.recly.android.settings.AppSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source
import recly.core.storage.LocalFolder
import recly.core.storage.StorageKind
import recly.core.storage.StorageUnavailableException

/**
 * docs/03 "Storage location" — Local folder on the phone: a document tree the user picked with
 * `ACTION_OPEN_DOCUMENT_TREE`, reached through the Storage Access Framework under the read · write
 * grant taken at the pick. Which tree it is lives in [AppSettings], on this device alone, and is
 * read on every call, so a new pick applies at once.
 *
 * A path is walked one display name at a time from the tree's root. Two things a provider does that
 * a path on disk does not are held off here: `createDocument` never replaces — a name that is taken
 * becomes `name (1)` — so a file that is there is found and truncated instead; and a provider may
 * give a new document another name than the one asked for (an extension for its MIME type), so
 * every document made is read back, and one that did not land under its exact name is taken away
 * again rather than left to become a second copy on the next attempt.
 *
 * A grant taken back in the middle of a call (`SecurityException`) is the folder becoming
 * unusable, so it is [StorageUnavailableException] — the upload waits for a new pick rather than
 * spending its attempts.
 */
class SafFolder(
    context: Context,
    private val settings: AppSettings,
    private val io: CoroutineDispatcher,
) : LocalFolder {
    private val resolver: ContentResolver = context.contentResolver

    override suspend fun available(): Boolean = withContext(io) {
        try {
            val tree = tree() ?: return@withContext false
            resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission } &&
                document(tree, DocumentsContract.getTreeDocumentId(tree))?.directory == true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun isDirectory(path: String): Boolean = access { tree -> find(tree, path)?.directory == true }

    override suspend fun size(path: String): Long? = access { tree ->
        val file = find(tree, path)?.takeIf { !it.directory } ?: return@access null
        file.size ?: resolver.openFileDescriptor(file.uri, "r")?.use { it.statSize }
    }

    override suspend fun makeDirectories(path: String) {
        access { tree -> directories(tree, folderSegments(path)) }
    }

    override suspend fun importFile(source: String, path: String) = access { tree ->
        val segments = folderSegments(path)
        val parent = directories(tree, segments.dropLast(1))
        val name = segments.last()
        val existing = child(tree, parent.id, name)
        if (existing?.directory == true) throw IOException("'$path' is a folder in the local folder")
        val target = existing ?: create(tree, parent, FILE_MIME, name)
        // "wt", not "w": a provider may leave the tail of a longer file there under plain "w".
        val out = resolver.openOutputStream(target.uri, "wt") ?: throw IOException("could not write '$path'")
        out.use { sink -> File(source).inputStream().use { it.copyTo(sink) } }
        Unit
    }

    override suspend fun exportFile(path: String, destination: String): Boolean = access { tree ->
        val file = find(tree, path)?.takeIf { !it.directory } ?: return@access false
        val input = resolver.openInputStream(file.uri) ?: return@access false
        input.use { source -> File(destination).outputStream().use { source.copyTo(it) } }
        true
    }

    override suspend fun md5(path: String): String? = access { tree ->
        val file = find(tree, path)?.takeIf { !it.directory } ?: return@access null
        val input = resolver.openInputStream(file.uri) ?: return@access null
        val hashing = HashingSource.md5(input.source())
        hashing.buffer().use { it.readAll(blackholeSink()) }
        hashing.hash.hex()
    }

    /** By the provider's own delete, which takes a folder with everything in it (`DocumentsProvider.deleteDocument`). */
    override suspend fun delete(path: String) = access { tree ->
        val doc = find(tree, path) ?: return@access
        if (!DocumentsContract.deleteDocument(resolver, doc.uri)) throw IOException("could not delete '$path' in the local folder")
    }

    /**
     * The tree the user picked, by its grant: taken for good, kept as the one the folder reaches,
     * and the grant of the tree it replaces given back — one folder at a time is all this app holds.
     */
    suspend fun pick(tree: Uri) = withContext(io) {
        resolver.takePersistableUriPermission(tree, GRANT)
        val before = tree()
        settings.setLocalFolder(tree.toString())
        if (before != null && before != tree) {
            try {
                resolver.releasePersistableUriPermission(before, GRANT)
            } catch (e: SecurityException) {
                // Already given back — taken away in the system's settings, or by an uninstall
                // of the provider. Nothing is held either way.
            }
        }
    }

    /** What the settings row calls the picked folder, or null while none is picked ([folderLabel]). */
    suspend fun label(): String? = withContext(io) {
        val tree = tree() ?: return@withContext null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val name = try {
            document(tree, id)?.name
        } catch (e: Exception) {
            null
        }
        folderLabel(tree.authority, id, name)
    }

    private suspend fun tree(): Uri? = settings.localFolder.first()?.let(Uri::parse)

    /** Every call but [available]: the picked tree, or the folder is not there to use. */
    private suspend fun <T> access(block: (Uri) -> T): T = withContext(io) {
        val tree = tree() ?: throw StorageUnavailableException(StorageKind.FOLDER, "no local folder is picked")
        try {
            block(tree)
        } catch (e: SecurityException) {
            throw StorageUnavailableException(StorageKind.FOLDER, "the local folder's grant is gone: ${e.message}")
        }
    }

    private fun root(tree: Uri): Doc {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return document(tree, id) ?: throw IOException("the local folder is gone")
    }

    private fun find(tree: Uri, path: String): Doc? {
        var doc = root(tree)
        for (segment in folderSegments(path)) {
            if (!doc.directory) return null
            doc = child(tree, doc.id, segment) ?: return null
        }
        return doc
    }

    /** [segments] under the root as folders, each made where it is missing. */
    private fun directories(tree: Uri, segments: List<String>): Doc {
        var doc = root(tree)
        for (segment in segments) {
            val next = child(tree, doc.id, segment) ?: create(tree, doc, Document.MIME_TYPE_DIR, segment)
            if (!next.directory) throw IOException("'$segment' is a file in the local folder, not a folder")
            doc = next
        }
        return doc
    }

    private fun create(tree: Uri, parent: Doc, mime: String, name: String): Doc {
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name)
            ?: throw IOException("could not make '$name' in the local folder")
        val made = document(tree, DocumentsContract.getDocumentId(uri))
            ?: throw IOException("'$name' was made but cannot be read back")
        if (made.name != name) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw IOException("the local folder named '$name' '${made.name}'")
        }
        return made
    }

    private fun child(tree: Uri, parentId: String, name: String): Doc? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(NAME) == name) return doc(tree, cursor)
            }
        }
        return null
    }

    private fun document(tree: Uri, id: String): Doc? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) doc(tree, cursor) else null
        }
    }

    private fun doc(tree: Uri, cursor: android.database.Cursor): Doc {
        val id = cursor.getString(ID)
        return Doc(
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
            id = id,
            name = cursor.getString(NAME),
            directory = cursor.getString(MIME) == Document.MIME_TYPE_DIR,
            size = if (cursor.isNull(SIZE)) null else cursor.getLong(SIZE),
        )
    }

    private data class Doc(val uri: Uri, val id: String, val name: String?, val directory: Boolean, val size: Long?)

    private companion object {
        /**
         * The type every file is made with. The external-storage provider keeps a name exactly as
         * given only for this one: for any other type it appends that type's extension unless the
         * name already ends in it (`FileUtils.splitFileName`), and `.meta.json` · `.md` · `.m4a`
         * are not all the extensions their types map back to on every release.
         */
        const val FILE_MIME = "application/octet-stream"

        const val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        const val ID = 0
        const val NAME = 1
        const val MIME = 2
        const val SIZE = 3
    }
}

/** A core path's folder names, in order: `/`-separated, with `""` the picked folder itself. */
internal fun folderSegments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

/**
 * docs/03 "Storage location": the picked folder as the settings row names it. On the device's own storage
 * the tree's document id is the volume and the path under it (`primary:Documents/Notes`), and the
 * path is the name a person knows the folder by; the volume's root, and any other provider's tree,
 * whose ids mean nothing to read, go by the folder's display name.
 */
internal fun folderLabel(authority: String?, treeDocumentId: String, displayName: String?): String {
    if (authority == EXTERNAL_STORAGE_AUTHORITY) {
        val path = treeDocumentId.substringAfter(':', "").trim('/')
        if (path.isNotEmpty()) return path
    }
    return displayName?.takeIf { it.isNotBlank() } ?: treeDocumentId
}

/** The system's own provider for the device's storage and SD cards (`ExternalStorageProvider`). */
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
