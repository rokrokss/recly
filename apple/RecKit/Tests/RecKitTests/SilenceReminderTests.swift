import XCTest
@testable import RecKit

/// docs/01 "Long recordings — watchdog · reminders · battery stop · waiting to resume": the reminder comes after ten
/// minutes without speech, whatever the microphone's gain, and never in a conversation. The Android
/// `SilenceReminderTest` holds the same cases.
final class SilenceReminderTests: XCTestCase {

    private let minute = 60 * SilenceReminder.windowsPerSecond
    private let tenMinutes = SilenceReminder.remindAfterWindows
    private let recent = SilenceReminder.recentWindows

    /// A quiet room's floor, about −50 dBFS.
    private static let quiet: Float = 0.003
    /// A steady room swings ±3 dB from window to window.
    private static let jitter: [Float] = [1, 0.8, 1.25, 0.9, 1.1, 0.75, 1.35, 0.85, 1, 1.2, 0.95, 0.71, 1.41]
    /// One second of talking: syllables, with a gap or two at the room's level (0).
    private static let syllables: [Float] = [0.25, 0.4, 0.1, 0, 0.3, 0.35, 0, 0.2, 0.45, 0]

    func testSteadySilenceRemindsOnceAtTenMinutes() {
        XCTAssertEqual(reminders((0 ..< 20 * minute).map { Self.room(Self.quiet, $0) }), [tenMinutes - 1])
    }

    func testAConversationIsNeverReminded() {
        XCTAssertEqual(reminders((0 ..< 30 * minute).map(Self.conversation)), [])
    }

    /// A meeting where somebody says a sentence every few minutes is a meeting, not a forgotten recording.
    func testAFewSentencesEveryFewMinutesKeepItFromReminding() {
        let windows = (0 ..< 30 * minute).map { $0 % (5 * minute) < 30 ? Self.speech($0) : Self.room(Self.quiet, $0) }

        XCTAssertEqual(reminders(windows), [])
    }

    /// The same room recorded ten times quieter or three times louder reminds at the same window.
    func testTheMicrophonesGainDoesNotMatterOnlyTheRoomsOwnFloor() {
        let windows = (0 ..< 9 * minute).map { Self.room(Self.quiet, $0) }
            + (0 ..< 50).map(Self.speech)
            + (0 ..< 15 * minute).map { Self.room(Self.quiet, $0) }
        let expected = reminders(windows)

        XCTAssertEqual(expected.count, 1)
        XCTAssertEqual(reminders(windows.map { $0 * 0.1 }), expected)
        XCTAssertEqual(reminders(windows.map { min(1, $0 * 3) }), expected)
    }

    func testANoisyRoomWithNobodyInItIsSilence() {
        XCTAssertEqual(reminders((0 ..< 12 * minute).map { Self.room(0.05, $0) }), [tenMinutes - 1])
    }

    /// A cough, a door, a chime: shorter than two seconds within ten, so the clock runs on.
    func testShortBlipsDoNotResetTheClock() {
        let windows: [Float] = (0 ..< 12 * minute).map { index in
            if index % 200 < 3 { return 0.5 }
            if (3_000 ..< 3_015).contains(index) { return 0.6 }
            return Self.room(Self.quiet, index)
        }

        XCTAssertEqual(reminders(windows), [tenMinutes - 1])
    }

    /// Speech sets the clock back to zero; the next ten quiet minutes remind again.
    func testSustainedSpeechResetsTheClockAndTheSilenceAfterItRemindsAgain() {
        let firstSpeech = 9 * minute
        let secondSpeech = 25 * minute
        let windows: [Float] = (0 ..< 40 * minute).map { index in
            if (firstSpeech ..< firstSpeech + 50).contains(index) || (secondSpeech ..< secondSpeech + 50).contains(index) {
                return Self.speech(index)
            }
            return Self.room(Self.quiet, index)
        }

        let reminded = reminders(windows)

        XCTAssertEqual(reminded.count, 2, "\(reminded)")
        assertWithinRecent(reminded.first, earliest: firstSpeech + 50 + tenMinutes - 1)
        assertWithinRecent(reminded.last, earliest: secondSpeech + 50 + tenMinutes - 1)
    }

    /// A microphone taken away delivers zeroes: nobody could be heard, so the clock waits.
    func testDigitalZeroNeitherCountsNorResets() {
        let windows = (0 ..< 5 * minute).map { Self.room(Self.quiet, $0) }
            + [Float](repeating: 0, count: 20 * minute)
            + (0 ..< 6 * minute).map { Self.room(Self.quiet, $0) }

        XCTAssertEqual(reminders(windows), [5 * minute + 20 * minute + 5 * minute - 1])
    }

    /// A fan that starts is a sound for its first minute, then it is the room.
    func testAFanThatKeepsRunningBecomesTheRoom() {
        let fanOn = 2 * minute
        let windows = (0 ..< fanOn).map { Self.room(Self.quiet, $0) } + (0 ..< 15 * minute).map { Self.room(0.03, $0) }

        let reminded = reminders(windows)

        XCTAssertEqual(reminded.count, 1, "\(reminded)")
        XCTAssertTrue((fanOn + tenMinutes ... fanOn + minute + recent + tenMinutes).contains(reminded.first ?? -1), "\(reminded)")
    }

    /// Behind a noise suppressor the floor is near zero; ten dB over it is still nobody speaking.
    func testNothingUnderAboutMinus55DBFSIsSpeech() {
        let windows: [Float] = (0 ..< 12 * minute).map { $0 % 10 < 5 ? 0.0008 : Self.room(0.00005, $0) }

        XCTAssertEqual(reminders(windows), [tenMinutes - 1])
    }

    private func reminders(_ windows: [Float]) -> [Int] {
        var reminder = SilenceReminder()
        return windows.indices.filter { reminder.add(windows[$0]) }
    }

    /// Within the ten seconds the speech takes to leave the recent windows.
    private func assertWithinRecent(_ actual: Int?, earliest: Int, line: UInt = #line) {
        XCTAssertTrue((earliest ... earliest + recent).contains(actual ?? -1), "expected \(earliest)…\(earliest + recent), got \(String(describing: actual))", line: line)
    }

    private static func room(_ level: Float, _ index: Int) -> Float {
        level * jitter[index % jitter.count]
    }

    private static func speech(_ index: Int) -> Float {
        let syllable = syllables[index % syllables.count]
        return syllable > 0 ? syllable : room(quiet, index)
    }

    /// Four seconds of talking, two of pause, over and over.
    private static func conversation(_ index: Int) -> Float {
        index % 60 < 40 ? speech(index) : room(quiet, index)
    }
}
