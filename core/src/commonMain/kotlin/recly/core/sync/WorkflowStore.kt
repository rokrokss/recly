package recly.core.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import recly.core.db.RecDatabase
import recly.core.model.WorkflowsDocument
import recly.core.platform.CoreDeps
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

/**
 * The device's `workflows.json` (docs/05). One row of the `sync_state` key/value table, beside the
 * device-default pointer's — both of them local, both of them the user's own configuration of this
 * device, neither of them going anywhere else.
 *
 * Same mutex-on-[CoreDeps.io] discipline as the other stores.
 */
class WorkflowStore(
    private val db: RecDatabase,
    private val deps: CoreDeps,
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    /** The stored document, or null when there is none — or when its bytes no longer parse. */
    suspend fun read(): WorkflowsDocument? = locked { stored().document }

    /** Replaces the stored document. Serialized at the current schema, which is what makes an
     * older copy's migration permanent the first time anything is saved over it. */
    suspend fun write(document: WorkflowsDocument): Unit = locked { put(document) }

    /**
     * Check-and-seed under one hold of the lock: two startup paths asking at once — a background
     * enqueue's `current()` and the shell's own `seed()` — must not both insert, and a save landing
     * between the check and the write must not be replaced by the seed.
     */
    suspend fun seedIfEmpty(seed: WorkflowsDocument): WorkflowsDocument = locked {
        val state = stored()
        state.document?.let { return@locked it }
        // A stored copy that no longer parses is unreadable, not absent: seeding over it would
        // destroy the user's bytes (docs/02). The caller still gets the defaults to run with; the
        // row itself is replaced only by an explicitly saved document.
        if (state.present) return@locked seed
        put(seed)
        seed
    }

    /** Every write of the document, deduplicated — the UI redraws on a real change only. */
    fun observeLocal(): Flow<String?> =
        queries.syncGet(LOCAL_DOC).asFlow().mapToOneOrNull(deps.io).distinctUntilChanged()

    /**
     * [present] is the row, [document] is what it parses to. Null with [present] true means
     * unreadable, not absent (docs/02: a document carrying a step type a later build removed) —
     * the difference between seeding and destroying.
     */
    private class Stored(val document: WorkflowsDocument?, val present: Boolean)

    private fun stored(): Stored {
        val raw = queries.syncGet(LOCAL_DOC).executeAsOneOrNull()
        return Stored(raw?.let(::document), raw != null)
    }

    private fun put(document: WorkflowsDocument) {
        queries.syncSet(LOCAL_DOC, WorkflowParser.serialize(document))
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }

    internal companion object {
        const val LOCAL_DOC = "localDoc"

        /** The stored copy was written by this app after it validated; one that no longer parses
         * (an older build's step type, say) reads as absent — but its bytes stay in the row until a
         * parsed document replaces them. */
        fun document(json: String): WorkflowsDocument? =
            (WorkflowParser.parse(json) as? ParseResult.Ok)?.document
    }
}
