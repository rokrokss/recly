@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.spec

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import recly.core.model.RecordingMeta
import recly.core.model.recJson
import recly.core.processing.ExternalTranscription
import recly.core.processing.ProcessingParseResult
import recly.core.processing.ProcessingSettings
import recly.core.processing.ProcessingSettingsDocument
import recly.core.processing.ProcessingSettingsParser
import recly.core.processing.ProcessingTranscription
import recly.core.processing.TranscriptionMode

class SchemaConformanceTest {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    /** `format` is annotation-only by default in 2020-12; the spec means it as an assertion. */
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    private val recordingMetaSchema: JsonSchema get() = schema("../spec/recording.meta.schema.json")
    private val settingsSchema: JsonSchema get() = schema("../spec/recording-settings.schema.json")
    private val transcriptSchema: JsonSchema get() = schema("../spec/transcript.schema.json")

    private fun schema(path: String) = factory.getSchema(File(path).readText(), config)

    private fun assertValid(label: String, schema: JsonSchema, json: String) {
        val errors = schema.validate(json, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "$label: ${errors.joinToString("; ")}")
    }

    private fun assertFlagged(label: String, schema: JsonSchema, json: String) {
        assertTrue(schema.validate(json, InputFormat.JSON).isNotEmpty(), "schema did not flag $label")
    }

    private fun recordingMetaExample() = File("../spec/examples/recording.meta.json").readText()

    private fun settingsExamples() = listOf("recording-settings.json", "recording-settings-external.json")
        .map { File("../spec/examples/$it").readText() }

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

    @Test
    fun exampleFilesMatchTheirSchemas() {
        assertValid("recording meta example", recordingMetaSchema, recordingMetaExample())
        settingsExamples().forEach { assertValid("settings example", settingsSchema, it) }
        assertValid("transcript example", transcriptSchema, transcriptExample())
    }

    /** docs/05: the settings the core stores and exports are the document the spec describes. */
    @Test
    fun `settings examples parse and serialized settings match the settings schema`() {
        settingsExamples().forEach { assertIs<ProcessingParseResult.Valid>(ProcessingSettingsParser.parse(it), it) }
        val external = ProcessingSettings(transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL,
            external = ExternalTranscription("clova", "clova_key", "https://clovaspeech-gw.ncloud.com/external/v1/1/abc", "model")))
        for (settings in listOf(ProcessingSettings(), external)) {
            val document = ProcessingSettingsDocument(revision = 1, updatedAt = "2026-09-24T10:00:00Z", updatedBy = "device", settings = settings)
            assertValid("serialized settings", settingsSchema, ProcessingSettingsParser.serialize(document))
        }
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
        val settings = settingsExamples().first()
        assertFlagged("settings schema: 2", settingsSchema, settings.replaceFirst("\"schema\": 1", "\"schema\": 2"))
        assertFlagged(
            "settings with a completion webhook",
            settingsSchema,
            settings.replaceFirst("\"storage\": {", "\"webhook\": { \"url\": \"https://example.com/hook\" },\n    \"storage\": {"),
        )
        assertFlagged(
            "transcript with a provider label as a speaker id",
            transcriptSchema,
            transcriptExample().replaceFirst("\"id\": \"S1\"", "\"id\": \"A\""),
        )
    }

    @Test
    fun serializedRecordingMetaMatchesTheSchema() {
        val meta = recJson.decodeFromString<RecordingMeta>(recordingMetaExample())
        assertValid("serialized recording meta", recordingMetaSchema, recJson.encodeToString(meta))
    }
}
