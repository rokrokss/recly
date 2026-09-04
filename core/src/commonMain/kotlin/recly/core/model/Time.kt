@file:OptIn(ExperimentalTime::class)

package recly.core.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * `2026-08-26T01:00:00.000Z` — always UTC, always three fraction digits (docs/01). `Instant`'s own
 * `toString` drops a zero fraction, which would break the `next_run_at <= :now` string comparison
 * the job queue is built on.
 */
internal fun Instant.isoUtc(): String {
    val t = toLocalDateTime(TimeZone.UTC)
    val date = "${pad(t.year, 4)}-${pad(t.month.number)}-${pad(t.day)}"
    val time = "${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}.${pad(t.nanosecond / 1_000_000, 3)}"
    return "${date}T${time}Z"
}

private fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
