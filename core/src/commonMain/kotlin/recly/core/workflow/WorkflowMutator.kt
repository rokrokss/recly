package recly.core.workflow

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.model.WorkflowsDocument
import recly.core.sync.SaveResult

/**
 * The document as a mutation sees it — `core.workflows` in the app, a fake in tests. Only the two
 * things a write needs: what is stored right now, and the write itself. Reading for display goes to
 * `observe()` directly.
 */
interface WorkflowDocuments {
    suspend fun current(): WorkflowsDocument
    suspend fun save(document: WorkflowsDocument): SaveResult
}

/** The `updatedAt` a workflow carried when the editor opened it — its version (docs/05 "저장 · 편집"). */
data class OpenedOn(val id: String, val updatedAt: String)

/**
 * Which editor is on screen, so a result can tell whether it is still talking to it (Sol M2-L4 #1).
 *
 * A save is asynchronous: validation and the write both happen while the user goes on
 * using the app, and by the time it comes back the editor it was started from may be gone and
 * another one open in its place. Closing *that* one would throw away edits nobody asked to discard.
 * So each opened editor gets a token, every mutation started from one carries the token it saw, and
 * only a result whose token is still [isCurrent] may close the editor or mark it stale or invalid.
 */
class EditorSessions {

    private var opened = 0L

    /** The editor on screen; 0 is "none", which no token ever equals. */
    private var current = 0L

    /** A newly opened editor — nothing started from an older one may touch it. */
    fun open(): Long {
        opened += 1
        current = opened
        return opened
    }

    /** No editor on screen: a result arriving now has nothing of its own left to change. */
    fun close() {
        current = 0L
    }

    /** True when [session] is the editor the user is looking at right now. Null is a list write. */
    fun isCurrent(session: Long?): Boolean = session != null && session == current
}

sealed interface MutationResult {
    data class Saved(val document: WorkflowsDocument) : MutationResult

    data class Invalid(val errors: List<String>) : MutationResult

    /** Something replaced the workflow while it was being edited — a second window on this device,
     * or an import: there is no merge, so it is refused. */
    data object Stale : MutationResult

    /** The mutation found nothing to change (the workflow is already gone). */
    data object Skipped : MutationResult
}

/**
 * The one gate every document write goes through.
 *
 * Two things it exists for. Serialization: a toggle, a delete and a save each rewrite the whole
 * `WorkflowsDocument`, so two of them applied to the same snapshot lose one of the changes — the
 * mutation is therefore applied to a document read *inside* the lock, never to one captured
 * before it. And staleness: a write that lands while the editor is open — a second window, an
 * import — replaces the workflow under it, and saving the editor's copy on top would silently undo
 * it; with no three-way merge the honest answer is to refuse and let the user reopen.
 */
class WorkflowMutator(private val documents: WorkflowDocuments) {

    private val mutex = Mutex()

    /**
     * @param expect the version the caller last saw, for an edit that started from a stored
     *   workflow. Null for anything that does not care what the workflow looked like before —
     *   a new workflow, a delete, a toggle that reads the fresh value itself.
     * @param block the change, applied to the document as it is inside the lock. Null means
     *   "nothing to do" and writes nothing.
     */
    suspend fun mutate(
        expect: OpenedOn? = null,
        block: suspend (WorkflowsDocument) -> WorkflowsDocument?,
    ): MutationResult = mutex.withLock {
        val current = documents.current()
        if (expect != null) {
            val stored = current.workflows.firstOrNull { it.id == expect.id }
            if (stored == null || stored.updatedAt != expect.updatedAt) return MutationResult.Stale
        }
        val next = block(current) ?: return MutationResult.Skipped
        when (val result = documents.save(next)) {
            is SaveResult.Invalid -> MutationResult.Invalid(result.errors)
            is SaveResult.Saved -> MutationResult.Saved(result.document)
        }
    }
}
