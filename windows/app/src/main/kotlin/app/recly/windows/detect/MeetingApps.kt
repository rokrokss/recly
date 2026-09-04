package app.recly.windows.detect

/**
 * Which meeting app to attribute a microphone to (docs/14 "감지": `Zoom.exe`, `ms-teams.exe`,
 * `slack.exe`, `Discord.exe`, browser window titles).
 *
 * The Mac reads bundle ids; Windows names processes, so this is the same rule spelled in executable
 * names. Matching is case-insensitive because the registry, the process table and the user's memory
 * disagree about `Discord.exe` / `discord.exe`.
 *
 * A native meeting app counts because it is running. A browser does not — a browser is always
 * running — so it counts only while one of its windows is named like a meeting, which is the same
 * line the Mac draws (docs/12 "브라우저 Meet은 창 제목까지만").
 */
object MeetingApps {

    /** docs/14 "감지". Lowercased; [attribute] answers with the name it was given. */
    val PROCESSES: Set<String> = setOf("zoom.exe", "ms-teams.exe", "slack.exe", "discord.exe")

    /** Being in this set is not a signal by itself — see [attribute]. */
    val BROWSERS: Set<String> = setOf(
        "chrome.exe",
        "msedge.exe",
        "firefox.exe",
        "brave.exe",
        "opera.exe",
        "arc.exe",
    )

    /** What a browser window has to be called before it counts. Lowercased on both sides. */
    val TITLES: List<String> = listOf("meet.google.com", "google meet", "zoom", "microsoft teams", "webex")

    /**
     * The process to attribute a recording to, or `null`. [windowTitles] is only asked for when a
     * browser is the only candidate left: enumerating every top-level window costs a round trip that
     * a running `ms-teams.exe` has already made unnecessary.
     */
    fun attribute(processes: Collection<String>, windowTitles: () -> List<String>): String? {
        processes.firstOrNull { it.lowercase() in PROCESSES }?.let { return it }
        val browser = processes.firstOrNull { it.lowercase() in BROWSERS } ?: return null
        val meeting = windowTitles().any { title ->
            val lower = title.lowercase()
            TITLES.any { lower.contains(it) }
        }
        return if (meeting) browser else null
    }
}
