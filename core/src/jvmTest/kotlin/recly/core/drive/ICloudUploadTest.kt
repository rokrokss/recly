@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.job.StepOutcome
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.Step
import recly.core.storage.StorageKind

/**
 * docs/03 "저장 위치": `drive.upload` into the app's iCloud folder. The files are copied in and the
 * step waits — spending nothing — until iCloud says it holds every one; an account out of space
 * parks it like a full Drive, and a device where iCloud cannot be used waits instead of failing.
 */
class ICloudUploadTest {
    private val step = Step.DriveUpload(id = "up", store = StorageKind.ICLOUD)

    @Test
    fun `the parts and then the meta are copied into the recording's iCloud folder, and the step waits for iCloud`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        val container = h.container!!

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.ICLOUD_UPLOADING, CoreMessageRef.parse(outcome.reason!!)!!.message)
        val folder = "recly/2026/2026-08/${h.base}"
        assertTrue(container.entries.keys.containsAll(listOf(
            "$folder/${h.partName(1)}", "$folder/${h.partName(2)}", "$folder/${h.metaName()}", "$folder/${h.base}.folder.json",
        )))
        val properties = container.text("$folder/${h.base}.folder.json")
        assertTrue(h.recordingId in properties && "주간 회의" in properties)
        assertTrue(h.drive.requests.isEmpty(), "nothing went to Drive")
    }

    @Test
    fun `once iCloud holds every file the step is done, without copying anything again`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        val container = h.container!!
        h.state = assertIs<StepOutcome.Waiting>(h.outcome(step)).state
        val versions = container.entries.mapValues { it.value.modifiedAt }

        container.settle()
        val output = assertIs<StepOutcome.Done>(h.outcome(step)).output.json

        assertEquals("icloud:recly/2026/2026-08/${h.base}", output.string("folderId"))
        val files = output["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, files.size)
        assertTrue(files.all { it.string("fileId")!!.startsWith(StorageKind.ICLOUD_PREFIX) })
        assertEquals(versions, container.entries.mapValues { it.value.modifiedAt }, "nothing was written twice")
    }

    @Test
    fun `an iCloud account out of space parks the upload in NEEDS_SPACE`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        h.state = assertIs<StepOutcome.Waiting>(h.outcome(step)).state
        h.container!!.refuse("recly/2026/2026-08/${h.base}/${h.partName(1)}")

        val failure = assertFailsWith<StepFailure> { h.outcome(step) }

        assertTrue(failure.needsSpace)
        assertEquals(CoreMessage.ICLOUD_STORAGE_FULL, CoreMessageRef.parse(failure.reason)!!.message)
    }

    @Test
    fun `while iCloud cannot be used the step waits and writes nothing`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        h.container!!.available = false

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.ICLOUD_UNAVAILABLE, CoreMessageRef.parse(outcome.reason!!)!!.message)
        assertTrue(h.container.entries.isEmpty())
    }

    @Test
    fun `an iCloud that has not finished listing its files is a wait, not a failure`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        val container = h.container!!
        container.listingComplete = false

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.ICLOUD_UNAVAILABLE, CoreMessageRef.parse(outcome.reason!!)!!.message)
        container.listingComplete = true
        h.state = outcome.state
        h.state = assertIs<StepOutcome.Waiting>(h.outcome(step)).state
        container.settle()
        assertIs<StepOutcome.Done>(h.outcome(step))
    }

    @Test
    fun `a device without an iCloud container waits rather than sending the recording to Drive`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)

        val outcome = assertIs<StepOutcome.Waiting>(h.outcome(step))

        assertEquals(CoreMessage.ICLOUD_UNAVAILABLE, CoreMessageRef.parse(outcome.reason!!)!!.message)
        assertTrue(h.drive.requests.isEmpty())
    }

    @Test
    fun `the folder says what is still to come after the upload, as Drive's does`() = runBlocking<Unit> {
        val h = DriveHarness(
            partCount = 1,
            partBytes = DriveHarness.SMALL_BYTES,
            icloud = true,
            steps = listOf(step, Step.LocalTranscribe("transcribe"), Step.TranscriptPublish("publish")),
        )

        h.outcome(step)

        val properties = h.container!!.text("recly/2026/2026-08/${h.base}/${h.base}.folder.json")
        assertTrue("local.transcribe,transcript.publish" in properties, properties)
    }

    @Test
    fun `a saved Drive folder is not where an iCloud upload goes`() = runBlocking<Unit> {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES, icloud = true)
        h.state = DriveUploadState(folderId = "1DriveFolderId").toJson()

        h.outcome(step)

        assertTrue(h.container!!.entries.keys.any { it.endsWith(h.partName(1)) })
        assertTrue(h.drive.requests.isEmpty())
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
}
