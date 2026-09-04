package app.recly.android.ui

/**
 * docs/09 "타이포": `00:12:34` — fixed width, because a timer that reflows is a distraction. The
 * hours are not wrapped at 24; a recording is not a clock.
 */
internal fun hms(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}
