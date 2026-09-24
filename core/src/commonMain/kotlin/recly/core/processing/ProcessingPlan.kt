package recly.core.processing

import recly.core.model.Step
import recly.core.model.Workflow

/** The one workflow every recording runs, compiled from its frozen settings. [ID] is stable across revisions. */
object ProcessingPlan {
    const val ID = "00000000000000000000REC100"

    fun compile(document: ProcessingSettingsDocument): Workflow {
        val settings = document.settings.forNewRecordings()
        return Workflow(
            id = ID, name = "Recording", updatedAt = document.updatedAt,
            minDurationSec = settings.storage.minDurationSec,
            steps = buildList {
                add(Step.DriveUpload("upload", folder = settings.storage.folder))
                val transcription = settings.transcription
                when (transcription.mode) {
                    TranscriptionMode.LOCAL -> {
                        add(Step.LocalTranscribe("transcribe", language = transcription.language, diarize = transcription.diarize))
                        add(Step.TranscriptPublish("publish"))
                    }
                    TranscriptionMode.EXTERNAL -> {
                        val external = requireNotNull(transcription.external)
                        add(Step.Transcribe("transcribe", provider = external.provider, secretRef = external.secretRef,
                            invokeUrl = external.invokeUrl, model = external.model,
                            language = transcription.language,
                            diarize = transcription.diarize, speakers = transcription.speakers))
                        add(Step.TranscriptPublish("publish"))
                    }
                    TranscriptionMode.OFF -> Unit
                }
            },
        )
    }
}
