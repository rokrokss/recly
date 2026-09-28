import XCTest
@testable import RecKit

/// The widths [FillLayout] hands out, which are the whole of what it decides. The Android shell's
/// `FillRowTest` asks the same questions.
final class FillLayoutTests: XCTestCase {

    /// 시스템 기본 / 밝게 / 어둡게 in a phone's line: short enough for an even split, so they get one.
    func testChipsThatFitAnEvenSplitShareTheLineEqually() {
        XCTAssertEqual(fillLines([100, 44, 60], width: 358, spacing: 8), [[114, 114, 114]])
    }

    /// `System default` is too long for a third of the line; every chip keeps its own width and more.
    func testALabelTooLongForAnEvenSplitKeepsItsWidthAndTheRestIsShared() {
        XCTAssertEqual(fillLines([150, 44, 44], width: 368, spacing: 8), [[188, 82, 82]])
    }

    /// A line that cannot hold them all wraps, and each line fills on its own.
    func testALineThatDoesNotHoldThemAllWrapsAndEveryLineFills() {
        XCTAssertEqual(fillLines([140, 120, 90], width: 308, spacing: 8), [[150, 150], [308]])
    }

    /// A chip wider than the whole line gets the line, not more.
    func testAChipWiderThanTheLineIsHeldToIt() {
        XCTAssertEqual(fillLines([260], width: 200, spacing: 8), [[200]])
    }
}
