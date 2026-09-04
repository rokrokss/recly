@file:OptIn(ExperimentalTime::class)

package recly.core.workflow

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import recly.core.model.RecordingMeta
import recly.core.model.wire

/** The docs/02 variable table, already resolved for one recording. */
class TemplateContext(val values: Map<String, String>) {
    companion object {
        /**
         * Clock fields are the recording's `startedAt` seen in [zone] — the meta's own timezone
         * unless the caller overrides it — so `recly/{{yyyy}}-{{MM}}` matches the user's calendar,
         * not UTC.
         */
        fun of(
            meta: RecordingMeta,
            workflowName: String,
            zone: TimeZone = TimeZone.of(meta.timezone),
        ): TemplateContext {
            val t = Instant.parse(meta.startedAt).toLocalDateTime(zone)
            return TemplateContext(
                mapOf(
                    "yyyy" to pad(t.year, 4),
                    "MM" to pad(t.month.number),
                    "dd" to pad(t.day),
                    "HH" to pad(t.hour),
                    "mm" to pad(t.minute),
                    "title" to (meta.title ?: workflowName),
                    "source" to meta.source.wire,
                    "recordingId" to meta.recordingId,
                    "workflowName" to workflowName,
                    "device" to meta.deviceName,
                ),
            )
        }

        private fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
    }
}

object Template {
    private val VARIABLE = Regex("\\{\\{([^}]*)\\}\\}")

    /**
     * @throws IllegalArgumentException on a variable the spec does not define. The parser rejects
     * those already; this is the second line of defence for templates that never went through it.
     */
    fun render(template: String, ctx: TemplateContext): String =
        VARIABLE.replace(template) { match ->
            val name = match.groupValues[1].trim()
            val value = ctx.values[name]
                ?: throw IllegalArgumentException("unknown template variable '{{$name}}'")
            sanitize(value)
        }

    /** Values end up in path segments, so separators and control characters cannot survive (docs/02). */
    private fun sanitize(value: String): String =
        value.map { if (it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F) '_' else it }
            .joinToString("")
            .trim()
}
