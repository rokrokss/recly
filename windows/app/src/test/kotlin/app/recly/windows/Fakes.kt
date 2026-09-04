@file:OptIn(ExperimentalTime::class)

package app.recly.windows

import app.recly.windows.core.Secrets
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.settings.AppTheme
import app.recly.windows.settings.RecordingMode
import app.recly.windows.settings.Settings
import app.recly.windows.ui.DisconnectPhase
import java.util.prefs.BackingStoreException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.model.Workflow
import recly.core.platform.Clock
import recly.core.platform.Logger
import recly.core.platform.SecureStore

/** What every test in this module needs and nothing more. */
val NOW: Instant = Instant.parse("2026-08-27T10:00:00Z")

class FixedClock(var instant: Instant = NOW) : Clock {
    override fun now(): Instant = instant
}

object SilentLogger : Logger {
    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) = Unit
}

/** The `DevFileSecureStore` without the file — the same namespaced map, in memory. */
class MemorySecureStore : SecureStore {
    private val entries = mutableMapOf<String, ByteArray>()

    override suspend fun get(ns: String, key: String): ByteArray? = entries["$ns/$key"]

    override suspend fun put(ns: String, key: String, value: ByteArray) {
        entries["$ns/$key"] = value
    }

    override suspend fun delete(ns: String, key: String) {
        entries.remove("$ns/$key")
    }

    override suspend fun names(ns: String): List<String> =
        entries.keys.filter { it.startsWith("$ns/") }.map { it.removePrefix("$ns/") }.sorted()
}

/** [Secrets] without the core's secure store behind it — the names and values, in memory. */
class MemorySecrets : Secrets {
    private val values = mutableMapOf<String, String>()

    override suspend fun names(): List<String> = values.keys.sorted()

    override suspend fun put(name: String, value: String) {
        values[name] = value
    }

    override suspend fun delete(name: String) {
        values.remove(name)
    }
}

/** The only fields the executor wiring looks at; the rest is a valid but empty workflow. */
fun job(
    id: String,
    status: JobStatus,
    nextRunAt: Instant? = null,
    createdAt: Instant = NOW,
): Job = Job(
    id = id,
    recordingId = "rec-$id",
    workflowId = "wf",
    workflow = Workflow(
        id = "wf",
        name = "wf",
        updatedAt = "2026-08-27T00:00:00.000Z",
        steps = emptyList(),
    ),
    status = status,
    createdAt = createdAt,
    updatedAt = createdAt,
    nextRunAt = nextRunAt,
)

/** [Settings] as the plain data holder it is on a PC, minus the registry. */
class FakeSettings(
    override var consentReminder: Boolean = true,
    override var recordingMode: RecordingMode = RecordingMode.MEETING,
    override var language: AppLanguage = AppLanguage.SYSTEM,
    override var theme: AppTheme = AppTheme.SYSTEM,
    override var disconnectPhase: DisconnectPhase = DisconnectPhase.NONE,
    override var revokeDebt: Boolean = false,
) : Settings

/**
 * A `java.util.prefs` node whose flush throws — a registry hive that is read-only, or gone. The two
 * settings a disconnect is about are the only ones written through synchronously, so they are the
 * only ones that can refuse; everything else is [delegate]'s and works.
 */
class FailingSettings(private val delegate: Settings = FakeSettings()) : Settings by delegate {

    override var disconnectPhase: DisconnectPhase
        get() = delegate.disconnectPhase
        set(value) = throw BackingStoreException("read-only hive")

    override var revokeDebt: Boolean
        get() = delegate.revokeDebt
        set(value) = throw BackingStoreException("read-only hive")
}
