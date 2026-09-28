import XCTest
@testable import RecKit

/// Where [ActionFlowLayout] puts an expanded row's buttons. The Android shell's `ActionFlowTest` asks
/// the same questions.
final class ActionFlowLayoutTests: XCTestCase {

    /// Open in Drive, Retry and Details on a 375pt phone: two lines, Delete at the end of the second.
    func testTheTrailingButtonEndsTheLastLineWhenItFitsThere() {
        XCTAssertEqual(
            actionFlow([109, 88, 120, 63], width: 273, spacing: 8, trailingLast: true),
            [ActionSpot(x: 0, line: 0), ActionSpot(x: 117, line: 0), ActionSpot(x: 0, line: 1), ActionSpot(x: 210, line: 1)]
        )
    }

    func testATrailingButtonThatDoesNotFitTakesALineOfItsOwnAtTheEnd() {
        XCTAssertEqual(
            actionFlow([120, 120, 63], width: 258, spacing: 8, trailingLast: true),
            [ActionSpot(x: 0, line: 0), ActionSpot(x: 128, line: 0), ActionSpot(x: 195, line: 1)]
        )
    }

    /// A recording that cannot be deleted right now: its last button is not pushed to the end.
    func testWithoutATrailingButtonTheLastOneFlowsLikeTheRest() {
        XCTAssertEqual(
            actionFlow([99, 120], width: 258, spacing: 8, trailingLast: false),
            [ActionSpot(x: 0, line: 0), ActionSpot(x: 107, line: 0)]
        )
    }
}
