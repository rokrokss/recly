@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.spec

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import recly.core.job.StepOutput
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.Step
import recly.core.model.recJson
import recly.core.platform.DeviceInfo
import recly.core.testing.START
import recly.core.testing.driveStep
import recly.core.testing.testWorkflow
import recly.core.webhook.PayloadBuilder
import recly.core.webhook.finalizedMeta
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

class SchemaConformanceTest {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    /** `format` is annotation-only by default in 2020-12; the spec means it as an assertion. */
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    private val workflowSchema: JsonSchema get() = schema("../spec/workflow.schema.json")
    private val recordingMetaSchema: JsonSchema get() = schema("../spec/recording.meta.schema.json")
    private val webhookSchema: JsonSchema get() = schema("../spec/webhook.payload.schema.json")
    private val transcriptSchema: JsonSchema get() = schema("../spec/transcript.schema.json")

    private fun schema(path: String) = factory.getSchema(File(path).readText(), config)

    private fun assertValid(label: String, schema: JsonSchema, json: String) {
        val errors = schema.validate(json, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "$label: ${errors.joinToString("; ")}")
    }

    private fun assertFlagged(label: String, schema: JsonSchema, json: String) {
        assertTrue(schema.validate(json, InputFormat.JSON).isNotEmpty(), "schema did not flag $label")
    }

    private fun workflowsExample() = File("../spec/examples/workflows.json").readText()

    private fun recordingMetaExample() = File("../spec/examples/recording.meta.json").readText()

    private fun webhookExample() = File("../spec/examples/webhook.payload.json").readText()

    private fun transcriptExample() = File("../spec/examples/transcript.json").readText()

    /** What `TranscribeRunner` writes, built by the same normalizer the runner calls. */
    private fun emittedTranscript(diarize: Boolean): String {
        val part = recly.core.model.Part(
            part = 1,
            track = recly.core.model.Track.MONO,
            file = "p001_mono.m4a",
            bytes = 16,
            sha256 = "0".repeat(64),
            startOffsetSec = 0.0,
            durationSec = 900.0,
        )
        val transcript = recly.core.transcribe.TranscriptNormalizer.normalize(
            recordingId = "01J9ABCDEF0123456789ABCDEF",
            track = recly.core.model.Track.MONO,
            parts = listOf(part),
            result = recly.core.transcribe.SttResult(
                segments = listOf(
                    recly.core.transcribe.SttSegment(
                        start = 0.0,
                        end = 3.2,
                        speaker = "A",
                        text = "시작하겠습니다.",
                        words = listOf(recly.core.transcribe.SttWord(0.0, 0.6, "시작하겠습니다.")),
                    ),
                    recly.core.transcribe.SttSegment(3.6, 9.1, "B", "네."),
                ),
                language = "ko",
                durationSec = 900.0,
                model = "universal-2",
            ),
            diarize = diarize,
            provider = recly.core.transcribe.TranscriptProvider("assemblyai", "universal-2", "t-1"),
            createdAt = "2026-08-29T03:10:00.000Z",
            language = "ko",
        )
        return recJson.encodeToString(transcript)
    }

    /** The real builder, over the same `drive.upload` output shape the runner writes. */
    private fun webhookPayload(uploaded: Boolean): String {
        val upload = StepOutput(
            Json.parseToJsonElement(
                """
                {
                  "folderId": "1FoLdEr",
                  "folderWebViewLink": "https://drive.google.com/drive/folders/1FoLdEr",
                  "path": "recly/2026/2026-08/20260826T010000Z_desktop_01J9ABCD",
                  "files": [
                    { "part": 1, "track": "mono", "name": "p001_mono.m4a", "bytes": 3601234,
                      "sha256": "9f86d0", "fileId": "1AbC",
                      "webViewLink": "https://drive.google.com/file/d/1AbC/view" },
                    { "part": 0, "track": "meta", "name": "rec.meta.json", "bytes": 2210,
                      "sha256": "2c26b4", "fileId": "1ZyX",
                      "webViewLink": "https://drive.google.com/file/d/1ZyX/view" }
                  ]
                }
                """.trimIndent(),
            ) as JsonObject,
        )
        return PayloadBuilder.build(
            meta = finalizedMeta(),
            workflow = testWorkflow(steps = listOf(driveStep("up"))),
            prior = if (uploaded) mapOf("up" to upload) else emptyMap(),
            device = DeviceInfo("7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10", Platform.MACOS, "MacBook Pro"),
            stepRunId = "01J9STEPR0N0123456789ABCDE",
            now = START,
        ).encode()
    }

    private fun full() = checkNotNull(javaClass.getResource("/workflows.full.json")).readText()

    @Test
    fun exampleFilesMatchTheirSchemas() {
        assertValid("workflows example", workflowSchema, workflowsExample())
        assertValid("recording meta example", recordingMetaSchema, recordingMetaExample())
        assertValid("full workflows fixture", workflowSchema, full())
        assertValid("webhook payload example", webhookSchema, webhookExample())
        assertValid("transcript example", transcriptSchema, transcriptExample())
    }

