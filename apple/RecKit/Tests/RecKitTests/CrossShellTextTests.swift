import Foundation
import XCTest
@testable import RecKit

/// The wording two shells are supposed to share, checked against the other shell's own resources
/// rather than against a copy written out here — the shape Windows' `ConsentTest` and Android's
/// `ConsentTextTest` already use. A rewording on either side fails here, which is the only place it
/// could be noticed.
enum ShellCatalogs {
    /// The repo root, from this file rather than from a working directory nobody controls.
    static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // RecKitTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // RecKit
        .deletingLastPathComponent() // apple
        .deletingLastPathComponent() // rec

    /// One `.xcstrings` as `key -> language -> value`.
    static func catalog(_ path: String) throws -> [String: [String: String]] {
        let url = repoRoot.appendingPathComponent(path)
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        let strings = (json as? [String: Any])?["strings"] as? [String: Any] ?? [:]
        return strings.compactMapValues { entry in
            let localizations = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            return localizations.compactMapValues {
                (($0 as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String
            }
        }
    }

    /// One Android `strings.xml` as `name -> value`, with Android's own escaping undone: an
    /// apostrophe has to be backslashed there and a line break is written `\n`. `aapt` undoes both
    /// at build time; this has to undo them itself before the text can be compared with anybody's.
    static func androidStrings(_ locale: String) throws -> [String: String] {
        let url = repoRoot.appendingPathComponent("android/app/src/main/res/\(locale)/strings.xml")
        let xml = try String(contentsOf: url, encoding: .utf8)
        var values: [String: String] = [:]
        let pattern = #"<string name="([^"]+)">([\s\S]*?)</string>"#
        let regex = try NSRegularExpression(pattern: pattern)
        for match in regex.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)) {
            guard let name = Range(match.range(at: 1), in: xml),
                  let value = Range(match.range(at: 2), in: xml)
            else { continue }
            values[String(xml[name])] = String(xml[value])
                .replacingOccurrences(of: "\\n", with: "\n")
                .replacingOccurrences(of: "\\'", with: "'")
                .replacingOccurrences(of: "&amp;", with: "&")
                .replacingOccurrences(of: "&lt;", with: "<")
                .replacingOccurrences(of: "&gt;", with: ">")
        }
        return values
    }
}

/// Lane P1 deliverable 6 · docs/15 §3: the editor says what leaves the device when a `transcribe`
/// step runs, and whose policy decides what becomes of it afterwards. Three shells show it and
/// docs/15 asks for the same content in all three, so the Apple copy is checked against the Android
/// resources it was taken from — in both languages.
final class ProviderDisclosureTests: XCTestCase {

    private static let pairs = [
        ("provider.disclosure.transcribe", "provider_disclosure_transcribe"),
    ]

    func testTheDisclosureIsWordForWordTheAndroidOneInBothLanguages() throws {
        let catalog = try ShellCatalogs.catalog("apple/RecKit/Sources/RecKit/Resources/Localizable.xcstrings")
        for (locale, language) in [("values", "en"), ("values-ko", "ko")] {
            let android = try ShellCatalogs.androidStrings(locale)
            for (key, name) in Self.pairs {
                let ours = try XCTUnwrap(catalog[key]?[language], "RecKit has no \(key) in \(language)")
                let theirs = try XCTUnwrap(android[name], "android has no \(name) in \(locale)")
                XCTAssertEqual(ours, theirs, "\(key) is not the Android wording in \(language)")
            }
        }
    }

    /// docs/15 §3 "작성 규칙": no "kept for N days". Recly does not know the number and would be
    /// making a promise on somebody else's behalf.
    func testTheDisclosureMakesNoRetentionClaim() throws {
        let catalog = try ShellCatalogs.catalog("apple/RecKit/Sources/RecKit/Resources/Localizable.xcstrings")
        let claim = try NSRegularExpression(pattern: #"\d+\s*(day|days|hour|hours|일|시간)"#)
        for (key, _) in Self.pairs {
            for (language, value) in try XCTUnwrap(catalog[key]) {
                let range = NSRange(value.startIndex..., in: value)
                XCTAssertNil(
                    claim.firstMatch(in: value, range: range),
                    "\(key) in \(language) promises a retention period: \(value)"
                )
            }
        }
    }

    /// docs/15 §3: the provider policy URLs are not confirmed, so the disclosure is sentences and
    /// no links — an app that invented one would be pointing at a page nobody wrote.
    func testTheDisclosureCarriesNoLink() throws {
        let catalog = try ShellCatalogs.catalog("apple/RecKit/Sources/RecKit/Resources/Localizable.xcstrings")
        for (key, _) in Self.pairs {
            for (language, value) in try XCTUnwrap(catalog[key]) {
                XCTAssertFalse(value.contains("http"), "\(key) in \(language) carries a link: \(value)")
            }
        }
    }
}

/// Lane P1 deliverable 7 · docs/12 M8: the iPhone's recording-consent reminder is the Mac's — "같은
/// 질문 · 같은 본문 · 같은 관할 링크 · 같은 다시 묻지 않기" — in both languages. A user with a Mac and
/// a phone is being told about the same law by the same product.
///
/// The Mac's is the original: Android's `ConsentTextTest` and Windows' `ConsentTest` both read the
/// Mac's own catalog, and this reads it for the phone.
final class ConsentTextTests: XCTestCase {

