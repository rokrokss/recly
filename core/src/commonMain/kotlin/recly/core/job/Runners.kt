package recly.core.job

import recly.core.db.RecDatabase
import recly.core.drive.DriveUploadRunner
import recly.core.platform.CoreDeps
import recly.core.transcribe.TranscribeRunner
import recly.core.webhook.WebhookRunner

/**
 * The step types this build can run, keyed the way [Executor] looks them up. A shell that wants a
 * platform-specific runner (an Apple background-`URLSession` upload, say) passes its own map.
 */
fun defaultRunners(db: RecDatabase, deps: CoreDeps): Map<String, StepRunner> =
    listOf(
        DriveUploadRunner.create(db, deps),
        WebhookRunner(deps),
        TranscribeRunner.create(deps),
    ).associateBy { it.type }
