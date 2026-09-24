package recly.core.transcribe

import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.job.StepContext
import recly.core.job.StepOutput
import recly.core.model.recJson
import recly.core.recording.MetaWriter

/** Fixed-plan results are durable before network publication; the run ID prevents cross-job reuse. */
internal object TranscriptCache {
    fun output(ctx: StepContext) = StepOutput(buildJsonObject { put("resultFile", LocalTranscriptionService.resultName(ctx.stepRunId)) })

    suspend fun read(ctx: StepContext): Transcript? = withContext(ctx.deps.io) {
        val path = ctx.recording.dir / LocalTranscriptionService.resultName(ctx.stepRunId)
        if (!ctx.deps.fileSystem.exists(path)) return@withContext null
        recJson.decodeFromString<Transcript>(ctx.deps.fileSystem.read(path) { readUtf8() })
            .also { require(it.recordingId == ctx.recording.id) }
    }

    suspend fun write(ctx: StepContext, transcript: Transcript) = withContext(ctx.deps.io) {
        resultFileMutex.withLock {
            val base = MetaWriter.baseName(ctx.recording.meta)
            val json = recJson.encodeToString(transcript)
            for ((name, value) in listOf(
                LocalTranscriptionService.resultName(ctx.stepRunId) to json,
                TranscribeRunner.jsonFileName(base) to json,
                TranscribeRunner.textFileName(base) to TranscriptNormalizer.text(transcript),
            )) {
                val target = ctx.recording.dir / name
                val temporary = ctx.recording.dir / "$name.tmp"
                ctx.deps.fileSystem.write(temporary) { writeUtf8(value) }
                ctx.deps.fileSystem.atomicMove(temporary, target)
            }
        }
    }
}
