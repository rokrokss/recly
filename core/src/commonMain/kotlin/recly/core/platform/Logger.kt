package recly.core.platform

/**
 * Structured logging. Event names are shared by every client (docs/20) — `rec.finalize`,
 * `job.step.start`, `job.step.ok`, `job.step.fail`, `job.done`, `job.failed` — so spike results can
 * be collected across platforms.
 */
interface Logger {
    fun log(level: Level, event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null)

    enum class Level { DEBUG, INFO, WARN, ERROR }
}
