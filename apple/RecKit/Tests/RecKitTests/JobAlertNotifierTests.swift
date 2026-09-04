import Foundation
import UserNotifications
import XCTest
@testable import RecKit

/// docs/10 rules 1 and 3 on the Apple side, as the two pieces that can be checked without a
/// Notification Center to check them against: what a new reading of the queue actually changes, and
/// what happens to a tap that lands before there is a screen to take it to.
final class StandingAlertsTests: XCTestCase {

    /// The regression: `publish` handed every alert to `UNUserNotificationCenter.add` on every
    /// runner pass, and an `add` under an identifier that is already standing replaces it — and
    /// alerts again while it does. One stuck job buzzed the user every five minutes.
    func testTheSameReadingTwiceIsOneNotification() {
        var standing = StandingAlerts()
        let queue = [JobAlert(reason: .needsSpace, count: 1, workflowId: "w1")]

        let first = standing.apply(queue)
        XCTAssertEqual(first.post, queue)
        standing.record(queue[0])

        XCTAssertEqual(standing.apply(queue).post, [], "the same queue was posted again")
    }

    /// A count that moved is news: the line the notification carries now says something else.
    func testAChangedCountIsPostedAgainAndOnlyOnce() {
        var standing = StandingAlerts()
        standing.record(JobAlert(reason: .needsSpace, count: 1))
        let grown = [JobAlert(reason: .needsSpace, count: 2)]

        XCTAssertEqual(standing.apply(grown).post, grown)
        standing.record(grown[0])
        XCTAssertEqual(standing.apply(grown).post, [])
    }

    /// So is anything else the line is made of — the workflow a tap would open is on the
    /// notification, and a fold that now points at another one has to replace it.
    func testAChangedFixIsPostedAgain() {
        var standing = StandingAlerts()
        standing.record(JobAlert(reason: .missingSecret, count: 1, workflowId: "w1", secret: "a", stepId: "s1"))

        let moved = [JobAlert(reason: .missingSecret, count: 1, workflowId: "w1", secret: "b", stepId: "s2")]

        XCTAssertEqual(standing.apply(moved).post, moved)
    }

    /// docs/10 rule 3: "그 이유가 큐에서 사라지면 알림도 내려간다."
    func testAReasonThatHasLeftTheQueueIsWithdrawn() {
        var standing = StandingAlerts()
        standing.record(JobAlert(reason: .needsSpace, count: 1))

        let update = standing.apply([])

        XCTAssertEqual(update.post, [])
        XCTAssertTrue(update.withdraw.contains(.needsSpace))
        // Every reason the reading does not name, so a notification left standing by the last
        // launch comes down too.
        XCTAssertEqual(update.withdraw.count, AlertReason.allCases.count)
    }

    /// Withdrawing is also forgetting: a reason that comes back is a fresh notification, not a
    /// silent replace of one that is no longer on the Lock Screen.
    func testAReasonThatComesBackIsPostedAgain() {
        var standing = StandingAlerts()
        let alert = JobAlert(reason: .quota, count: 1, workflowId: "w1")
        standing.record(alert)
        _ = standing.apply([])

        XCTAssertEqual(standing.apply([alert]).post, [alert])
    }

    /// A reading that changes one of two reasons posts one of them and withdraws neither.
    func testOnlyWhatChangedIsPosted() {
        var standing = StandingAlerts()
        let auth = JobAlert(reason: .needsAuth, count: 1)
        let quota = JobAlert(reason: .quota, count: 1, workflowId: "w1")
        standing.record(auth)
        standing.record(quota)

        let update = standing.apply([auth, JobAlert(reason: .quota, count: 3, workflowId: "w1")])

        XCTAssertEqual(update.post, [JobAlert(reason: .quota, count: 3, workflowId: "w1")])
        XCTAssertFalse(update.withdraw.contains(.needsAuth))
        XCTAssertFalse(update.withdraw.contains(.quota))
    }

    /// Nothing was posted because the user refused the permission, so nothing was recorded — and
    /// the reading after they granted it is still a first post rather than a repeat.
    func testAnAlertThatNeverReachedNotificationCenterIsStillDue() {
        var standing = StandingAlerts()
        let queue = [JobAlert(reason: .needsAuth, count: 1)]

        XCTAssertEqual(standing.apply(queue).post, queue)
        XCTAssertEqual(standing.apply(queue).post, queue, "an unposted alert was treated as standing")
    }
}

/// docs/10: the tap that arrives before the model that answers it.
@MainActor
final class AlertRouterTests: XCTestCase {

    /// The regression: the notification delegate was built lazily, from the first reading of the
    /// queue — which is after the core has opened. A job alert opened from a cold launch is
    /// delivered long before that, and there was nothing there to take it.
    func testATapBeforeTheModelIsReadyIsKeptAndThenRouted() {
        let router = AlertRouter<JobAlert>()
        let alert = JobAlert(reason: .missingSecret, count: 1, workflowId: "w1", secret: "k", stepId: "s1")

        router.deliver(alert)
        XCTAssertTrue(router.isWaiting)

        var routed: [JobAlert] = []
        router.connect { routed.append($0) }

        XCTAssertEqual(routed, [alert])
        XCTAssertFalse(router.isWaiting)
    }

