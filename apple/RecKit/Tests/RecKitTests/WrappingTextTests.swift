import SwiftUI
import XCTest
@testable import RecKit

/// 2026-10-11: words in badges, nodes and banner lines — a word breaks only at a hyphen (N2), and words in
/// Hangul, Kana or Han are not set in SF Mono, which has none of their glyphs (N3).
final class WrappingTextTests: XCTestCase {
    /// N2: the language's own hyphenation points, as soft hyphens a line can break at.
    func testGermanWordsGetTheirHyphenationPoints() {
        let hyphenated = Hyphenation.softHyphens("Fehlgeschlagen", locale: Locale(identifier: "de"))

        XCTAssertEqual(hyphenated.replacingOccurrences(of: "\u{00AD}", with: "|"), "Fehl|ge|schla|gen")
        XCTAssertEqual(hyphenated.replacingOccurrences(of: "\u{00AD}", with: ""), "Fehlgeschlagen", "only invisible hyphens are added")
    }

    /// N2: a language with no hyphenation data is drawn as it is — Korean breaks between words.
    func testALanguageWithoutHyphenationIsLeftAsItIs() {
        XCTAssertEqual(Hyphenation.softHyphens("업로드 중", locale: Locale(identifier: "ko")), "업로드 중")
    }

    /// N3: a word in Hangul, Kana or Han takes the sans at the size asked for; Latin words, times and
    /// counts keep the monospace.
    func testWordsInHangulKanaAndHanTakeTheSans() {
        let fonts = BlueprintFonts()

        XCTAssertEqual(fonts.mono(TypeSize.small, words: "업로드 중"), fonts.sans(TypeSize.small))
        XCTAssertEqual(fonts.mono(TypeSize.small, words: "Drive 연결 대기"), fonts.sans(TypeSize.small))
        XCTAssertEqual(fonts.mono(TypeSize.small, words: "アップロード中"), fonts.sans(TypeSize.small))
        XCTAssertEqual(fonts.mono(TypeSize.small, words: "上传中"), fonts.sans(TypeSize.small))
        XCTAssertEqual(fonts.mono(TypeSize.small, words: "Uploading"), fonts.mono(TypeSize.small))
        XCTAssertEqual(fonts.mono(TypeSize.small, words: "Wird hochgeladen"), fonts.mono(TypeSize.small))
        XCTAssertEqual(fonts.mono(TypeSize.small, words: "00:12"), fonts.mono(TypeSize.small))
    }
}
