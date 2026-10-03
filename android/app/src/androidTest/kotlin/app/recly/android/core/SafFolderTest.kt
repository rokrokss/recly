package app.recly.android.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.recly.android.settings.AppSettings
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in coverage of [SafFolder] against the folder the app was really given: the provider behind
 * it (the device's own storage, for a folder under Documents), its names and its overwrites. A
 * tree grant comes only from the system picker, so pick a folder in Settings → Storage → Local
 * folder first; without one this is skipped. Everything happens in a folder of its own under the
 * picked one, which is removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class SafFolderTest {

    @Test
    fun filesLandUnderTheirExactNamesAndAreReplacedInPlace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(context)
        assumeTrue("pick a local folder in Settings first", settings.localFolder.first() != null)
        val folder = SafFolder(context, settings, Dispatchers.IO)
        assertTrue(folder.available())
        // One folder at a time: a pick gives the grant of the one before it back.
        assertEquals(1, context.contentResolver.persistedUriPermissions.size)

        val base = "saf-test-${System.currentTimeMillis()}"
        val dir = "recly/memo/$base"
        val names = listOf(
            "${base}_p001_mono.m4a",
            "$base.meta.json",
            "$base.transcript.json",
            "$base.transcript.txt",
            "$base.transcript.md",
        )
        try {
            names.forEach { name -> folder.importFile(local(context, "first $name"), "$dir/$name") }
            assertTrue(folder.isDirectory("recly/memo"))
            assertTrue(folder.isDirectory(dir))
            names.forEach { name ->
                assertEquals("first $name".length.toLong(), folder.size("$dir/$name"), name)
                assertFalse(folder.isDirectory("$dir/$name"), name)
            }

            // A second copy over the first — shorter, so a write that did not truncate would show.
            val meta = "$dir/$base.meta.json"
            folder.importFile(local(context, "second"), meta)
            assertEquals(6L, folder.size(meta))
            assertEquals("second".encodeToByteArray().toByteString().md5().hex(), folder.md5(meta))
            val back = File(context.cacheDir, "saf-back")
            assertTrue(folder.exportFile(meta, back.absolutePath))
            assertContentEquals("second".encodeToByteArray(), back.readBytes())

            // Nothing but the names asked for, as the provider itself lists them: no `name (1)` from
            // the second copy, and no extension a MIME type added.
            val tree = Uri.parse(settings.localFolder.first())
            assertEquals(names.toSet(), namesUnder(context, tree, dir))

            assertNull(folder.size("$dir/missing.m4a"))
            assertNull(folder.md5("$dir/missing.m4a"))
            assertFalse(folder.exportFile("$dir/missing.m4a", back.absolutePath))
        } finally {
            folder.delete("recly/memo/$base")
        }
        assertFalse(folder.isDirectory(dir))
        // Missing is not an error.
        folder.delete(dir)
    }

    /** The display names under [path], walked from the tree's root the way a file manager would. */
    private fun namesUnder(context: Context, tree: Uri, path: String): Set<String> {
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (segment in path.split('/')) id = children(context, tree, id).getValue(segment)
        return children(context, tree, id).keys
    }

    private fun children(context: Context, tree: Uri, id: String): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        return context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
    }

    private fun local(context: Context, text: String): String =
        File(context.cacheDir, "saf-source").apply { writeText(text) }.absolutePath
}
