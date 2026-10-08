package recly.core.processing

import recly.core.model.Step
import recly.core.model.Workflow

/** The one workflow every recording runs, compiled from its frozen settings. [ID] is stable across revisions. */
object ProcessingPlan {
    const val ID = "00000000000000000000REC100"

    /**
     * The job id of a re-transcription (docs/10 "Re-transcription"): a recording has at most one besides
     * its own [ID] job, and each new request replaces it.
     */
    const val RETRANSCRIBE_ID = "00000000000000000000REC101"

    /** Both plans keep a durable local result before publishing it (`TranscriptCache`). */
    fun isFixed(workflowId: String): Boolean = workflowId == ID || workflowId == RETRANSCRIBE_ID

    /**
     * Transcription and publication again, with the current [document] — its mode, provider, language
     * and vocabulary — into [folderId], the recording's folder, which already holds its audio. Null with
     * transcription off.
     */
    fun retranscription(document: ProcessingSettingsDocument, folderId: String): Workflow? {
        val steps = compile(document).steps.filter { it !is Step.DriveUpload }
            .map { if (it is Step.TranscriptPublish) it.copy(folderId = folderId) else it }
        if (steps.isEmpty()) return null
        return Workflow(id = RETRANSCRIBE_ID, name = "Transcribe again", updatedAt = document.updatedAt, steps = steps)
    }

    fun compile(document: ProcessingSettingsDocument): Workflow {
        val settings = document.settings.forNewRecordings()
        return Workflow(
            id = ID, name = "Recording", updatedAt = document.updatedAt,
            minDurationSec = settings.storage.minDurationSec,
            steps = buildList {
                add(Step.DriveUpload("upload", folder = settings.storage.uploadFolder(), store = settings.storage.provider))
                val transcription = settings.transcription
                when (transcription.mode) {
                    TranscriptionMode.LOCAL -> {
                        add(Step.LocalTranscribe("transcribe", language = transcription.language, diarize = transcription.diarize,
                            vocabulary = transcription.vocabulary))
                        add(Step.TranscriptPublish("publish"))
                    }
                    TranscriptionMode.EXTERNAL -> {
                        val external = requireNotNull(transcription.external)
                        add(Step.Transcribe("transcribe", provider = external.provider, secretRef = external.secretRef,
                            invokeUrl = external.invokeUrl, model = external.model,
                            language = transcription.language,
                            diarize = transcription.diarize, speakers = transcription.speakers,
                            vocabulary = transcription.vocabulary))
                        add(Step.TranscriptPublish("publish"))
                    }
                    TranscriptionMode.OFF -> Unit
                }
            },
        )
    }
}
