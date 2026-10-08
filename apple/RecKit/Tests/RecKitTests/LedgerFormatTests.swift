import Foundation
import XCTest
@testable import RecKit

/// docs/09 screen principle 2: the ledger's two-line time column is a fixed-width *pattern*, so its date
/// half is `MM-dd` in every language — Korean used to hand the order to the locale and came out
/// day-first (`02/09`), which made the same two numbers mean two different things depending on the
/// language picker. The desktop shell has always written `MM-dd`.
final class LedgerFormatTests: XCTestCase {

    /// Not midnight in any plausible zone: the column is drawn in the device's own time, and a
    /// stamp near the boundary would make this test about the machine it runs on.
    private static let iso = "2026-02-09T12:04:05.000Z"

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheDateColumnIsMonthFirstAndTheSameInBothLanguages() throws {
        let parsed = ISO8601DateFormatter()
        parsed.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = try XCTUnwrap(parsed.date(from: Self.iso))
        let parts = Calendar.autoupdatingCurrent.dateComponents([.month, .day], from: date)
        let expected = String(
            format: "%02d-%02d", try XCTUnwrap(parts.month), try XCTUnwrap(parts.day)
        )

        AppLanguage.current = .en
        let english = LedgerFormat.date(Self.iso)
        AppLanguage.current = .ko
        let korean = LedgerFormat.date(Self.iso)

        XCTAssertEqual(english, expected)
        XCTAssertEqual(korean, expected, "the Korean ledger still writes the day first")
    }

    /// The time half is `HH:mm` in every language too. English is a twelve-hour locale, and with the
    /// day period left off it wrote a recording started at 15:05 as `03:05` — the same text as one
    /// started at 03:05.
    func testTheTimeColumnIsTwentyFourHourInEveryLanguage() throws {
        let components = DateComponents(year: 2026, month: 2, day: 9, hour: 15, minute: 5)
        let date = try XCTUnwrap(Calendar.autoupdatingCurrent.date(from: components))
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let iso = formatter.string(from: date)

        for language in AppLanguage.Choice.choices {
            AppLanguage.current = language
            XCTAssertEqual(LedgerFormat.time(iso), "15:05", language.rawValue)
        }
    }

    /// docs/07 rule 7 is untouched for the date in *words*: the sentence a screen reader says about
    /// a row is still the locale's own.
    func testTheSpokenDateIsStillTheLocalesOwn() {
        AppLanguage.current = .en
        let english = LedgerFormat.startedAt(Self.iso)
        AppLanguage.current = .ko
        let korean = LedgerFormat.startedAt(Self.iso)

        XCTAssertNotEqual(korean, english, "the spoken date is no longer locale-formatted")
    }

    /// A stamp this build cannot read is a blank column rather than a crash or the raw text.
    func testAnUnreadableStampIsBlank() {
        XCTAssertEqual(LedgerFormat.date("not a date"), "")
    }

    /// docs/09 "Typography": the timer, the playback clock and a transcript stamp are `00:12:34` from
    /// the first second — Android's `hms` — and the hours run past 24 rather than wrapping.
    func testTheClockAlwaysShowsTheHours() {
        XCTAssertEqual(LedgerFormat.clock(0), "00:00:00")
        XCTAssertEqual(LedgerFormat.clock(754), "00:12:34")
        XCTAssertEqual(LedgerFormat.clock(3723), "01:02:03")
        XCTAssertEqual(LedgerFormat.clock(25 * 3600), "25:00:00")
        XCTAssertEqual(LedgerFormat.clock(-5), "00:00:00")
    }

    /// 2026-10-08 §3: the ledger's length column is `MM:SS` under the hour and `HH:MM:SS` from it, and
    /// the placeholder for a length that is not in yet.
    func testTheLengthColumnIsMinutesUnderTheHourAndHoursFromIt() {
        XCTAssertEqual(LedgerFormat.length(250.9), "04:10")
        XCTAssertEqual(LedgerFormat.length(3753), "01:02:33")
        XCTAssertEqual(LedgerFormat.length(nil), LedgerFormat.noLength)
    }

    /// 2026-10-08 §3: a live timer follows the time itself — `00:12` … `59:59`, then `01:00:00`.
    func testALiveTimerGrowsItsHoursAtTheHour() {
        XCTAssertEqual(LedgerFormat.elapsed(0), "00:00")
        XCTAssertEqual(LedgerFormat.elapsed(12), "00:12")
        XCTAssertEqual(LedgerFormat.elapsed(3599), "59:59")
        XCTAssertEqual(LedgerFormat.elapsed(3600), "01:00:00")
        XCTAssertEqual(LedgerFormat.elapsed(25 * 3600 + 3), "25:00:03")
        XCTAssertEqual(LedgerFormat.elapsed(-5), "00:00")
    }

    /// 2026-10-08 §3: inside one recording every time takes the shape the recording's length takes,
    /// so they are one width; with no length known, the time shapes itself.
    func testTimesInsideARecordingAreShapedByItsLength() {
        XCTAssertEqual(LedgerFormat.stamp(75, total: 600), "01:15")
        XCTAssertEqual(LedgerFormat.stamp(75, total: 3700), "00:01:15")
        XCTAssertEqual(LedgerFormat.stamp(3650, total: 3700), "01:00:50")
        XCTAssertEqual(LedgerFormat.stamp(75, total: nil), "01:15")
        XCTAssertEqual(LedgerFormat.stamp(75, total: 0), "01:15")
    }
}
