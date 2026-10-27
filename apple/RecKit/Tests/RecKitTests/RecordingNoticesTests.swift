import Foundation
import UserNotifications
import ReclyCore
import XCTest
@testable import RecKit

/// The notices of a long recording: the rules as arithmetic, and what each of the recorder's events
/// hands Notification Center — against a fake one, which is the only kind a test bundle can hold.
/// The battery's own reading and the delivery itself need a device.
@MainActor
final class RecordingNoticesTests: XCTestCase {

    // MARK: - The rules

    func testTheDeadManIsTwoSegmentsAway() {
        XCTAssertEqual(RecordingNotices.deadManDelaySec(segmentSec: 900), 1_800)
        XCTAssertEqual(RecordingNotices.deadManDelaySec(segmentSec: 5), 10)
    }

    /// The rule itself is the core's `BatteryStop` (`BatteryStopTest`); here is only how Apple's
    /// readings are handed to it — as a whole percent, −1 for one the platform cannot read.
    func testTheBatteryReadingIsAWholePercent() {
        XCTAssertEqual(RecordingNotices.percent(level: 0.05), 5)
        XCTAssertEqual(RecordingNotices.percent(level: 0.06), 6)
        XCTAssertEqual(RecordingNotices.percent(level: 1), 100)
        XCTAssertEqual(RecordingNotices.percent(level: -1), -1, "a level the platform cannot read stops nothing")
        XCTAssertEqual(RecordingNotices.percent(current: 5, max: 100), 5)
        XCTAssertEqual(RecordingNotices.percent(current: 10, max: 200), 5, "a Mac's capacity is a share of its maximum")
        XCTAssertEqual(RecordingNotices.percent(current: 3, max: 0), -1)
        XCTAssertEqual(RecordingNotices.percent(current: -1, max: 100), -1)
    }

    func testTheRuleTheNoticesCallIsTheCores() {
        let rule = BatteryStop()
        XCTAssertFalse(rule.check(percent: 60, unplugged: true), "the start's reading never stops")
        XCTAssertTrue(rule.check(percent: RecordingNotices.percent(level: 0.05), unplugged: true))
        XCTAssertEqual(RecordingNotices.batteryCheckSec, 60)
    }

    // MARK: - What each event schedules

    func testAStartArmsOnlyTheDeadManAndABoundaryPushesItBack() async throws {
        let center = FakeAlertCenter()
        let notices = RecordingNotices(stopKey: "Stop", logger: OSLogLogger(), center: center)

        notices.apply(.started(recordingId: "r1", segmentSec: 900))
        await notices.settled()
        XCTAssertEqual(center.categories, [RecordingNotices.reminderCategory])
        XCTAssertEqual(center.posted, [RecordingNotices.deadManId], "the reminder is never scheduled ahead")
        XCTAssertEqual(try delay(of: center.requests[0]), 1_800, accuracy: 1)

        notices.apply(.partFiled)
        await notices.settled()
        XCTAssertEqual(center.posted, [RecordingNotices.deadManId, RecordingNotices.deadManId])

        notices.apply(.ended)
        await notices.settled()
        XCTAssertEqual(center.withdrawn.last, RecordingNotices.scheduledIds)
        XCTAssertTrue(RecordingNotices.scheduledIds.contains(RecordingNotices.reminderId), "a reminder still shown goes with the recording")
        XCTAssertFalse(RecordingNotices.scheduledIds.contains(RecordingNotices.batteryId))

        // A boundary the stop's own drain crossed lands after the end, and arms nothing.
        notices.apply(.partFiled)
        await notices.settled()
        XCTAssertEqual(center.posted.count, 2)
    }

    /// Ten minutes without speech: the reminder now, silent, with the Stop for this recording — and
    /// again in the same place after the next silence.
    func testNobodySpeakingPostsTheReminderAtOnce() async throws {
        let center = FakeAlertCenter()
        let notices = RecordingNotices(stopKey: "Stop", logger: OSLogLogger(), center: center)
        notices.apply(.started(recordingId: "r1", segmentSec: 900))

        notices.apply(.nobodySpeaking)
        await notices.settled()
        XCTAssertEqual(center.posted, [RecordingNotices.deadManId, RecordingNotices.reminderId])
        let reminder = center.requests[1]
        XCTAssertNil(reminder.trigger, "said now, not scheduled")
        XCTAssertEqual(reminder.content.categoryIdentifier, RecordingNotices.reminderCategory)
        XCTAssertNil(reminder.content.sound, "a chime in the middle of a recording is in the recording")
        XCTAssertEqual(reminder.content.title, RecKitStrings.localized("Still recording"))
        XCTAssertEqual(reminder.content.body, RecKitStrings.localized("No one has spoken for 10 minutes."))
        XCTAssertEqual(reminder.content.userInfo["recordingId"] as? String, "r1")

        notices.apply(.nobodySpeaking)
        await notices.settled()
        XCTAssertEqual(center.posted.last, RecordingNotices.reminderId)
        XCTAssertEqual(center.posted.count, 3)

        notices.apply(.ended)
        notices.apply(.nobodySpeaking)
        await notices.settled()
        XCTAssertEqual(center.posted.count, 3, "a silence heard as the recording ended says nothing")
    }

    /// Waiting takes down everything that promises a running recording and says it is paused; the
    /// resume takes the pause down and arms the dead-man again.
    func testWaitingTakesTheRunningNoticesDownAndAResumePutsTheDeadManBack() async throws {
        let center = FakeAlertCenter()
        let notices = RecordingNotices(stopKey: "Stop", logger: OSLogLogger(), center: center)
        notices.apply(.started(recordingId: "r1", segmentSec: 900))
        await notices.settled()
        XCTAssertEqual(center.posted, [RecordingNotices.deadManId])

        notices.apply(.awaitingResume)
        await notices.settled()
        XCTAssertEqual(center.withdrawn.last, [RecordingNotices.deadManId, RecordingNotices.reminderId])
        XCTAssertEqual(center.posted.last, RecordingNotices.pausedId)
        XCTAssertNil(center.requests.last?.trigger, "the pause is said now")

        // Nothing reaches a file while it waits: a stray boundary must not re-arm the dead-man, nor
        // a stray silence remind.
        let before = center.posted.count
        notices.apply(.partFiled)
        notices.apply(.nobodySpeaking)
        await notices.settled()
        XCTAssertEqual(center.posted.count, before)

        notices.apply(.resumed)
        await notices.settled()
        XCTAssertEqual(center.withdrawn.last, [RecordingNotices.pausedId])
        XCTAssertEqual(Array(center.posted.dropFirst(before)), [RecordingNotices.deadManId])
        XCTAssertEqual(try delay(of: center.requests[before]), 1_800, accuracy: 1)
    }

    private func delay(of request: UNNotificationRequest) throws -> TimeInterval {
        try XCTUnwrap(request.trigger as? UNTimeIntervalNotificationTrigger).timeInterval
    }
}