    private static let shared = [
        "Did you tell the participants about the recording?",
        "consent.body",
        "I told them · Start recording",
        "Do not ask again",
        "Recording-consent rules by jurisdiction",
    ]

    func testTheQuestionTheBodyAndTheButtonsAreWordForWordTheMacsInBothLanguages() throws {
        let mac = try ShellCatalogs.catalog("apple/RecMac/RecMac/Localizable.xcstrings")
        let phone = try ShellCatalogs.catalog("apple/RecPhone/RecPhone/Localizable.xcstrings")
        for key in Self.shared {
            for language in ["en", "ko"] {
                let theirs = try XCTUnwrap(mac[key]?[language], "the Mac has no \(key) in \(language)")
                let ours = try XCTUnwrap(phone[key]?[language], "the phone has no \(key) in \(language)")
                XCTAssertEqual(ours, theirs, "\(key) is not the Mac's wording in \(language)")
            }
        }
    }

    /// The link is a link and not a third button, on both, for the same reason: the question is
    /// open. And it is the same page — the phone must not point somewhere the Mac does not.
    func testTheGuidanceLinkIsTheOneTheMacPointsAt() throws {
        let url = try Self.wikipediaLink(in: "apple/RecMac/RecMac/MenuModel.swift")

        XCTAssertEqual(try Self.wikipediaLink(in: "apple/RecPhone/RecPhone/RecordingView.swift"), url)
    }

    private static func wikipediaLink(in path: String) throws -> String {
        let source = try String(
            contentsOf: ShellCatalogs.repoRoot.appendingPathComponent(path),
            encoding: .utf8
        )
        let regex = try NSRegularExpression(pattern: #""(https://en\.wikipedia\.org/[^"]+)""#)
        let range = NSRange(source.startIndex..., in: source)
        let match = try XCTUnwrap(
            regex.firstMatch(in: source, range: range),
            "\(path) no longer links to a jurisdiction summary"
        )
        return String(source[try XCTUnwrap(Range(match.range(at: 1), in: source))])
    }
}

/// docs/09 화면 원칙 2 (2026-09-04) · the cross-shell dictionary: the three states a ledger row shows
/// for work that is in flight on *another* device. Every shell grew them at once and every shell has
/// to say the same thing, so the wording is written out here rather than read off a neighbour —
/// Android's `CrossShellDictionaryTest` is what compares the four shells, and it reads this catalog.
/// What this holds is the Apple half: the keys exist, in both languages, saying exactly this.
///
/// The English *is* the key ([RecKitStrings.localized]), so a key renamed on this side and not in
/// the dictionary shows up here as a missing key rather than as an English row on a Korean device.
final class LedgerStateTextTests: XCTestCase {

    /// code → the sentence the row says about it, in both languages.
    private static let states: [(code: String, en: String, ko: String)] = [
        ("RECEIVING", "Receiving from the watch", "워치에서 받는 중"),
        ("UPLOADING", "Uploading on another device", "다른 기기에서 업로드 중"),
        ("TRANSCRIBING", "Transcribing on another device", "다른 기기에서 전사 중"),
    ]

    func testRecKitsCatalogCarriesTheDictionaryWordingInBothLanguages() throws {
        let catalog = try ShellCatalogs.catalog(
            "apple/RecKit/Sources/RecKit/Resources/Localizable.xcstrings"
        )
        for state in Self.states {
            let reading = try XCTUnwrap(catalog[state.en], "RecKit has no '\(state.en)'")
            XCTAssertEqual(reading["en"], state.en)
            XCTAssertEqual(reading["ko"], state.ko)
        }
    }

    /// The badge is the state as a code (docs/09 화면 원칙 2), and the two are minted from the same
    /// key — a rewording that missed [LedgerStatus.forRecent] would show as `UNKNOWN`.
    func testEachOfThemMintsItsOwnAccentBadge() {
        for state in Self.states {
            XCTAssertEqual(
                LedgerStatus.forRecent(state: state.en),
                LedgerStatus(code: state.code, tone: .accent),
                state.en
            )
        }
    }

    /// docs/07 rule 3: the row reads its own sentence where it is drawn, so the language it is drawn
    /// in is the one it comes out in.
    func testTheRowSaysTheKoreanOneOnAKoreanDevice() {
        AppLanguage.current = .ko
        defer { AppLanguage.current = .system }

        for state in Self.states {
            XCTAssertEqual(RecKitStrings.localized(state.en), state.ko)
        }
    }
}
