import Foundation
import XCTest

/// docs/13 "Apple Watch" 진입점: what the complication draws for each state the app can leave behind,
/// and what its tap does (docs/lanes M5-L4 deliverable 6 "컴플리케이션 상태 매핑").
///
/// docs/07: the labels are catalog keys rather than sentences, so this asserts the mapping and the
/// catalog test asserts that every key has words in both languages.
///
/// `RecWatchShared` is compiled into this bundle for the same reason `RecPhoneShared` is: the
/// mapping is pure Foundation, and the alternative — a watchOS test target of its own — would run
/// this on a watch simulator for four assertions.
final class WatchComplicationTests: XCTestCase {
    /// Nothing running and nothing owed: the face offers a recording, and the tap starts one.
    func testTheIdleFaceOffersARecording() {
        let status = WatchStatus()

        XCTAssertEqual(status.symbol, "mic")
        XCTAssertEqual(status.label, "Record")
        XCTAssertTrue(status.startsOnTap)
    }

    /// While a recording runs the tap is the stop — the same switch the app's own button is, so a
    /// glance and a tap do what the wrist expects.
    func testARunningRecordingTurnsTheTapIntoTheStop() {
        let status = WatchStatus(state: .recording, startedAt: Date(), waiting: 0)

        XCTAssertEqual(status.symbol, "square.fill")
        XCTAssertEqual(status.label, "Recording")
        XCTAssertFalse(status.startsOnTap)
    }

    /// docs/03: a part is deleted only after `ack-meta ok` — until the phone acks, the audio is
    /// still on the
    /// watch, and that is worth saying on the face.
    func testRecordingsWaitingForThePhoneAreCounted() {
        let status = WatchStatus(state: .idle, startedAt: nil, waiting: 2)

        XCTAssertEqual(status.symbol, "arrow.up.square")
        // The count cannot ride in a key, so the face formats this branch itself.
        XCTAssertEqual(status.label, "Sending %lld")
        XCTAssertTrue(status.startsOnTap, "a queue is no reason not to start another recording")
    }

    /// A recording outranks the queue: it is the one the tap acts on.
    func testARecordingOutranksTheQueueOnTheFace() {
        let status = WatchStatus(state: .recording, startedAt: Date(), waiting: 3)

        XCTAssertEqual(status.symbol, "square.fill")
        XCTAssertEqual(status.label, "Recording")
    }

    /// docs/07 rule 2·3: the extension links no RecKit and cannot read the setting, so the language
    /// the app is following rides in the file and becomes the face's `\.locale`. Nothing written is
    /// the device's own, which is what `system` means (rule 1).
    func testTheFaceFollowsTheLanguageTheAppWroteIntoTheFile() {
        XCTAssertEqual(WatchStatus().appLocale, .current)
        XCTAssertEqual(WatchStatus(language: "ko").appLocale, Locale(identifier: "ko"))
    }

    /// The file is the only channel between the app and the extension, so what the app writes has to
    /// come back as what it wrote.
    func testTheStatusRoundTripsThroughItsFile() throws {
        let status = WatchStatus(
            state: .recording, startedAt: Date(timeIntervalSince1970: 1), waiting: 4, language: "ko"
        )

        let decoded = try JSONDecoder().decode(
            WatchStatus.self, from: try JSONEncoder().encode(status)
        )

        XCTAssertEqual(decoded, status)
    }

    /// The other direction of the same channel: the `status.json` on disk after an update is the
    /// one the previous build wrote, with no `language` in it. It has to read back with everything
    /// it did carry intact — a throw here would blank the face until the app next ran.
    func testAFileWrittenBeforeThereWasALanguageStillDecodes() throws {
        let stored = Data(#"{"state":"recording","startedAt":1,"waiting":2}"#.utf8)

        let status = try JSONDecoder().decode(WatchStatus.self, from: stored)

        XCTAssertEqual(status.state, .recording)
        XCTAssertEqual(status.startedAt, Date(timeIntervalSinceReferenceDate: 1))
        XCTAssertEqual(status.waiting, 2)
        XCTAssertEqual(status.language, "", "no language written means the device's own")
        XCTAssertEqual(status.appLocale, .current)
    }

    /// The idle file has no `startedAt` either — the encoder leaves an empty optional out — so the
    /// same decoder has to accept its own writing.
    func testAnIdleFileWithNoStartTimeDecodes() throws {
        let stored = try JSONEncoder().encode(WatchStatus())

        XCTAssertEqual(try JSONDecoder().decode(WatchStatus.self, from: stored), WatchStatus())
    }
}