    /** docs/08: the file the runner writes is the same document the spec example shows. */
    @Test
    fun `emitted transcripts match the transcript schema`() {
        assertValid("emitted transcript", transcriptSchema, emittedTranscript(diarize = true))
        assertValid("emitted transcript without diarization", transcriptSchema, emittedTranscript(diarize = false))
    }

    /** Without these the suite would pass just as happily if the schema silently failed to load. */
    @Test
    fun validatorFlagsBrokenDocuments() {
        assertFlagged("schema: 2", workflowSchema, workflowsExample().replaceFirst("\"schema\": 3", "\"schema\": 2"))
        assertFlagged(
            "updatedAt: yesterday",
            workflowSchema,
            workflowsExample().replaceFirst("\"2026-08-26T01:00:00.000Z\"", "\"yesterday\""),
        )
        assertFlagged(
            "url with a space",
            workflowSchema,
            workflowsExample().replaceFirst("https://n8n.example.com/webhook/rec", "https://exa mple.com/x"),
        )
        assertFlagged(
            "transcript with a provider label as a speaker id",
            transcriptSchema,
            transcriptExample().replaceFirst("\"id\": \"S1\"", "\"id\": \"A\""),
        )
        assertFlagged(
            "webhook payload without files[].drive",
            webhookSchema,
            webhookExample().replaceFirst("\"drive\": { \"fileId\": \"1AbCdEfGhIjKlMnOpQrStUvWxYz\"", "\"nope\": { \"fileId\": \"x\""),
        )
    }

    /** Both halves of docs/04: with a `drive.upload` in front of the step, and without one. */
    @Test
    fun `emitted webhook payloads match the webhook schema`() {
        assertValid("webhook payload after an upload", webhookSchema, webhookPayload(uploaded = true))
        assertValid("webhook payload with no upload", webhookSchema, webhookPayload(uploaded = false))
    }

    /** docs/08: `transcript` is a file track the receiver must be able to read. */
    @Test
    fun `a payload carrying the transcript matches the schema`() {
        val results = StepOutput(
            Json.parseToJsonElement(
                """
                {
                  "files": [
                    { "part": 0, "track": "transcript", "name": "rec.transcript.txt", "bytes": 4096,
                      "sha256": "bb22", "fileId": "1TxT",
                      "webViewLink": "https://drive.google.com/file/d/1TxT/view" }
                  ]
                }
                """.trimIndent(),
            ) as JsonObject,
        )
        val payload = PayloadBuilder.build(
            meta = finalizedMeta(),
            workflow = testWorkflow(
                steps = listOf(
                    driveStep("up"),
                    Step.Transcribe(id = "stt", provider = "assemblyai", secretRef = "stt_key"),
                ),
            ),
            prior = mapOf("stt" to results),
            device = DeviceInfo("7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10", Platform.MACOS, "MacBook Pro"),
            stepRunId = "01J9STEPR0N0123456789ABCDE",
            now = START,
        ).encode()

        assertValid("webhook payload with results", webhookSchema, payload)
        assertTrue(payload.contains("\"track\":\"transcript\""), payload)
    }

    @Test
    fun serializedWorkflowsMatchTheSchema() {
        val doc = (WorkflowParser.parse(workflowsExample()) as ParseResult.Ok).document
        assertValid("serialized workflows", workflowSchema, WorkflowParser.serialize(doc))
    }

    @Test
    fun serializedRecordingMetaMatchesTheSchema() {
        val meta = recJson.decodeFromString<RecordingMeta>(recordingMetaExample())
        assertValid("serialized recording meta", recordingMetaSchema, recJson.encodeToString(meta))
    }

    @Test
    fun fullDocumentRoundTripsStructurally() {
        val source = full()
        val doc = (WorkflowParser.parse(source) as ParseResult.Ok).document
        assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(WorkflowParser.serialize(doc)))
    }

    @Test
    fun webhookWithoutSecretRefOmitsTheKey() {
        val doc = (WorkflowParser.parse(workflowsExample()) as ParseResult.Ok).document
        val stripped = doc.copy(
            workflows = listOf(
                doc.workflows[0].let { w ->
                    w.copy(steps = listOf(w.steps[0], (w.steps[1] as Step.Webhook).copy(secretRef = null)))
                },
            ),
        )
        val emitted = WorkflowParser.serialize(stripped)
        val step = Json.parseToJsonElement(emitted)
            .jsonObject.getValue("workflows").jsonArray[0]
            .jsonObject.getValue("steps").jsonArray[1].jsonObject
        assertFalse(step.containsKey("secretRef"), "secretRef should be absent, was $step")
        assertValid("workflows without secretRef", workflowSchema, emitted)
    }
}