    func testATapAfterTheModelIsReadyGoesStraightThrough() {
        let router = AlertRouter<JobAlert>()
        var routed: [JobAlert] = []
        router.connect { routed.append($0) }

        router.deliver(JobAlert(reason: .quota, count: 2, workflowId: "w2"))

        XCTAssertEqual(routed, [JobAlert(reason: .quota, count: 2, workflowId: "w2")])
        XCTAssertFalse(router.isWaiting)
    }

    /// Two taps before the app is up are one intent; the second is the one the user made last.
    func testOnlyTheLastTapIsWaiting() {
        let router = AlertRouter<JobAlert>()
        router.deliver(JobAlert(reason: .needsAuth, count: 1))
        router.deliver(JobAlert(reason: .needsSpace, count: 1))

        var routed: [JobAlert] = []
        router.connect { routed.append($0) }

        XCTAssertEqual(routed, [JobAlert(reason: .needsSpace, count: 1)])
    }

    /// Nothing arrived, so connecting routes nothing — the fix screen is not opened at every launch.
    func testAModelThatWasReadyFirstRoutesNothing() {
        let router = AlertRouter<JobAlert>()
        var routed: [JobAlert] = []

        router.connect { routed.append($0) }

        XCTAssertEqual(routed, [])
    }

    /// docs/12 "미팅 감지": the same buffer, for the Mac's other notification. The regression:
    /// `MenuModel` wired `MeetingNotifier.onAction` in `load()`, so "Start recording" taken on an
    /// offer that woke the app reached a nil closure and was dropped — exactly the cold launch the
    /// offer is most likely to be opened from. It is wired in `init` now and waits here for the
    /// recorder `load()` makes.
    func testAMeetingOfferTakenBeforeTheRecorderExistsIsKeptAndThenServed() {
        enum Offer: Equatable { case start, stop }
        let router = AlertRouter<Offer>()

        router.deliver(.start)
        XCTAssertTrue(router.isWaiting)

        var taken: [Offer] = []
        router.connect { taken.append($0) }

        XCTAssertEqual(taken, [.start])
        XCTAssertFalse(router.isWaiting)
    }
}

/// Notification Center as [JobAlertNotifier] uses it, with a refusal on demand — the one thing the
/// real one cannot be asked for.
@MainActor
final class FakeAlertCenter: AlertCenter {
    struct Refused: Error {}

    var granted = true
    /// How many of the next posts to refuse.
    var failures = 0
    private(set) var posted: [String] = []
    private(set) var withdrawn: [[String]] = []

    func authorize() async -> Bool { granted }

    func post(_ request: UNNotificationRequest) async throws {
        guard failures == 0 else {
            failures -= 1
            throw Refused()
        }
        posted.append(request.identifier)
    }

    func withdraw(_ identifiers: [String]) { withdrawn.append(identifiers) }
}

/// docs/10 rule 1 depends on [StandingAlerts] holding what Notification Center *took*, not what it
/// was offered — which is the half no arithmetic over the queue can check.
@MainActor
final class JobAlertPostingTests: XCTestCase {

    /// The regression: `add` was fired off and the alert recorded as standing whatever it answered.
    /// A post Notification Center refused — the notification budget, a content it would not take —
    /// left nothing on the Lock Screen and yet made every later reading of the same queue "no
    /// change", so the user was never told at all.
    func testAnAlertNotificationCenterRefusedIsPostedAgainOnTheNextReading() async {
        let center = FakeAlertCenter()
        center.failures = 1
        let notifier = JobAlertNotifier(subsystem: "app.recly.tests", center: center)
        let queue = [JobAlert(reason: .needsSpace, count: 1, workflowId: "w1")]

        await notifier.publish(queue)
        XCTAssertEqual(center.posted, [], "the refused post was counted as delivered")

        await notifier.publish(queue)
        XCTAssertEqual(
            center.posted, [JobAlertNotifier.category(of: .needsSpace)],
            "the refused alert was recorded as standing and never tried again"
        )
    }

    /// And the rule it must not break: one that did land is not posted again for the same reading.
    func testAnAlertThatLandedIsNotPostedAgain() async {
        let center = FakeAlertCenter()
        let notifier = JobAlertNotifier(subsystem: "app.recly.tests", center: center)
        let queue = [JobAlert(reason: .quota, count: 1, workflowId: "w1")]

        await notifier.publish(queue)
        await notifier.publish(queue)

        XCTAssertEqual(center.posted, [JobAlertNotifier.category(of: .quota)])
    }

    /// docs/10 rule 3: withdrawing needs no permission and happens on every reading — including the
    /// one where nothing could be posted.
    func testAReasonThatHasLeftTheQueueIsWithdrawnEvenWhenPostingFails() async {
        let center = FakeAlertCenter()
        center.granted = false
        let notifier = JobAlertNotifier(subsystem: "app.recly.tests", center: center)

        await notifier.publish([])

        XCTAssertEqual(center.posted, [])
        XCTAssertEqual(center.withdrawn.count, 1)
        XCTAssertEqual(center.withdrawn[0].count, AlertReason.allCases.count)
    }
}
