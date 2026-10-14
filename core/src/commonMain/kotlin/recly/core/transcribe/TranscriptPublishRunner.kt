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
import recly.core.recording.RecordingRecord
import recly.core.storage.CloudStorage
import recly.core.storage.StorageKind
import recly.core.storage.StorageUnavailableException

/**
 * Network publication only. The durable transcript remains readable when Drive is unavailable. An
 * iCloud folder or a local folder that cannot be reached from this device right now is waited for,
 * not failed (docs/03 "Storage location"). A local folder also gets the transcript as Markdown
 * (docs/08 "Result files").
 */
class TranscriptPublishRunner(
    private val deps: CoreDeps,
    private val fill: TranscriptFill = TranscriptFill.NONE,
) : StepRunner {
    override val type = "transcript.publish"
    private val api = CloudStorage.of(deps)
    private val files = ResultFiles(api, deps)
    private val writer = TranscriptWriter(deps)

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
        val current = editedSince(ctx, jsonName, result) ?: result
        // docs/08 "Titles and speaker names": before anything goes up, so the folder's first copy has them. Kept
        // here before the upload is tried — in the result file too, unless the user's edit is what goes up — so a
        // publish that has to try again finds nothing left to ask for.
        val filled = fill.fill(ctx.recording, current)
        val transcript = filled.transcript
        if (transcript != current) writer.writeLocal(ctx.recording, transcript, markdown = false, result = resultName.takeIf { current === result })
        val meta = filled.title?.let { ctx.recording.meta.copy(title = it) } ?: ctx.recording.meta
        val json = recJson.encodeToString(transcript).encodeToByteArray()
        val marks = TranscriptMarks.of(transcript)
        val published = files.write(ctx.recording.dir, folder, jsonName, json, TranscribeRunner.JSON_MIME, marks)
        val text = files.write(ctx.recording.dir, folder, TranscribeRunner.textFileName(base),
            TranscriptNormalizer.text(transcript).encodeToByteArray(), TranscribeRunner.TEXT_MIME, marks)
        val markdown = if (StorageKind.ofId(folder) != StorageKind.FOLDER) null else files.write(
            ctx.recording.dir, folder, TranscribeRunner.markdownFileName(base),
            TranscriptNormalizer.markdown(transcript, meta).encodeToByteArray(), TranscribeRunner.MARKDOWN_MIME,
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

/** What [TranscriptFill] made of a transcript: the one to publish, and the title the recording was given, if any. */
data class TranscriptFilled(val transcript: Transcript, val title: String? = null)

/**
 * docs/08 "Titles and speaker names": what fills in a transcript's speaker names, and the recording's title, just
 * before it is published. Never throws — a fill that fails publishes the transcript as it was.
 */
fun interface TranscriptFill {
    suspend fun fill(recording: RecordingRecord, transcript: Transcript): TranscriptFilled

    companion object {
        val NONE: TranscriptFill = TranscriptFill { _, transcript -> TranscriptFilled(transcript) }
    }
}
