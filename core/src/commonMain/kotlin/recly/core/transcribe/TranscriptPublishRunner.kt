package recly.core.transcribe

import kotlinx.serialization.json.*
import recly.core.drive.DriveUploadRunner
import recly.core.drive.string
import recly.core.job.*
import recly.core.message.CoreMessage
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.recording.MetaWriter
import recly.core.storage.CloudStorage
import recly.core.storage.StorageUnavailableException

/**
 * Network publication only. The durable transcript remains readable when Drive is unavailable. An
 * iCloud folder that cannot be reached from this device right now is waited for, not failed
 * (docs/03 "Storage location").
 */
class TranscriptPublishRunner(private val deps: CoreDeps) : StepRunner {
    override val type = "transcript.publish"
    private val files = ResultFiles(CloudStorage.of(deps), deps)

    override suspend fun run(ctx: StepContext): StepOutcome = try {
        publish(ctx)
    } catch (e: StorageUnavailableException) {
        StepOutcome.Waiting(DriveUploadRunner.UNAVAILABLE_WAIT_SEC, ctx.state ?: JsonObject(emptyMap()), CoreMessage.ICLOUD_UNAVAILABLE.code())
    }

    private suspend fun publish(ctx: StepContext): StepOutcome {
        val folder = ctx.priorOutput(DriveUploadRunner.TYPE)?.string("folderId")
            ?: throw StepFailure(false, CoreMessage.STEP_FAILED.code("missing upload destination"))
        val base = MetaWriter.baseName(ctx.recording.meta)
        val jsonName = TranscribeRunner.jsonFileName(base)
        val resultName = ctx.prior.values.mapNotNull { it.json.string("resultFile") }.lastOrNull()
            ?: throw StepFailure(false, CoreMessage.STEP_FAILED.code("missing durable transcript"))
        require(resultName.matches(Regex("\\.result-[0-9A-Z]+\\.json")))
        val json = deps.fileSystem.read(ctx.recording.dir / resultName) { readByteArray() }
        val transcript = recJson.decodeFromString<Transcript>(json.decodeToString())
        require(transcript.recordingId == ctx.recording.id)
        val published = files.write(ctx.recording.dir, folder, jsonName, json, TranscribeRunner.JSON_MIME)
        val text = files.write(ctx.recording.dir, folder, TranscribeRunner.textFileName(base),
            TranscriptNormalizer.text(transcript).encodeToByteArray(), TranscribeRunner.TEXT_MIME)
        return StepOutcome.Done(StepOutput(buildJsonObject {
            putJsonObject("transcript") {
                put("jsonFileId", published.fileId); put("txtFileId", text.fileId)
                put("language", transcript.language); put("speakerCount", transcript.speakers.size)
                put("durationSec", transcript.durationSec); put("provider", transcript.provider.name)
                transcript.provider.model?.let { put("model", it) }
            }
            putJsonArray("files") { add(published.toJson("transcript")); add(text.toJson("transcript")) }
        }))
    }
}
