@file:OptIn(ExperimentalTime::class)

package recly.core.webhook

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import recly.core.job.StepOutput
import recly.core.model.Context
import recly.core.model.Platform
import recly.core.model.Source
import recly.core.model.Step
import recly.core.model.Track
import recly.core.platform.DeviceInfo
import recly.core.testing.START
import recly.core.testing.STEP_RUN_ID
import recly.core.testing.driveStep
import recly.core.testing.testWorkflow

class PayloadBuilderTest {
    private val device = DeviceInfo("7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10", Platform.MACOS, "MacBook Pro")
    private val workflow = testWorkflow(steps = listOf(driveStep("up")))

    /** Exactly the shape `DriveUploadRunner` writes to `step_run.output_json`. */
    private val uploadOutput = StepOutput(
        Json.parseToJsonElement(
            """
            {
              "folderId": "1FoLdEr",
              "folderWebViewLink": "https://drive.google.com/drive/folders/1FoLdEr",
              "path": "recly/2026/2026-08/20260826T010000Z_desktop_01J9ABCD",
              "files": [
                { "part": 1, "track": "mono", "name": "20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a",
                  "bytes": 3601234, "sha256": "9f86d0", "fileId": "1AbC",
                  "webViewLink": "https://drive.google.com/file/d/1AbC/view" },
                { "part": 0, "track": "meta", "name": "20260826T010000Z_desktop_01J9ABCD.meta.json",
                  "bytes": 2210, "sha256": "2c26b4", "fileId": "1ZyX",
                  "webViewLink": "https://drive.google.com/file/d/1ZyX/view" }
              ]
            }
            """.trimIndent(),
        ) as JsonObject,
    )

    private val transcriptOutput = StepOutput(
        Json.parseToJsonElement(
            """
            {
              "transcript": { "jsonFileId": "1JsN", "txtFileId": "1TxT", "language": "ko",
                "speakerCount": 2, "durationSec": 2700.0, "provider": "assemblyai", "model": "universal-2" },
              "files": [
                { "part": 0, "track": "transcript", "name": "20260826T010000Z_desktop_01J9ABCD.transcript.json",
                  "bytes": 8192, "sha256": "aa11", "fileId": "1JsN",
                  "webViewLink": "https://drive.google.com/file/d/1JsN/view" },
                { "part": 0, "track": "transcript", "name": "20260826T010000Z_desktop_01J9ABCD.transcript.txt",
                  "bytes": 4096, "sha256": "bb22", "fileId": "1TxT",
                  "webViewLink": "https://drive.google.com/file/d/1TxT/view" }
              ]
            }
            """.trimIndent(),
        ) as JsonObject,
    )

    @Test
    fun `the drive ids of the prior upload step end up in the payload`() {
        val payload = PayloadBuilder.build(
            meta = finalizedMeta(),
            workflow = workflow,
            prior = mapOf("up" to uploadOutput),
            device = device,
            stepRunId = STEP_RUN_ID,
            now = START,
        )

        assertEquals(WebhookPayload.RECORDING_COMPLETED, payload.type)
        assertEquals(STEP_RUN_ID, payload.id)
        assertEquals("2026-08-26T01:00:00.000Z", payload.timestamp)
        assertEquals(
            WebhookFolder(
                path = "recly/2026/2026-08/20260826T010000Z_desktop_01J9ABCD",
                drive = WebhookDriveFolder("1FoLdEr", "https://drive.google.com/drive/folders/1FoLdEr"),
            ),
            payload.data.folder,
        )
        assertEquals(
            WebhookDriveFile("1AbC", "https://drive.google.com/file/d/1AbC/view"),
            payload.data.files.first().drive,
        )
        assertEquals(WebhookWorkflow(workflow.id, "회의"), payload.data.workflow)
        assertEquals(WebhookDevice(device.deviceId, Platform.MACOS, "MacBook Pro"), payload.data.device)
        assertEquals(Source.DESKTOP, payload.data.recording.source)
        assertEquals(2700.0, payload.data.recording.durationSec)
        assertEquals(listOf(Track.MONO), payload.data.recording.tracks)
    }

    /** docs/04: `meta.json` rides along in `files[]` under its own track name. */
    @Test
    fun `the meta file is listed as track meta at part 0`() {
        val payload = PayloadBuilder.build(
            finalizedMeta(), workflow, mapOf("up" to uploadOutput), device, STEP_RUN_ID, START,
        )

        val meta = payload.data.files.single { it.track == "meta" }
        assertEquals(0, meta.part)
        assertEquals("20260826T010000Z_desktop_01J9ABCD.meta.json", meta.name)
        assertEquals(2210L, meta.bytes)
    }

