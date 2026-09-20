@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.drive

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import recly.core.job.JobStatus
import recly.core.job.Job
import recly.core.job.JobStore
import recly.core.message.CoreMessage
import recly.core.model.Step
import recly.core.platform.AuthRequiredException
import recly.core.platform.CoreDeps

/** Account binding for durable workflow progress. Called under the executor's gate. */
internal class DriveJobAccess(private val deps: CoreDeps, private val store: JobStore) {
    private val api = DriveApi(deps)
    private var account: String? = null
    private var token: String? = null

    fun clear() {
        account = null
        token = null
    }

    suspend fun reconnect(): Int {
        store.connectDrive()
        return refresh()
    }

    suspend fun prepare() {
        clear()
        if (!store.driveConnected()) return
        if (store.list().none {
            it.status != JobStatus.DONE && it.status != JobStatus.SKIPPED_SHORT &&
                it.workflow?.steps?.any { step -> step is Step.DriveUpload } == true
        }) return
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Due steps use the normal executor error/backoff path. A parked job stays parked
            // when the network cannot establish which Drive would receive its remaining work.
        }
    }

    private suspend fun refresh(): Int {
        account = null
        val verifyingToken = deps.tokenProvider.accessToken()
        val verified = api.accountId()
        for ((jobId, folderId) in store.unboundDriveJobs()) {
            val owners = try {
                api.getFile(folderId, "owners(permissionId)")?.get("owners") as? JsonArray
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // One inaccessible legacy folder must not block other, already-bound jobs.
                null
            }
            if (owners?.any { (it as? JsonObject)?.string("permissionId") == verified } == true) {
                store.bindDriveAccount(jobId, verified)
            }
        }
        // OAuth can finish while the account lookup is in flight. Never attach its answer to
        // credentials from a different connection; a later pass verifies the replacement token.
        if (deps.tokenProvider.accessToken() != verifyingToken) {
            throw AuthRequiredException(CoreMessage.DRIVE_REAUTH)
        }
        val resumed = store.resumeDrive(verified, deps.clock.now())
        account = verified
        token = verifyingToken
        return resumed
    }

    suspend fun requireAccess(job: Job) {
        if (job.workflow?.steps?.none { it is Step.DriveUpload } == true) return
        if (!store.driveConnected()) throw AuthRequiredException(CoreMessage.DRIVE_REAUTH)
        if (account == null || token != deps.tokenProvider.accessToken()) refresh()
        if (!store.driveAccountMatches(job.id, checkNotNull(account))) {
            throw AuthRequiredException(CoreMessage.DRIVE_REAUTH)
        }
    }
}
