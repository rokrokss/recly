package recly.core.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import recly.core.db.RecDatabase
import recly.core.platform.CoreDeps

/**
 * "이 기기의 기본 워크플로우" (ADR-016): the one workflow id this device falls back to when a
 * recording carries no pick of its own.
 *
 * A row of the `sync_state` table beside the document's own ([WorkflowStore]), and like it this
 * device's own configuration: it is deliberately not a field of `workflows.json`, so an exported
 * document never carries one device's pick to another. "연결 해제" leaves it alone (docs/03).
 *
 * A pointer at a workflow that is no longer in the document — an import replaced it — is not an
 * error and is not repaired here: [recly.core.workflow.WorkflowSelector] resolves it to nothing and
 * the shells ask for a new pick. A delete clears it ([WorkflowRepository.save]).
 */
class DeviceDefaultStore(
    private val db: RecDatabase,
    private val deps: CoreDeps,
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    suspend fun read(): String? = locked { queries.syncGet(KEY).executeAsOneOrNull() }

    /** Null unsets it — the device has no default again and the shells nudge for one. */
    suspend fun write(workflowId: String?): Unit = locked {
        if (workflowId == null) queries.syncDelete(KEY) else queries.syncSet(KEY, workflowId)
    }

    /** Sets the pointer only while none is set — the seeding path, which never moves a choice. */
    suspend fun writeIfNull(workflowId: String): Unit = locked {
        if (queries.syncGet(KEY).executeAsOneOrNull() == null) queries.syncSet(KEY, workflowId)
    }

    /** Clears it only while it still holds [expected] — a concurrent [write] of a new choice wins. */
    suspend fun clearIf(expected: String): Unit = locked {
        if (queries.syncGet(KEY).executeAsOneOrNull() == expected) queries.syncDelete(KEY)
    }

    /** Emits on every change, so the row's checkmark and the record screen agree without polling. */
    fun observe(): Flow<String?> =
        queries.syncGet(KEY).asFlow().mapToOneOrNull(deps.io).distinctUntilChanged()

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }

    internal companion object {
        const val KEY = "deviceDefaultWorkflowId"
    }
}
