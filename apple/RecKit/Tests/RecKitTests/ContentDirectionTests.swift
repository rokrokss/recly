import SwiftUI
import XCTest
@testable import RecKit

/// 2026-10-10 (2.13): user and model text is laid out in its own direction — the first letter that has one decides.
final class ContentDirectionTests: XCTestCase {
    func testTheFirstLetterWithADirectionDecides() {
        XCTAssertEqual(ContentDirection.of("Ship the beta"), .leftToRight)
        XCTAssertEqual(ContentDirection.of("주간 회의"), .leftToRight)
        XCTAssertEqual(ContentDirection.of("نفدت المحاولات"), .rightToLeft)
        XCTAssertEqual(ContentDirection.of("שלום"), .rightToLeft)
        // Times, digits and marks have none of their own: what follows them decides.
        XCTAssertEqual(ContentDirection.of("[00:12:34] - مرحبا"), .rightToLeft)
        XCTAssertEqual(ContentDirection.of("12:34 · Budget"), .leftToRight)
    }

    func testTextWithNoDirectionFollowsTheApp() {
        XCTAssertNil(ContentDirection.of(""))
        XCTAssertNil(ContentDirection.of("[00:12:34] 1×"))
    }
}
