@file:OptIn(ExperimentalTime::class)

package app.recly.windows.core

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `2026-08-26T01:00:00.000Z` — the shape docs/01 fixes for every timestamp the core stores. The
 * core's own formatter is `internal`, so the shells carry their own copy of the format (the phone's
 * is `android/recording/.../Time.kt`).
 */
internal fun Instant.isoUtc(): String =
    ISO_UTC.format(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()))

private val ISO_UTC: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
