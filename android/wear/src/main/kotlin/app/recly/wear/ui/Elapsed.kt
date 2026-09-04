package app.recly.wear.ui

/**
 * `12:34` under an hour, `1:02:03` over it. A three-hour meeting is the case docs/20 S1 measures,
 * so the hour is not optional — but showing `00:12:34` for a short memo would waste the width a
 * watch does not have.
 */
fun formatElapsed(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "$hours:${minutes.pad()}:${secs.pad()}"
    } else {
        "${minutes.pad()}:${secs.pad()}"
    }
}

private fun Long.pad(): String = toString().padStart(2, '0')
