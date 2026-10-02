@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.DriveApi
import recly.core.drive.DriveFileMeta
import recly.core.drive.DriveNotFound
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.testing.FakeClock
import recly.core.testing.FakeUbiquityContainer
import recly.core.testing.testDeps

/**
 * docs/03 "Storage location": the app's iCloud folder held to Drive's shapes — a folder's description and
 * properties in `{folder}.folder.json`, ids that are paths, and an upload that is done only once
 * iCloud says it holds the file.
 */
class ICloudFilesTest {
    private val clock = FakeClock()
    private val fs = FakeFileSystem(clock)
    private val container = FakeUbiquityContainer(fs, clock)
    private val deps = testDeps(clock = clock, fileSystem = fs, ubiquity = container)
    private val files = ICloudFiles(container, deps)

    private val base = "20260826T010000Z_desktop_01J9ABCD"
    private val folder = "recly/memo/2026-08/$base"

    @Test
    fun `a recording folder keeps its title and properties in its folder file and is listed like Drive's`() = runBlocking<Unit> {
        val parent = resolve("recly/memo/2026-08")
        val made = files.createFolder(base, parent, "주간 회의", mapOf("recordingId" to RECORDING_ID, "workflowId" to "W"))

        assertEquals("icloud:$folder", made.id)
        assertTrue(container.entries.containsKey("$folder/$base.folder.json"))
        val listed = files.recordingFolders().single()
        assertEquals("icloud:$folder", listed.string("id"))
        assertEquals(base, listed.string("name"))
        assertEquals("주간 회의", listed.string("description"))
        assertEquals(RECORDING_ID, listed["appProperties"]!!.jsonObject.string("recordingId"))
        assertNotNull(listed.string("createdTime"))
    }

    @Test
    fun `the folders a path walks through have no folder file and are not recordings`() = runBlocking<Unit> {
        resolve("recly/memo/2026-08")

        assertTrue("recly/memo/2026-08" in container.directories)
        assertTrue(files.recordingFolders().isEmpty())
    }

    @Test
    fun `making a folder that is already there keeps the properties it was made with`() = runBlocking<Unit> {
        val parent = resolve("recly/memo/2026-08")
        files.createFolder(base, parent, "처음 제목", mapOf("recordingId" to RECORDING_ID))
        files.createFolder(base, parent, "다른 제목", mapOf("recordingId" to RECORDING_ID))

        assertEquals("처음 제목", files.recordingFolders().single().string("description"))
    }

    @Test
    fun `a folder whose making stopped before its file gets one when it is found again`() = runBlocking<Unit> {
        container.makeDirectories(folder)
        val found = assertNotNull(files.findChild("icloud:recly/memo/2026-08", base, DriveApi.FOLDER_MIME))

        files.completeFolder(found, "제목", mapOf("recordingId" to RECORDING_ID))

        assertEquals(RECORDING_ID, files.recordingFolders().single()["appProperties"]!!.jsonObject.string("recordingId"))
    }

    @Test
    fun `the pending marker merges into the properties and a rename changes the description`() = runBlocking<Unit> {
        val made = files.createFolder(base, resolve("recly/memo/2026-08"), "제목", mapOf("recordingId" to RECORDING_ID))

        files.updateAppProperties(made.id, mapOf("pending" to "transcribe", "pendingAt" to "2026-08-26T01:00:00.000Z"))
        files.updateDescription(made.id, "새 제목")

        val listed = files.recordingFolders().single()
        val properties = listed["appProperties"]!!.jsonObject
        assertEquals(RECORDING_ID, properties.string("recordingId"))
        assertEquals("transcribe", properties.string("pending"))
        assertEquals("새 제목", listed.string("description"))
    }

    @Test
    fun `a folder's children are its files with their sizes, without the folder file`() = runBlocking<Unit> {
        val made = files.createFolder(base, resolve("recly/memo/2026-08"), null, mapOf("recordingId" to RECORDING_ID))
        container.arrive("$folder/${base}_p001_mono.m4a", ByteArray(10))
        container.arrive("$folder/$base.meta.json", "{}".encodeToByteArray())

        val children = files.children(made.id).associate { it.name to it.size }

        assertEquals(mapOf("${base}_p001_mono.m4a" to 10L, "$base.meta.json" to 2L), children)
    }