    @Test
    fun `without a successful upload every drive field is null and the local parts are still listed`() {
        val payload = PayloadBuilder.build(finalizedMeta(), workflow, emptyMap(), device, STEP_RUN_ID, START)

        assertNull(payload.data.folder)
        val file = payload.data.files.single()
        assertNull(file.drive)
        assertEquals("20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a", file.name)
        assertEquals("mono", file.track)
        // The key must still be emitted, or the schema's `required: [drive]` fails.
        assertTrue(payload.encode().contains("\"drive\":null"), payload.encode())
    }

    /** docs/08: the result of `transcribe` rides along in `files[]`. */
    @Test
    fun `transcript files are added to the uploaded ones`() {
        val steps = listOf(
            driveStep("up"),
            Step.Transcribe(id = "stt", provider = "assemblyai", secretRef = "stt_key"),
        )
        val payload = PayloadBuilder.build(
            meta = finalizedMeta(),
            workflow = testWorkflow(steps = steps),
            prior = mapOf("up" to uploadOutput, "stt" to transcriptOutput),
            device = device,
            stepRunId = STEP_RUN_ID,
            now = START,
        )

        assertEquals(
            listOf("mono", "meta", "transcript", "transcript"),
            payload.data.files.map { it.track },
        )
        val transcript = payload.data.files.first { it.name.endsWith(".transcript.txt") }
        assertEquals(0, transcript.part)
        assertEquals(4096L, transcript.bytes)
        assertEquals(WebhookDriveFile("1TxT", "https://drive.google.com/file/d/1TxT/view"), transcript.drive)
    }

    /** A step that has not run leaves nothing behind: no entry, no placeholder. */
    @Test
    fun `a transcribe step that did not succeed adds no files`() {
        val steps = listOf(
            driveStep("up"),
            Step.Transcribe(id = "stt", provider = "assemblyai", secretRef = "stt_key"),
        )
        val payload = PayloadBuilder.build(
            finalizedMeta(), testWorkflow(steps = steps), mapOf("up" to uploadOutput), device, STEP_RUN_ID, START,
        )

        assertEquals(listOf("mono", "meta"), payload.data.files.map { it.track })
    }

    /** Only outputs of `drive.upload` steps are read, and the last of them wins. */
    @Test
    fun `a webhook step earlier in the job is not mistaken for an upload`() {
        val steps = listOf(
            recly.core.model.Step.Webhook(id = "first", url = "https://example.com/a"),
            driveStep("up"),
        )
        val payload = PayloadBuilder.build(
            meta = finalizedMeta(),
            workflow = testWorkflow(steps = steps),
            prior = mapOf(
                "first" to StepOutput(Json.parseToJsonElement("""{"status":200}""") as JsonObject),
                "up" to uploadOutput,
            ),
            device = device,
            stepRunId = STEP_RUN_ID,
            now = START,
        )

        assertEquals("1FoLdEr", payload.data.folder?.drive?.folderId)
    }

    /** The emitted document has the same field names, in the same places, as the spec example. */
    @Test
    fun `the emitted field set matches spec examples webhook payload json`() {
        val meta = finalizedMeta().copy(context = Context(app = "us.zoom.xos"))
        val payload = PayloadBuilder.build(meta, workflow, mapOf("up" to uploadOutput), device, STEP_RUN_ID, START)

        // `context` is the one opaque object in the schema — the recorder passes through whatever
        // the platform gave it — so only its presence is part of the contract, not its shape.
        val expected = contract(Json.parseToJsonElement(File("../spec/examples/webhook.payload.json").readText()))
        assertEquals(expected, contract(Json.parseToJsonElement(payload.encode())))
    }

    private fun contract(element: JsonElement): Set<String> =
        paths(element).filterNot { it.startsWith("$CONTEXT.") }.toSet()

    /** Every key of the document, qualified by where it sits; array items collapse to one entry. */
    private fun paths(element: JsonElement, prefix: String = ""): Set<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            listOf("$prefix$key") + paths(value, "$prefix$key.")
        }.toSet()

        is JsonArray -> element.flatMap { paths(it, prefix.dropLast(1) + "[].") }.toSet()
        else -> emptySet()
    }

    private companion object {
        const val CONTEXT = "data.recording.context"
    }
}
