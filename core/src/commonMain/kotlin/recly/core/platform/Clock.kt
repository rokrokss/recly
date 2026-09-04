@file:OptIn(ExperimentalTime::class)

package recly.core.platform

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The shell owns the wall clock so tests can advance it without waiting. */
interface Clock {
    fun now(): Instant
}