    @Test
    fun `an upload is a copy into the folder, and settled once iCloud holds it`() = runBlocking<Unit> {
        val made = files.createFolder(base, resolve("recly/memo/2026-08"), null, mapOf("recordingId" to RECORDING_ID))
        val local = "/data/part.m4a".toPath()
        fs.createDirectories(local.parent!!)
        fs.write(local) { writeUtf8("audio") }

        val file = files.uploadResumable(DriveFileMeta("part.m4a", listOf(made.id), "audio/mp4"), local, 5, null) {}

        assertEquals("icloud:$folder/part.m4a", file.id)
        assertEquals(files.findChild(made.id, "part.m4a")!!.md5, file.md5)
        assertFalse(files.settled(listOf(file.id)))
        container.settle()
        assertTrue(files.settled(listOf(file.id)))
    }

    @Test
    fun `an iCloud account out of space parks the upload the way a full Drive does`() = runBlocking<Unit> {
        val made = files.createFolder(base, resolve("recly/memo/2026-08"), null, emptyMap())
        val file = files.multipartUpload(DriveFileMeta("a.json", listOf(made.id), "application/json"), "{}".encodeToByteArray())
        container.refuse("$folder/a.json")

        val failure = assertFailsWith<StepFailure> { files.settled(listOf(file.id)) }

        assertTrue(failure.needsSpace)
        assertEquals(CoreMessage.ICLOUD_STORAGE_FULL, CoreMessageRef.parse(failure.reason)!!.message)
    }

    @Test
    fun `a file deleted from the folder before iCloud took it is not found, so the upload starts over`() = runBlocking<Unit> {
        val made = files.createFolder(base, resolve("recly/memo/2026-08"), null, emptyMap())
        val file = files.multipartUpload(DriveFileMeta("a.json", listOf(made.id), "application/json"), "{}".encodeToByteArray())
        container.delete("$folder/a.json")

        assertFailsWith<DriveNotFound> { files.settled(listOf(file.id)) }
    }

    @Test
    fun `a download brings down a file that is only listed here`() = runBlocking<Unit> {
        container.arrive("$folder/$base.meta.json", "meta".encodeToByteArray())

        assertEquals("meta", files.download("icloud:$folder/$base.meta.json").decodeToString())
        assertEquals(listOf("$folder/$base.meta.json"), container.downloads)
        assertFailsWith<DriveNotFound> { files.download("icloud:$folder/gone.json") }
    }

    @Test
    fun `nothing is touched while iCloud cannot be used`() = runBlocking<Unit> {
        container.available = false

        assertFailsWith<StorageUnavailableException> { files.recordingFolders() }
        assertFailsWith<StorageUnavailableException> { files.createFolder("recly", files.rootId) }
        assertTrue(container.directories == setOf(""))
    }

    @Test
    fun `a name cannot climb out of the folder it is made in`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { files.createFolder("..", files.rootId) }
        assertFailsWith<IllegalArgumentException> { files.createFolder("a/b", files.rootId) }
    }

    @Test
    fun `the router sends an id to the storage it belongs to`() = runBlocking<Unit> {
        val drive = DriveApi(deps)
        val storage = CloudStorage(drive, files)
        container.arrive("$folder/$base.meta.json", "meta".encodeToByteArray())

        assertSame(files, storage.forKind(StorageKind.ICLOUD))
        assertSame(drive, storage.forKind(StorageKind.DRIVE))
        assertEquals("meta", storage.download("icloud:$folder/$base.meta.json").decodeToString())
        assertEquals(StorageKind.ICLOUD, StorageKind.ofId("icloud:$folder"))
        assertEquals(StorageKind.DRIVE, StorageKind.ofId("1AbCdEf"))
    }

    @Test
    fun `without a container an iCloud id is unreachable rather than sent to Drive`() = runBlocking<Unit> {
        val storage = CloudStorage(DriveApi(deps), null)

        assertNull(storage.forKind(StorageKind.ICLOUD))
        assertFailsWith<StorageUnavailableException> { storage.delete("icloud:$folder") }
    }

    /** The path folders, made the way the upload's folder resolver makes them. */
    private suspend fun resolve(path: String): String {
        var parent = files.rootId
        for (segment in path.split('/')) {
            parent = (files.findChild(parent, segment, DriveApi.FOLDER_MIME) ?: files.createFolder(segment, parent)).id
        }
        return parent
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content

    private companion object {
        const val RECORDING_ID = "01J9ABCDEF0123456789ABCDEF"
    }
}
