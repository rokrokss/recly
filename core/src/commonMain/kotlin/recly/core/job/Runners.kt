package recly.core.job

import recly.core.db.RecDatabase
import recly.core.drive.DriveUploadRunner
import recly.core.platform.CoreDeps
import recly.core.transcribe.TranscribeRunner

/**
 * The step types this build can run, keyed the way [Executor] looks them up. A shell that wants a
 * platform-specific runner (an Apple background-`URLSession` upload, say) passes its own map.
 */
fun defaultRunners(
    db: RecDatabase,
    deps: CoreDeps,
    local: recly.core.transcribe.LocalTranscriptionService = recly.core.transcribe.LocalTranscriptionService(db, deps),
    fill: recly.core.transcribe.TranscriptFill = recly.core.transcribe.TranscriptFill.NONE,
    /** docs/08 "Organizing": the notes; without it, a plan's `notes.organize` has no runner here. */
    organize: StepRunner? = null,
): Map<String, StepRunner> =
    listOfNotNull(
        DriveUploadRunner.create(db, deps),
        TranscribeRunner.create(deps),
        local,
        recly.core.transcribe.TranscriptPublishRunner(deps, fill),
        organize,
    ).associateBy { it.type }
