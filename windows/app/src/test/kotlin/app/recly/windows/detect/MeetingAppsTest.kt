package app.recly.windows.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/14 "감지": which process a microphone gets attributed to. The Mac's rule in Windows' terms
 * (`MeetingAppMonitor`), and the browser half is the half worth a test — a browser is always
 * running, so "Chrome is open" must never be a meeting.
 */
class MeetingAppsTest {

    private val browsing = listOf("Inbox (12) — Gmail", "Recly — GitHub")

    @Test
    fun `a running meeting app is the answer, whatever its case`() {
        assertEquals(
            "ms-teams.exe",
            MeetingApps.attribute(listOf("explorer.exe", "ms-teams.exe"), { browsing }),
        )
        assertEquals("Discord.exe", MeetingApps.attribute(listOf("Discord.exe"), { browsing }))
    }

    /** A browser with no meeting in it is somebody reading their mail. */
    @Test
    fun `a browser alone is not a meeting`() {
        assertNull(MeetingApps.attribute(listOf("chrome.exe", "explorer.exe"), { browsing }))
    }

    @Test
    fun `a browser whose window is named like a meeting is one`() {
        assertEquals(
            "chrome.exe",
            MeetingApps.attribute(listOf("chrome.exe")) { browsing + "Google Meet — 주간 회의" },
        )
        assertEquals(
            "msedge.exe",
            MeetingApps.attribute(listOf("msedge.exe")) { listOf("Microsoft Teams") },
        )
    }

    /** The titles cost a round trip through the window server; a native app has already answered. */
    @Test
    fun `the window titles are not asked for when a meeting app is running`() {
        var asked = false
        assertEquals(
            "zoom.exe",
            MeetingApps.attribute(listOf("zoom.exe", "chrome.exe")) {
                asked = true
                emptyList()
            },
        )
        assertTrue(!asked)
    }

    @Test
    fun `nothing running is nothing to offer`() {
        assertNull(MeetingApps.attribute(emptyList(), { browsing }))
    }
}
