package app.recly.android.work

import app.recly.android.ui.AlertReason
import app.recly.android.ui.JobAlert
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/10 rule 3 ("상태가 풀리면 알림을 내린다") against the one thing that can break it: two writers.
 * The queue collector and a locale change both paint the shade, and a refresh that re-posted what
 * *was* on screen could put back a notification the queue had just taken down — with nothing left
 * to take it down again, because a reason the queue no longer has will not emit about it twice.
 */
class JobAlertShadeTest {

    /** The shade is a process-wide object; each test starts from an empty one. */
    @BeforeTest
    fun reset() = JobAlertShade.forget()

    /** What the shade was asked to do, in order. */
    private class FakeShade : AlertShade {
        val ops = mutableListOf<String>()

        override fun notify(alert: JobAlert) {
            ops += "notify ${alert.reason} ${alert.count}"
        }

        override fun cancel(reason: AlertReason) {
            ops += "cancel $reason"
        }

        /** Only the notifications: every paint cancels the six reasons it has nothing to say about. */
        fun posted(): List<String> = ops.filter { it.startsWith("notify") }
    }

    @Test
    fun `a queue reading posts one notification per reason and cancels the rest`() {
        val shade = FakeShade()

        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.NEEDS_AUTH, 3)))

        assertEquals(listOf("notify NEEDS_AUTH 3"), shade.posted())
        assertEquals(AlertReason.entries.size - 1, shade.ops.count { it.startsWith("cancel") })
    }

    /** A sign-in, a retry, a deletion: the reason leaves the queue and the notification goes. */
    @Test
    fun `a reason that has left the queue is cancelled`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.NEEDS_SPACE, 1)))
        shade.ops.clear()

        JobAlertShade.publish(shade, emptyList())

        assertEquals(emptyList(), shade.posted())
        assertEquals(AlertReason.entries.size, shade.ops.count { it.startsWith("cancel") })
    }

    /**
     * docs/10 rule 3 is the *only* thing that takes an alert down, which is why the notification
     * has no `setAutoCancel`. Tapping it opens the fix screen; the job is still blocked, and
     * opening an activity emits nothing the queue could repost from — so the alert has to survive
     * the tap and wait for a reading that no longer has the reason in it.
     */
    @Test
    fun `a tap leaves the alert up and a later queue reading takes it down`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.WEBHOOK, 1, "morning")))
        shade.ops.clear()

        // The tap: MainActivity opens the editor and publishes nothing, so the last reading of the
        // queue — what the shade is showing — is untouched.
        JobAlertShade.repaint(shade)
        assertEquals(listOf("notify WEBHOOK 1"), shade.posted())

        shade.ops.clear()
        JobAlertShade.publish(shade, emptyList())

        assertEquals(emptyList(), shade.posted())
        assertEquals(AlertReason.entries.size, shade.ops.count { it.startsWith("cancel") })
    }

    /** The race this was written for: nothing the queue has taken down comes back on a refresh. */
    @Test
    fun `a refresh after a cancellation reposts nothing`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.WEBHOOK, 2)))
        JobAlertShade.publish(shade, emptyList())
        shade.ops.clear()

        JobAlertShade.repaint(shade)

        assertEquals(emptyList(), shade.posted())
    }

    /**
     * The runner passes every five minutes and the queue re-emits every time, so the same stuck job
     * is read over and over. `NotificationManager.notify` under an id that is already showing
     * *replaces* the notification and alerts again while it does — so an unchanged reading has to
     * post nothing at all, or one job stuck since this morning buzzes the user all day.
     */
    @Test
    fun `a reading that changed nothing posts nothing`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.QUOTA, 2, "morning")))
        shade.ops.clear()

        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.QUOTA, 2, "morning")))

        assertEquals(emptyList(), shade.posted())
        // The reasons it still has nothing to say about come down whatever happens.
        assertEquals(AlertReason.entries.size - 1, shade.ops.count { it.startsWith("cancel") })
    }

    /** A count that moved is news: the line now says something else, so it is posted again. */
    @Test
    fun `a changed count is posted again`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.QUOTA, 2)))
        shade.ops.clear()

        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.QUOTA, 3)))

        assertEquals(listOf("notify QUOTA 3"), shade.posted())
    }

    /**
     * A reason that left the queue is forgotten as well as cancelled, so its next appearance is a
     * fresh notification rather than a silent no-op against what used to be standing.
     */
    @Test
    fun `a reason that comes back after being cancelled is posted again`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.NEEDS_SPACE, 1)))
        JobAlertShade.publish(shade, emptyList())
        shade.ops.clear()

        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.NEEDS_SPACE, 1)))

        assertEquals(listOf("notify NEEDS_SPACE 1"), shade.posted())
    }

    /** docs/07 rule 3: what is still stuck *is* repainted, so it arrives in the new language. */
    @Test
    fun `a refresh repaints what the queue last said`() {
        val shade = FakeShade()
        JobAlertShade.publish(shade, listOf(JobAlert(AlertReason.QUOTA, 4, "morning")))
        shade.ops.clear()

        JobAlertShade.repaint(shade)

        assertEquals(listOf("notify QUOTA 4"), shade.posted())
    }
}
