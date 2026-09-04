@file:OptIn(ExperimentalTime::class)

package recly.core.sync

import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.workflow.ParseResult
import recly.core.workflow.WorkflowParser

/**
 * What the watch is told about a workflow — it never sees steps (docs/05). Two fields, because
 * after ADR-016 there is nothing else about a definition the watch could act on: which one is
 * default is the watch's own local pick, not something the document says.
 */
data class WorkflowSummary(
    val id: String,
    val name: String,
)

sealed interface SaveResult {
    data class Saved(val document: WorkflowsDocument) : SaveResult

    /** Nothing was written: the document does not satisfy docs/02. */
    data class Invalid(val errors: List<String>) : SaveResult
}

/** What [WorkflowRepository.importJson] made of a file the user picked. */
sealed interface ImportResult {
    /** The whole document was replaced; [workflows] is how many the device now has. */
    data class Imported(val workflows: Int) : ImportResult

    /** Nothing was written: the file does not parse, or does not satisfy docs/02. */
    data class Invalid(val errors: List<String>) : ImportResult
}

/**
 * This device's `workflows.json` — what the executor, the UI and the watch summary read. It is
 * local and it stays local (docs/05): nothing syncs it, and moving definitions to another device is
 * [exportJson]/[importJson].
 */
class WorkflowRepository(
    private val store: WorkflowStore,
    private val deviceDefaults: DeviceDefaultStore,
    private val deps: CoreDeps,
) {
    /**
     * The two docs/05 starters stand in the first time a device asks, so a job always has something
     * to run against — even on a fresh install, even offline.
     */
    suspend fun current(): WorkflowsDocument = store.seedIfEmpty(defaults())

    /**
     * [current], plus this device's default pointed at [preferredDefaultId] ([MEMO_ID], the one
     * starter there is: the shell names the id so that the pick stays a shell decision and nothing
     * in the core has to know which it is).
     *
     * The pick is conditional on there being no pointer yet, so it is only ever made for a user who
     * has not chosen — and once made it stands: there is no sync left to take it back. Which
     * startup path planted the starters does not matter for the same reason; call it at startup and
     * it is [current] on every later call.
     */
    @Throws(Throwable::class)
    suspend fun seed(preferredDefaultId: String): WorkflowsDocument {
        val document = current()
        if (document.workflows.any { it.id == preferredDefaultId }) {
            deviceDefaults.writeIfNull(preferredDefaultId)
        }
        return document
    }

    /**
     * Validates against docs/02 before anything is stored — the editor is not the only caller and a
     * document that cannot be parsed back would be unrunnable.
     */
    suspend fun save(document: WorkflowsDocument): SaveResult {
        val stamped = document.copy(
            revision = document.revision + 1,
            updatedAt = deps.clock.now().isoUtc(),
            updatedBy = deps.device.deviceId,
        )
        val errors = validate(stamped)
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)
        store.write(stamped)
        // Deleting this device's default is allowed (ADR-016) and leaves the pointer with nothing to
        // resolve, so it goes with it and the shells ask for a new pick.
        deviceDefaults.read()?.let { id ->
            // Conditional: a concurrent setDeviceDefault of a *new* choice beats this clear.
            if (stamped.workflows.none { it.id == id }) deviceDefaults.clearIf(id)
        }
        return SaveResult.Saved(stamped)
    }

    /** Emits the stored document on every change to it. */
    fun observe(): Flow<WorkflowsDocument> =
        store.observeLocal().mapNotNull { json -> json?.let { WorkflowStore.document(it) } }

    /** ADR-016: the id this device falls back to, or null while it has not been chosen. */
    @Throws(Throwable::class)
    suspend fun deviceDefault(): String? = deviceDefaults.read()

    /** Null unsets it. */
    @Throws(Throwable::class)
    suspend fun setDeviceDefault(workflowId: String?) {
        deviceDefaults.write(workflowId)
    }

    /** What the delete confirmation asks before it warns that this is the device's own default. */
    @Throws(Throwable::class)
    suspend fun isDeviceDefault(workflowId: String): Boolean = deviceDefaults.read() == workflowId

    /** Emits on every change to the pointer — the row's checkmark and the record screen read it. */
    fun observeDeviceDefault(): Flow<String?> = deviceDefaults.observe()

    suspend fun summary(): List<WorkflowSummary> = current().workflows.map {
        WorkflowSummary(it.id, it.name)
    }

    /**
     * "워크플로우 내보내기" (docs/05): the stored document, serialized exactly as it is stored. The
     * device-default pointer is not in it and neither are the secret *values* — a definition names
     * a `secretRef`, and the key behind it stays on the device that holds it.
     *
     * The shell writes the bytes wherever the user chose; the core has no idea where that is.
     */
    @Throws(Throwable::class)
    suspend fun exportJson(): String = WorkflowParser.serialize(current())

    /**
     * "워크플로우 가져오기" (docs/05): the file replaces the whole document. There is no merge —
     * two devices' documents have nothing to merge *on* now that neither is a copy of the other, so
     * the honest offer is the one the confirmation dialog makes ("N개 워크플로우로 교체합니다").
     *
     * A file written by an older build migrates on the way in exactly as a stored copy does, and a
     * file that does not parse leaves the device untouched and comes back as the parser's own
     * errors — the same list the editor shows.
     */
    @Throws(Throwable::class)
    suspend fun importJson(json: String): ImportResult = when (val parsed = WorkflowParser.parse(json)) {
        is ParseResult.Ok -> when (val saved = save(parsed.document)) {
            is SaveResult.Saved -> ImportResult.Imported(saved.document.workflows.size)
            is SaveResult.Invalid -> ImportResult.Invalid(saved.errors)
        }

        else -> ImportResult.Invalid(unreadable(parsed))
    }

    /** The parser is the one place docs/02 lives, so validation is a round trip through it. */
    private fun validate(document: WorkflowsDocument): List<String> =
        when (val parsed = WorkflowParser.parse(WorkflowParser.serialize(document))) {
            is ParseResult.Ok -> emptyList()
            else -> unreadable(parsed)
        }

    /** Everything a parse can say other than [ParseResult.Ok], as the shells show it. */
    private fun unreadable(parsed: ParseResult): List<String> = when (parsed) {
        is ParseResult.Invalid -> parsed.errors
        is ParseResult.UnsupportedSchema -> listOf("schema ${parsed.schema} is not supported")
        is ParseResult.MigrationBlocked ->
            listOf("schema ${parsed.schema} carries unknown fields: ${parsed.fields.joinToString()}")
        // Unreachable: the caller has already matched it.
        is ParseResult.Ok -> emptyList()
    }

    /**
     * docs/07 §6: the seed names are written in the language the app was in when the device first
     * asked, and stay that way — they are the user's data from that moment on, not a translation.
     */
    internal fun defaults(locale: String = deps.locale): WorkflowsDocument {
        val korean = locale.substringBefore('-').equals(KOREAN, ignoreCase = true)
        val stamp = PLACEHOLDER_UPDATED_AT
        return WorkflowsDocument(
            schema = WorkflowParser.SCHEMA,
            revision = 0,
            updatedAt = stamp,
            updatedBy = deps.device.deviceId,
            workflows = listOf(
                Workflow(
                    id = MEMO_ID,
                    name = if (korean) "메모" else "Memo",
                    updatedAt = stamp,
                    steps = listOf(
                        Step.DriveUpload(
                            id = "upload",
                            folder = "recly/memo/{{yyyy}}-{{MM}}",
                        ),
                    ),
                ),
            ),
        )
    }

    companion object {
        /**
         * The docs/05 starter carries a well-known id rather than a fresh ULID, so that a shell can
         * ask for it by name ([seed]) without having to read the document first. It is a valid ULID
         * (a zero timestamp, a mnemonic suffix) so nothing downstream has to special-case it.
         */
        const val MEMO_ID = "00000000000000000000RECMEM"

        /** The epoch: a starter nobody has edited yet. The first edit gives it a real one. */
        const val PLACEHOLDER_UPDATED_AT = "1970-01-01T00:00:00.000Z"

        /** docs/07 rule 1: Korean for any `ko` locale, English for everything else. */
        private const val KOREAN = "ko"
    }
}
