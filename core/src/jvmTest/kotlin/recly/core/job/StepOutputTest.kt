@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [outputString] is the only way a Swift shell may read a step's output: the `JsonObject` itself
 * force-bridges into a typed Swift dictionary and aborts on the `files` array (see the KDoc). What
 * it must answer for every shape a `drive.upload` output actually has is decided here.
 */
class StepOutputTest {

    /** The shape `DriveUploadRunner` writes, `files` array and all. */
    private val uploadOutput = buildJsonObject {
        put("folderId", "1AbCdEf")
        put("folderWebViewLink", "https://drive.google.com/drive/folders/1AbCdEf")
        put("path", "Recly/Meeting/2026-09-02_1400")
        putJsonArray("files") {
            addJsonObject {
                put("part", 1)
                put("track", "mic")
                put("name", "p001_mic.m4a")
                put("bytes", 1_234_567L)
                put("sha256", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
                put("fileId", "1FileId")
                put("webViewLink", "https://drive.google.com/file/d/1FileId/view")
            }
        }
    }

    private fun step(output: JsonObject?) = StepRun(
        id = "01J9STEPR0N0123456789ABCDE",
        jobId = "01J9JOB0000000000000000000",
        stepId = "upload",
        ordinal = 0,
        status = StepStatus.SUCCEEDED,
        attempts = 0,
        nextAttemptAt = null,
        lastError = null,
        state = null,
        output = output,
    )

    @Test
    fun `a string field of a real upload output is the string`() {
        assertEquals(
            "https://drive.google.com/drive/folders/1AbCdEf",
            step(uploadOutput).outputString("folderWebViewLink"),
        )
        assertEquals("1AbCdEf", step(uploadOutput).outputString("folderId"))
    }

    /** The value that trapped in Swift: it is not a string, so it answers null rather than throwing. */
    @Test
    fun `an array field is not a string`() {
        assertNull(step(uploadOutput).outputString("files"))
    }

    @Test
    fun `an object field is not a string`() {
        val output = buildJsonObject { putJsonObject("folder") { put("id", "1AbCdEf") } }
        assertNull(step(output).outputString("folder"))
    }

    @Test
    fun `a key nothing wrote is null`() {
        assertNull(step(uploadOutput).outputString("folderWebViewLinks"))
        assertNull(step(null).outputString("folderWebViewLink"))
    }

    /** A JSON `null` is a primitive, and the caller wants a link — not the word "null". */
    @Test
    fun `a json null is null`() {
        val output = buildJsonObject { put("folderWebViewLink", JsonNull) }
        assertNull(step(output).outputString("folderWebViewLink"))
    }
}
