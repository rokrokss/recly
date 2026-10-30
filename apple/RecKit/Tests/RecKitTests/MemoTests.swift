import ReclyCore
@testable import RecKit
import XCTest

/// docs/08 "Memo": what the memo's field takes and how its row reads it.
final class MemoTests: XCTestCase {
    /// The field takes what the core keeps: 2000 UTF-16 code units, as Kotlin counts — never half a character.
    func testTheFieldIsCutWhereTheCoreCutsIt() {
        XCTAssertEqual(Memos.companion.MAX, 2000)
        XCTAssertEqual(MemoText.capped("Agenda"), "Agenda")
        XCTAssertEqual(MemoText.capped(String(repeating: "z", count: 2500)).utf16.count, 2000)
        // 2001 units, the last two an emoji: the emoji goes whole.
        XCTAssertEqual(MemoText.capped(String(repeating: "x", count: 1999) + "😀"), String(repeating: "x", count: 1999))
        let korean = String(repeating: "가", count: 2000)
        XCTAssertEqual(MemoText.capped(korean + "나"), korean)
    }

    /// The row shows the first line that says something, trimmed; a blank memo shows nothing.
    func testTheRowReadsTheFirstLineThatSaysSomething() {
        XCTAssertEqual(MemoText.firstLine("  \n  Agenda: launch  \nMinsu"), "Agenda: launch")
        XCTAssertNil(MemoText.firstLine(""))
        XCTAssertNil(MemoText.firstLine(" \n\t\n"))
        XCTAssertTrue(MemoText.isBlank("  \n "))
        XCTAssertFalse(MemoText.isBlank("x"))
    }
}
