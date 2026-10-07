package recly.core.transcribe

import kotlinx.serialization.json.*
import recly.core.drive.DriveUploadRunner
import recly.core.drive.string
import recly.core.job.*
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.recording.MetaWriter
import recly.core.storage.CloudStorage
import recly.core.storage.StorageKind
import recly.core.storage.StorageUnavailableException

/**
 * Network publication only. The durable transcript remains readable when Drive is unavailable. An
 * iCloud folder or a local folder that cannot be reached from this device right now is waited for,
 * not failed (docs/03 "Storage location"). A local folder also gets the transcript as Markdown
 * (docs/08 "Result files").
 */
class TranscriptPublishRunner(private val deps: CoreDeps) : StepRunner {
    override val type = "transcript.publish"
    private val api = CloudStorage.of(deps)
    private val files = ResultFiles(api, deps)

    override suspend fun run(ctx: StepContext): StepOutcome = try {
        publish(ctx)
    } catch (e: StorageUnavailableException) {
        StepOutcome.Waiting(DriveUploadRunner.UNAVAILABLE_WAIT_SEC, ctx.state ?: JsonObject(emptyMap()), DriveUploadRunner.unavailableReason(e.kind))
    }

    private suspend fun publish(ctx: StepContext): StepOutcome {
        // A re-transcription uploads nothing: its plan names the recording's folder (docs/10 "Re-transcription").
        val folder = (ctx.step as? Step.TranscriptPublish)?.folderId
            ?: ctx.priorOutput(DriveUploadRunner.TYPE)?.string("folderId")
            ?: throw StepFailure(false, CoreMessage.STEP_FAILED.code("missing upload destination"))
        val base = MetaWriter.baseName(ctx.recording.meta)
        val jsonName = TranscribeRunner.jsonFileName(base)
        val resultName = ctx.prior.values.mapNotNull { it.json.string("resultFile") }.lastOrNull()
            ?: throw StepFailure(false, CoreMessage.STEP_FAILED.code("missing durable transcript"))
        require(resultName.matches(Regex("\\.result-[0-9A-Z]+\\.json")))
        val result = recJson.decodeFromString<Transcript>(deps.fileSystem.read(ctx.recording.dir / resultName) { readUtf8() })
        require(result.recordingId == ctx.recording.id)
        val transcript = editedSince(ctx, jsonName, result) ?: result
        val json = recJson.encodeToString(transcript).encodeToByteArray()
        val marks = TranscriptMarks.of(transcript)
        val published = files.write(ctx.recording.dir, folder, jsonName, json, TranscribeRunner.JSON_MIME, marks)
        val text = files.write(ctx.recording.dir, folder, TranscribeRunner.textFileName(base),
            TranscriptNormalizer.text(transcript).encodeToByteArray(), TranscribeRunner.TEXT_MIME, marks)
        val markdown = if (StorageKind.ofId(folder) != StorageKind.FOLDER) null else files.write(
            ctx.recording.dir, folder, TranscribeRunner.markdownFileName(base),
            TranscriptNormalizer.markdown(transcript, ctx.recording.meta).encodeToByteArray(), TranscribeRunner.MARKDOWN_MIME,
        )
        stamp(folder, transcript)
        return StepOutcome.Done(StepOutput(buildJsonObject {
            putJsonObject("transcript") {
                put("jsonFileId", published.fileId); put("txtFileId", text.fileId)
                markdown?.let { put("mdFileId", it.fileId) }
                put("language", transcript.language); put("speakerCount", transcript.speakers.size)
                put("durationSec", transcript.durationSec); put("provider", transcript.provider.name)
                transcript.provider.model?.let { put("model", it) }
            }
            putJsonArray("files") {
                add(published.toJson("transcript")); add(text.toJson("transcript"))
                markdown?.let { add(it.toJson("transcript")) }
            }
        }))
    }

    /**
     * The local copy when the user edited this very result before it went out — a publish that failed
     * and was retried after the edit (docs/08 "Editing"). The transcription wrote the local copy when it
     * finished, so an edit of an older transcript never matches: the new result replaces it.
     */
    private fun editedSince(ctx: StepContext, jsonName: String, result: Transcript): Transcript? {
        val path = ctx.recording.dir / jsonName
        if (!deps.fileSystem.exists(path)) return null
        val local = runCatching { recJson.decodeFromString<Transcript>(deps.fileSystem.read(path) { readUtf8() }) }.getOrNull()
        return local?.takeIf { it.editedAt != null && it.createdAt == result.createdAt && it.recordingId == result.recordingId }
    }

    /**
     * Tells other devices the folder has a newer transcript ([TranscriptMarks.FOLDER_STAMP]). Not advisory:
     * without it the other devices keep the copy they have, so a failure fails the step like the file
     * writes do, and the retry skips the files already there (same md5) and stamps again.
     */
    private suspend fun stamp(folder: String, transcript: Transcript) {
        api.updateAppProperties(folder, mapOf(TranscriptMarks.FOLDER_STAMP to TranscriptMarks.version(transcript)))
    }
}
