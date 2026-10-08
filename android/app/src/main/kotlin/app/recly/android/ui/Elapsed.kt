package app.recly.android.ui

/**
 * docs/09 "Typography" (UX decisions of 2026-10-08): `MM:SS` under an hour and `HH:MM:SS` from one —
 * fixed width either way, because a timer that reflows is a distraction. [scaleSec] picks the format
 * for every time on one recording's screen: its total length, so all of them stand the same width
 * (`00:12 / 42:10`, `00:00:12 / 01:02:03`). Null — a live timer, or a recording whose length is not
 * known — lets the time itself decide, so a live timer goes from `59:59` to `01:00:00`. The hours are
 * not wrapped at 24; a recording is not a clock.
 */
internal fun clock(seconds: Long, scaleSec: Long? = null): String {
    val total = seconds.coerceAtLeast(0)
    return if (total >= HOUR || (scaleSec ?: 0) >= HOUR) {
        "%02d:%02d:%02d".format(total / HOUR, (total % HOUR) / 60, total % 60)
    } else {
        "%02d:%02d".format(total / 60, total % 60)
    }
}

/**
 * `00:12:34` always — what a screen reader is told a time is (a seek, a highlight, the playhead).
 * Spoken text keeps its one format; [clock] is what the screen draws.
 */
internal fun hms(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / HOUR, (total % HOUR) / 60, total % 60)
}

private const val HOUR = 3600L
