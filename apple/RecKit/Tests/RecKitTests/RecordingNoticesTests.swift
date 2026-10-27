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
