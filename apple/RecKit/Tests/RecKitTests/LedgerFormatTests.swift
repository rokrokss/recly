import Foundation
import XCTest
@testable import RecKit

/// docs/09 화면 원칙 2: the ledger's two-line time column is a fixed-width *pattern*, so its date
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
}
