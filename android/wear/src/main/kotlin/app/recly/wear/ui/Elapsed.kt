package app.recly.wear.ui

/**
 * docs/09 "Typography" (UX decisions of 2026-10-08): `MM:SS` under an hour and `HH:MM:SS` from one, as
 * every shell writes a live timer — `00:12` … `59:59` → `01:00:00`. A three-hour meeting is the case
 * docs/20 S1 measures, so the hour is not optional; a short memo does not spend the width on it.
 */
fun formatElapsed(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "${hours.pad()}:${minutes.pad()}:${secs.pad()}"
    } else {
        "${minutes.pad()}:${secs.pad()}"
    }
}

private fun Long.pad(): String = toString().padStart(2, '0')
