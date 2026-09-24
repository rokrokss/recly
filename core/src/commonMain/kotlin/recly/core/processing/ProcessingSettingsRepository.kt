@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.processing

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import recly.core.db.RecDatabase
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.transcribe.TranscriptionLanguages
import recly.core.transcribe.installed

/**
 * [NotInitialized] until [ProcessingSettingsRepository.initialize] has run. A stored document this
 * build cannot read counts as none: initializing replaces it with the defaults.
 */
sealed interface ProcessingSettingsState {
    data object NotInitialized : ProcessingSettingsState
    data class Ready(val document: ProcessingSettingsDocument) : ProcessingSettingsState
}

sealed interface ProcessingSaveResult {
    data class Saved(val document: ProcessingSettingsDocument) : ProcessingSaveResult
    data class Invalid(val errors: List<String>) : ProcessingSaveResult
    data object Stale : ProcessingSaveResult
    data object Unavailable : ProcessingSaveResult
}

/**
 * docs/05: this device's processing preferences. A recording freezes them when it starts ([capture]),
 * so an edit only ever changes recordings made after it.
 */
class ProcessingSettingsRepository(private val db: RecDatabase, private val deps: CoreDeps) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    @Throws(Throwable::class)
    suspend fun read(): ProcessingSettingsState = locked { readStored() }

    /** Idempotent. A fresh install — or a stored document this build cannot read — gets the defaults. */
    @Throws(Throwable::class)
    suspend fun initialize(): ProcessingSettingsState.Ready = locked {
        db.transactionWithResult {
            (readStored() as? ProcessingSettingsState.Ready)?.let { return@transactionWithResult it }
            // A build without an on-device runtime must not default to a mode that always fails.
            val document = stamp(ProcessingSettings(transcription = ProcessingTranscription(
                language = TranscriptionLanguages.preferred(deps.locale),
                mode = if (deps.localTranscription.installed) TranscriptionMode.LOCAL else TranscriptionMode.OFF,
            )), 1)
            write(document)
            ProcessingSettingsState.Ready(document)
        }
    }

    /** A checked revision protects two windows and import-versus-edit races. */
    @Throws(Throwable::class)
    suspend fun save(settings: ProcessingSettings, expectedRevision: Int): ProcessingSaveResult = locked {
        db.transactionWithResult {
            val current = readStored() as? ProcessingSettingsState.Ready
                ?: return@transactionWithResult ProcessingSaveResult.Unavailable
            if (current.document.revision != expectedRevision) return@transactionWithResult ProcessingSaveResult.Stale
            if (expectedRevision == Int.MAX_VALUE) {
                return@transactionWithResult ProcessingSaveResult.Invalid(listOf("revision limit reached"))
            }
            saveValidated(stamp(settings, expectedRevision + 1))
        }
    }

    /** Call only after the import preview has been accepted; the source revision is not adopted. */
    @Throws(Throwable::class)
    suspend fun importJson(json: String, expectedRevision: Int): ProcessingSaveResult =
        when (val parsed = ProcessingSettingsParser.parse(json)) {
            is ProcessingParseResult.Valid -> save(parsed.document.settings, expectedRevision)
            is ProcessingParseResult.Invalid -> ProcessingSaveResult.Invalid(parsed.errors)
            is ProcessingParseResult.UnsupportedSchema -> ProcessingSaveResult.Invalid(listOf("unsupported settings schema"))
        }

    /** Returns null for missing/unreadable settings, never a fabricated default export. */
    @Throws(Throwable::class)
    suspend fun exportJson(): String? = locked {
        (readStored() as? ProcessingSettingsState.Ready)?.document?.let(ProcessingSettingsParser::serialize)
    }

    fun observe(): Flow<ProcessingSettingsState> = queries.syncGet(KEY).asFlow().mapToOneOrNull(deps.io)
        .map(::decode).distinctUntilChanged()

    /** Freeze preferences at capture start, or at verified watch receipt. Never replace a snapshot. */
    @Throws(Throwable::class)
    suspend fun capture(recordingId: String) {
        val document = initialize().document
        locked {
            db.transaction {
                if (queries.syncGet(RECORDING_PREFIX + recordingId).executeAsOneOrNull() == null) {
                    queries.syncSet(RECORDING_PREFIX + recordingId, ProcessingSettingsParser.serialize(document))
                }
            }
        }
    }

    /** Null when nothing was frozen for [recordingId], or what was frozen no longer reads. */
    @Throws(Throwable::class)
    suspend fun recordingSnapshot(recordingId: String): ProcessingSettingsDocument? = locked {
        queries.syncGet(RECORDING_PREFIX + recordingId).executeAsOneOrNull()
            ?.let(ProcessingSettingsParser::parse)?.let { (it as? ProcessingParseResult.Valid)?.document }
    }

    private fun saveValidated(document: ProcessingSettingsDocument): ProcessingSaveResult {
        val errors = ProcessingSettingsParser.validate(document)
        if (errors.isNotEmpty()) return ProcessingSaveResult.Invalid(errors)
        write(document)
        return ProcessingSaveResult.Saved(document)
    }

    private fun stamp(settings: ProcessingSettings, revision: Int) = ProcessingSettingsDocument(
        revision = revision, updatedAt = deps.clock.now().isoUtc(), updatedBy = deps.device.deviceId,
        settings = settings.forNewRecordings(),
    )

    private fun readStored() = decode(raw())
    private fun raw() = queries.syncGet(KEY).executeAsOneOrNull()
    private fun write(document: ProcessingSettingsDocument) = queries.syncSet(KEY, ProcessingSettingsParser.serialize(document))

    private fun decode(raw: String?): ProcessingSettingsState = when (raw) {
        null -> ProcessingSettingsState.NotInitialized
        else -> when (val parsed = ProcessingSettingsParser.parse(raw)) {
            is ProcessingParseResult.Valid -> ProcessingSettingsState.Ready(
                parsed.document.copy(settings = parsed.document.settings.forNewRecordings()),
            )
            else -> ProcessingSettingsState.NotInitialized
        }
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }

    internal companion object {
        const val KEY = "processing/settings"
        private const val RECORDING_PREFIX = "processing/recording/"
    }
}
