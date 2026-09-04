package app.recly.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * docs/09 "모션": motion is a state signal, never decoration. One easing, one duration for a normal
 * transition, a shorter one for a badge, and a deliberate window for the rare high-risk action —
 * start/stop, upload, sign-in, save — so the user sees that something happened.
 */
object Motion {
    /** `ease-in-out 200ms`. */
    const val STANDARD_MS: Int = 200

    /** A status badge fading between two states. */
    const val BADGE_FADE_MS: Int = 150

    /** The processing state ("…") is on screen at least this long, even if the work was instant. */
    const val PROCESSING_MIN_MS: Long = 400

    /** …and the processing state plus its completion badge do not run past this. */
    const val PROCESSING_MAX_MS: Long = 800

    val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/**
 * How much longer the "…" has to stay after the work finished in [workMs]. Instant work is padded
 * up to [Motion.PROCESSING_MIN_MS]; work that already took that long is not padded at all.
 *
 * Reduce motion does not shorten this. docs/09 "모션" asks for "즉시 전환 + 텍스트 상태만" — instant
 * transitions *and* the text state, not no state at all — and a user who has turned animations off
 * is the one with nothing else to tell them the stop was heard.
 */
fun processingHoldMs(workMs: Long): Long = (Motion.PROCESSING_MIN_MS - workMs).coerceAtLeast(0L)

/**
 * How long the completion badge ("✓") then stands. It fills out the [Motion.PROCESSING_MAX_MS]
 * window that the processing state opened, and never flashes for less than one fade
 * ([Motion.BADGE_FADE_MS]) when the work overran that window — with reduce motion there is no fade,
 * but the letters still need that long to be read.
 */
fun doneBadgeMs(workMs: Long): Long {
    val shown = maxOf(workMs, Motion.PROCESSING_MIN_MS)
    return (Motion.PROCESSING_MAX_MS - shown).coerceAtLeast(Motion.BADGE_FADE_MS.toLong())
}

/** What a processing button draws at one moment — not what the operation behind it is doing. */
enum class ProcessingPhase { IDLE, PROCESSING, DONE }

/**
 * The phase [elapsedMs] after the operation started, from the operation's own outcome:
 * [succeeded] is null while it is still running, true when it worked, false when it failed.
 *
 * Running work holds the processing state however long it takes; a result that arrived earlier
 * waits out [processingHoldMs] before it shows. Only a success then wears the badge — a failure
 * goes straight back to idle, because the screen is the one that says what went wrong.
 */
fun processingPhase(succeeded: Boolean?, workMs: Long, elapsedMs: Long): ProcessingPhase {
    if (succeeded == null) return ProcessingPhase.PROCESSING
    val holdEnd = workMs + processingHoldMs(workMs)
    return when {
        elapsedMs < holdEnd -> ProcessingPhase.PROCESSING
        !succeeded -> ProcessingPhase.IDLE
        elapsedMs < holdEnd + doneBadgeMs(workMs) -> ProcessingPhase.DONE
        else -> ProcessingPhase.IDLE
    }
}
