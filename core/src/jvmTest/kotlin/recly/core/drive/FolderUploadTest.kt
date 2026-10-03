@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import recly.core.job.StepOutcome
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Step
import recly.core.storage.StorageKind

/**
 * docs/03 "Storage location" — Local folder: `drive.upload` into the folder the user picked. The copy is the
 * upload, so the step is done at once; nothing is kept beside the recording but its own files; and
 * a folder that is not picked or not there is waited for, not failed.
 */
class FolderUploadTest {
    private val step = Step.DriveUpload(id = "up", store = StorageKind.FOLDER)

    @Test
    fun `the parts and then the meta are copied into the picked folder, and the step is done`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES, folder = true)

        val output = assertIs<StepOutcome.Done>(h.outcome(step)).output.json

        assertEquals("folder:recly/2026/2026-08/${h.base}", output.string("folderId"))
        val files = output["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, files.size)
        assertTrue(files.all { it.string("fileId")!!.startsWith(StorageKind.FOLDER_PREFIX) })
        val folder = recordingFolder(h)
        assertEquals(
            setOf(h.partName(1), h.partName(2), h.metaName()),
            h.fs.list(folder).map { it.name }.toSet(),
            "nothing beside the recording's own files: no property file, no partial copy",
        )
        assertTrue(h.fs.read(folder / h.partName(1)) { readByteArray() }.contentEquals(h.fs.read(h.dir / h.partName(1)) { readByteArray() }))
        assertTrue(h.drive.requests.isEmpty(), "nothing went to Drive")
    }

    @Test
    fun `a rerun finds every file in place and copies nothing again`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES, folder = true)
        assertIs<StepOutcome.Done>(h.outcome(step))
        val folder = recordingFolder(h)
        val written = h.fs.list(folder).associateWith { h.fs.metadata(it).lastModifiedAtMillis }

        h.clock.advance(5.minutes)
        h.state = null
        assertIs<StepOutcome.Done>(h.outcome(step))

        assertEquals(written, h.fs.list(folder).associateWith { h.fs.metadata(it).lastModifiedAtMillis })
    }

    @Test
    fun `while no folder is picked the step waits and writes nothing`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, folder = true)
        h.folderRoot = null

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.FOLDER_UNAVAILABLE, CoreMessageRef.parse(outcome.reason!!)!!.message)
        assertTrue(h.fs.list(DriveHarness.FOLDER_ROOT.toPath()).isEmpty())
    }

    @Test
    fun `a picked folder that is gone — a removed drive — is a wait, and the folder is not made again`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, folder = true)
        h.folderRoot = "/Volumes/Backup/Notes"

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.FOLDER_UNAVAILABLE, CoreMessageRef.parse(outcome.reason!!)!!.message)
        assertFalse(h.fs.exists("/Volumes".toPath()))
    }

    @Test
    fun `a device without a local folder waits rather than sending the recording to Drive`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.FOLDER_UNAVAILABLE, CoreMessageRef.parse(outcome.reason!!)!!.message)
        assertTrue(h.drive.requests.isEmpty())
    }

    @Test
    fun `another folder picked between runs gets the rest, under the same path`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, folder = true)
        h.state = DriveUploadState(folderId = "folder:recly/2026/2026-08/${h.base}").toJson()
        h.fs.createDirectories("/moved".toPath())
        h.folderRoot = "/moved"

        assertIs<StepOutcome.Done>(h.outcome(step))

        assertTrue(h.fs.exists("/moved/recly/2026/2026-08/${h.base}/${h.metaName()}".toPath()))
    }

    private fun recordingFolder(h: DriveHarness): Path = "${DriveHarness.FOLDER_ROOT}/recly/2026/2026-08/${h.base}".toPath()

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
}
