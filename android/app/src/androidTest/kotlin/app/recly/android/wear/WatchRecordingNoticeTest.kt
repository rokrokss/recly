@file:OptIn(ExperimentalTime::class)

package app.recly.android.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.recly.android.R
import app.recly.datalayer.WearRecording
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/11 "While the paired Galaxy Watch records", as the platform takes it: the notice is what the shade is handed,
 * so its channel, flags and chronometer can only be read back on a device. The events are [WatchRecordingNotice]'s
 * own entry; Play Services, which delivers them, has no double, and pairing a watch is not something a test does.
 */
@RunWith(AndroidJUnit4::class)
class WatchRecordingNoticeTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun stop() = WatchRecordingNotice.onEvent(context, null)

    @Test
    fun theWatchsRecordingStandsInItsOwnQuietChannelWithItsElapsedTime() {
        val startedAt = Instant.parse("2026-10-28T04:57:29.123Z")

        WatchRecordingNotice.onEvent(context, WearRecording.State("01J9WATCH", startedAt))

        val notification = assertNotNull(standing(), "the notice is up").notification
        assertEquals("watch-recording", notification.channelId)
        assertEquals(
            context.getString(R.string.watch_recording_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER), "the platform ticks it")
        assertEquals(startedAt.toEpochMilliseconds(), notification.`when`, "from the watch's start")
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0, "ongoing")
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0, "never bridged to the watch that is recording")
        assertTrue(notification.actions.isNullOrEmpty(), "nothing on the phone to stop")
        assertNotNull(notification.contentIntent, "a tap opens the app")
        // The request, not the promotion: whether a Live Update is drawn is the system's call, and the first Android 16
        // builds (BE2A, the API 36 emulator) promote nothing at all.
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification), "asks to be a Live Update, like the recorder's")

        // Its own group, under a summary as quiet and as local as it is: Android 16 bundles ungrouped notices together.
        assertEquals("watch-recording", notification.group)
        val summary = assertNotNull(standing(id = 301), "the group's summary is up").notification
        assertEquals("watch-recording", summary.group)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(summary.flags and Notification.FLAG_LOCAL_ONLY != 0, "the summary is not bridged either")
        assertTrue(summary.flags and Notification.FLAG_ONGOING_EVENT != 0, "Clear all does not leave the notice without it")

        val channel = assertNotNull(manager.getNotificationChannel("watch-recording"))
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
    }

    @Test
    fun theWatchsStopTakesItDown() {
        WatchRecordingNotice.onEvent(context, WearRecording.State("01J9WATCH", Clock.System.now()))
        assertNotNull(standing())

        WatchRecordingNotice.onEvent(context, null)

        assertNull(standing(present = false), "the notice is down")
        assertNull(standing(id = 301, present = false), "and its summary")
    }

    @Test
    fun aWatchClockAheadOfThePhonesOpensAtNoTimeRatherThanANegativeOne() {
        WatchRecordingNotice.onEvent(context, WearRecording.State("01J9WATCH", Clock.System.now() + 1.minutes))

        val notification = assertNotNull(standing()).notification
        assertTrue(notification.`when` <= System.currentTimeMillis())
    }

    /** `notify` and `cancel` reach the shade through the service's own queue, a moment later. */
    private fun standing(id: Int = 300, present: Boolean = true): StatusBarNotification? {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (true) {
            val found = manager.activeNotifications.firstOrNull { it.id == id }
            if ((found != null) == present || SystemClock.uptimeMillis() > deadline) return found
            SystemClock.sleep(50)
        }
    }
}
