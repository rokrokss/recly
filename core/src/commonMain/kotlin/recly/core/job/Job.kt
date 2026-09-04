@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import recly.core.model.Workflow

/**
 * docs/10 "잡 상태 머신". [NEEDS_AUTH] and [NEEDS_SPACE] are the two terminal-until-a-person-acts
 * states: the scheduler never picks them up again and only `JobService.retry` moves them on.
 */
enum class JobStatus { PENDING, RUNNING, WAITING, DONE, FAILED, NEEDS_AUTH, NEEDS_SPACE, SKIPPED_SHORT }

enum class StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, NEEDS_AUTH, NEEDS_SPACE }

/**
 * One recording × one workflow. [workflow] is the snapshot taken at enqueue time, so editing the
 * definition on another device never changes what a queued job does.
 *
 * [workflow] is null exactly when [snapshotError] is set: the stored snapshot names something this
 * build cannot decode — a step type a newer app wrote (docs/10 "잡 스냅샷"). Such a job reads as
 * [JobStatus.FAILED] whatever the row says, and nothing overwrites the snapshot, so an updated app
 * decodes it and runs it as it was written.
 */
data class Job(
    val id: String,
    val recordingId: String,
    val workflowId: String,
    val workflow: Workflow?,
    val status: JobStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val nextRunAt: Instant?,
    /** A [recly.core.message.CoreMessage] code, for the row the list draws. */
    val snapshotError: String? = null,
)

/**
 * [state] is the step's own resume point (Drive session URI and offset); [output] is what later
 * steps read. [attempts] counts failures, so `attempts >= retry.maxAttempts` means the budget is
 * spent.
 */
data class StepRun(
    val id: String,
    val jobId: String,
    val stepId: String,
    val ordinal: Int,
    val status: StepStatus,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastError: String?,
    val state: JsonObject?,
    val output: JsonObject?,
)

/**
 * One string field of [StepRun.output], read without the caller ever holding the JSON tree.
 *
 * Swift must not touch [StepRun.output]. Kotlin/Native exports a `JsonObject` as an
 * `NSDictionary<NSString *, JsonElement *>`, and reading that property from Swift force-bridges the
 * whole map — every value cast to `JsonElement`. A `drive.upload` output carries its `files` as a
 * `JsonArray`, which crosses the bridge as an `NSArray` and fails that cast, aborting the process in
 * `swift_dynamicCastFailure`. Asking Kotlin for the one field keeps the tree on this side.
 *
 * A missing key, a JSON `null`, an array and an object all answer null: this asks for a string, and
 * none of those is one.
 */
fun StepRun.outputString(key: String): String? =
    (output?.get(key) as? JsonPrimitive)?.contentOrNull
