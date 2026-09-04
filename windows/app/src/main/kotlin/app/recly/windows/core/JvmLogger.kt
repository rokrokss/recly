@file:OptIn(ExperimentalTime::class)

package app.recly.windows.core

import java.io.PrintStream
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import recly.core.platform.Logger

/**
 * One line per event on stdout, the core's event name first, so the shared stream docs/20 expects
 * (`rec.finalize`, `job.step.ok`, `sync.push`…) reads the same here as in `logcat -s recly`. The
 * ring buffer and log export are a later lane, as they are on the phone.
 */
class JvmLogger(private val out: PrintStream = System.out) : Logger {
    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
        val rendered = if (fields.isEmpty()) event else "$event ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        out.println("${Clock.System.now()} ${level.name} $rendered")
        error?.printStackTrace(out)
    }
}
