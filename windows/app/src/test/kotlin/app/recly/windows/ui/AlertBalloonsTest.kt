package app.recly.windows.ui

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/10 rule 1 and the Windows-specific half of rule 3: a balloon goes up when a reason *arrives*
 * or its count *changes*, and never again on the runner passes in between.
 *
 * It matters more here than on the phone: `TrayIcon.displayMessage` cannot replace or withdraw a
 * toast the way `NotificationManager.notify` replaces a notification, so a pass that re-posted
 * everything it still saw would interrupt the user every five minutes about a job that has been
 * stuck since this morning.
 */
class AlertBalloonsTest {

    private val shown = mutableListOf<JobAlert>()
    private val balloon = AlertBalloon { shown += it }

    @BeforeTest
    fun clearStandingBalloons() {
        JobAlertBalloons.forget()
        shown.clear()
    }

    @Test
    fun `a reason that has just arrived is posted once`() {
        val alerts = listOf(JobAlert(AlertReason.NEEDS_SPACE, 1))

        JobAlertBalloons.publish(balloon, alerts)
        JobAlertBalloons.publish(balloon, alerts)
        JobAlertBalloons.publish(balloon, alerts)

        assertEquals(listOf(JobAlert(AlertReason.NEEDS_SPACE, 1)), shown)
    }

    /** The count is the news: two more recordings blocked on the same thing is worth saying. */
    @Test
    fun `a count that changed is posted again`() {
        JobAlertBalloons.publish(balloon, listOf(JobAlert(AlertReason.NEEDS_SPACE, 1)))
        JobAlertBalloons.publish(balloon, listOf(JobAlert(AlertReason.NEEDS_SPACE, 3)))

        assertEquals(
            listOf(JobAlert(AlertReason.NEEDS_SPACE, 1), JobAlert(AlertReason.NEEDS_SPACE, 3)),
            shown,
        )
    }

    /**
     * docs/10 rule 3: a reason that has left the queue is no longer standing, so it can announce
     * itself again if it comes back. (Taking the *visible* balloon down is not on offer — Windows
     * fades it — which is why the banner and the tray icon are drawn from the queue instead.)
     */
    @Test
    fun `a reason that came back after being fixed is posted again`() {
        JobAlertBalloons.publish(balloon, listOf(JobAlert(AlertReason.WEBHOOK, 1)))
        JobAlertBalloons.publish(balloon, emptyList())
        JobAlertBalloons.publish(balloon, listOf(JobAlert(AlertReason.WEBHOOK, 1)))

        assertEquals(2, shown.size)
    }

    @Test
    fun `two reasons are two balloons, and one of them changing does not repost the other`() {
        JobAlertBalloons.publish(
            balloon,
            listOf(JobAlert(AlertReason.NEEDS_AUTH, 1), JobAlert(AlertReason.QUOTA, 1)),
        )
        JobAlertBalloons.publish(
            balloon,
            listOf(JobAlert(AlertReason.NEEDS_AUTH, 1), JobAlert(AlertReason.QUOTA, 2)),
        )

        assertEquals(
            listOf(
                JobAlert(AlertReason.NEEDS_AUTH, 1),
                JobAlert(AlertReason.QUOTA, 1),
                JobAlert(AlertReason.QUOTA, 2),
            ),
            shown,
        )
    }

    /** A queue with nothing wrong in it interrupts nobody. */
    @Test
    fun `an empty reading posts nothing`() {
        JobAlertBalloons.publish(balloon, emptyList())

        assertEquals(emptyList(), shown)
    }
}
