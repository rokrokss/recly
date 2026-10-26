import Foundation
import UserNotifications
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

    func testTheRemindersAreEveryHourFromTheSecondToTheTwelfth() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let plan = RecordingNotices.reminders(startedAt: start, now: start)

        XCTAssertEqual(plan.map(\.hour), Array(2 ... 12))
        XCTAssertEqual(plan.map(\.delaySec), (2 ... 12).map { Double($0) * 3_600 })
    }

    /// Re-armed after a resume, from the start rather than from now: the hours behind are skipped,
    /// and the next one is as far away as it really is.
    func testRemindersArmedAfterAResumeSkipTheHoursAlreadyPast() {
        let start = Date(timeIntervalSince1970: 1_000_000)
        let resumed = start.addingTimeInterval(3 * 3_600 + 20 * 60)
        let plan = RecordingNotices.reminders(startedAt: start, now: resumed)

        XCTAssertEqual(plan.map(\.hour), Array(4 ... 12))
        XCTAssertEqual(plan.first?.delaySec ?? 0, 40 * 60, accuracy: 0.001)
        // An hour that is exactly now is behind, not a reminder due in no time at all.
        XCTAssertEqual(RecordingNotices.reminders(startedAt: start, now: start.addingTimeInterval(3 * 3_600)).first?.hour, 4)
        XCTAssertEqual(RecordingNotices.reminders(startedAt: start, now: start.addingTimeInterval(12 * 3_600)).count, 0)
    }

    func testTheBatteryStopsARecordingThatFallsToFivePercentUnplugged() {
        func stops(_ level: Float, unplugged: Bool) -> Bool {
            RecordingNotices.batteryStops(level: level, unplugged: unplugged, startLevel: 0.6, startUnplugged: true)
        }
        XCTAssertTrue(stops(0.05, unplugged: true))
        XCTAssertTrue(stops(0.01, unplugged: true))
        XCTAssertFalse(stops(0.06, unplugged: true))
        XCTAssertFalse(stops(0.05, unplugged: false), "on the charger the level only goes up")
        XCTAssertFalse(stops(-1, unplugged: true), "a level the platform cannot read stops nothing")
    }

    /// A recording started this low is the person's choice: stopping it within the minute would leave
    /// next to nothing. Started on the charger, it is stopped once it is unplugged that low.
    func testARecordingThatStartedThatLowRunsOn() {
        XCTAssertFalse(RecordingNotices.batteryStops(level: 0.02, unplugged: true, startLevel: 0.05, startUnplugged: true))
        XCTAssertTrue(RecordingNotices.batteryStops(level: 0.02, unplugged: true, startLevel: 0.03, startUnplugged: false))
        XCTAssertTrue(RecordingNotices.batteryStops(level: 0.04, unplugged: true, startLevel: -1, startUnplugged: true))
    }

    // MARK: - What each event schedules

    func testAStartArmsTheDeadManAndEveryReminderAndABoundaryPushesTheDeadManBack() async throws {
        let center = FakeAlertCenter()
        let notices = RecordingNotices(stopKey: "Stop", logger: OSLogLogger(), center: center)

        notices.apply(.started(recordingId: "r1", at: Date(), segmentSec: 900))
        await notices.settled()
        XCTAssertEqual(center.categories, [RecordingNotices.reminderCategory])
        XCTAssertEqual(center.posted, [RecordingNotices.deadManId] + (2 ... 12).map(RecordingNotices.reminderId))
        XCTAssertEqual(try delay(of: center.requests[0]), 1_800, accuracy: 1)
        XCTAssertEqual(try delay(of: center.requests[1]), 2 * 3_600, accuracy: 1)
        let reminder = center.requests[1].content
        XCTAssertEqual(reminder.categoryIdentifier, RecordingNotices.reminderCategory)
        XCTAssertNil(reminder.sound, "a chime in the middle of a recording is in the recording")
        XCTAssertTrue(reminder.body.contains("2"), reminder.body)
        XCTAssertEqual(reminder.userInfo["recordingId"] as? String, "r1")

        notices.apply(.partFiled)
        await notices.settled()
        XCTAssertEqual(center.posted.last, RecordingNotices.deadManId)
        XCTAssertEqual(center.posted.count, 13, "the dead-man again, and nothing else")

        notices.apply(.ended)
        await notices.settled()
        XCTAssertEqual(center.withdrawn.last, RecordingNotices.scheduledIds)
        XCTAssertFalse(RecordingNotices.scheduledIds.contains(RecordingNotices.batteryId))

        // A boundary the stop's own drain crossed lands after the end, and arms nothing.
        notices.apply(.partFiled)
        await notices.settled()
        XCTAssertEqual(center.posted.count, 13)
    }

    /// Waiting takes down everything that promises a running recording and says it is paused; the
    /// resume takes the pause down and arms the dead-man and the reminders still ahead.
    func testWaitingTakesTheRunningNoticesDownAndAResumePutsTheRestBack() async throws {
        let center = FakeAlertCenter()
        let notices = RecordingNotices(stopKey: "Stop", logger: OSLogLogger(), center: center)
        notices.apply(.started(recordingId: "r1", at: Date().addingTimeInterval(-(3 * 3_600 + 30 * 60)), segmentSec: 900))
        await notices.settled()
        XCTAssertEqual(center.posted, [RecordingNotices.deadManId] + (4 ... 12).map(RecordingNotices.reminderId))

        notices.apply(.awaitingResume)
        await notices.settled()
        XCTAssertEqual(
            Set(try XCTUnwrap(center.withdrawn.last)),
            Set([RecordingNotices.deadManId] + (2 ... 12).map(RecordingNotices.reminderId))
        )
        XCTAssertEqual(center.posted.last, RecordingNotices.pausedId)
        XCTAssertNil(center.requests.last?.trigger, "the pause is said now")

        // Nothing reaches a file while it waits, and a stray boundary must not re-arm the dead-man.
        let before = center.posted.count
        notices.apply(.partFiled)
        await notices.settled()
        XCTAssertEqual(center.posted.count, before)

        notices.apply(.resumed)
        await notices.settled()
        XCTAssertEqual(center.withdrawn.last, [RecordingNotices.pausedId])
        XCTAssertEqual(
            Array(center.posted.dropFirst(before)),
            [RecordingNotices.deadManId] + (4 ... 12).map(RecordingNotices.reminderId)
        )
        XCTAssertEqual(try delay(of: center.requests[before]), 1_800, accuracy: 1)
        XCTAssertEqual(try delay(of: center.requests[before + 1]), 30 * 60, accuracy: 2)
    }

    private func delay(of request: UNNotificationRequest) throws -> TimeInterval {
        try XCTUnwrap(request.trigger as? UNTimeIntervalNotificationTrigger).timeInterval
    }
}
