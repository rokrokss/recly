import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/07 rule 9: the two checks every platform owes — every key exists in both languages, and no
/// Korean sentence is left hiding in the source where a resource should be.
///
/// Both walk the checkout rather than the built bundle, because what they are about is the source:
/// a catalog Xcode has not been asked to build yet is still the thing a reviewer reads, and a
/// literal in a target this test bundle does not link is exactly the one that would be missed.
final class LocalizationCatalogTests: XCTestCase {

    /// `apple/`, from this file rather than from a working directory nobody controls.
    static let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // RecKitTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // RecKit
        .deletingLastPathComponent() // apple

    /// Build products carry generated copies of the catalogs and of other people's sources; only
    /// what is checked in is the subject here.
    static let ignoredDirectories: Set<String> = [".build", "build", "DerivedData", "Frameworks"]

    /// Every catalog the four apps and RecKit are expected to ship, so that a target losing one is
    /// a failure rather than a silently smaller run.
    static let expectedCatalogs = [
        "RecKit/Sources/RecKit/Resources/Localizable.xcstrings",
        "RecMac/RecMac/Localizable.xcstrings",
        "RecMac/RecMac/InfoPlist.xcstrings",
        "RecPhone/RecPhone/Localizable.xcstrings",
        "RecPhone/RecPhone/InfoPlist.xcstrings",
        "RecPhone/RecPhone/AppShortcuts.xcstrings",
        "RecPhone/RecPhoneWidgets/Localizable.xcstrings",
        "RecWatch/RecWatch/Localizable.xcstrings",
        "RecWatch/RecWatch/InfoPlist.xcstrings",
        "RecWatch/RecWatch/AppShortcuts.xcstrings",
        "RecWatch/RecWatchWidgets/Localizable.xcstrings",
    ]

    func testEveryCatalogKeyIsWrittenInBothLanguages() throws {
        let catalogs = try Self.files(named: [
            "Localizable.xcstrings", "InfoPlist.xcstrings", "AppShortcuts.xcstrings",
        ])
        let found = catalogs.map { $0.path.replacingOccurrences(of: Self.appleRoot.path + "/", with: "") }
        XCTAssertEqual(found.sorted(), Self.expectedCatalogs.sorted())
        for catalog in catalogs {
            let json = try JSONSerialization.jsonObject(with: Data(contentsOf: catalog))
            let root = try XCTUnwrap(json as? [String: Any], "\(catalog.path) is not a catalog")
            XCTAssertEqual(root["sourceLanguage"] as? String, "en", catalog.path)
            let strings = try XCTUnwrap(root["strings"] as? [String: Any], catalog.path)
            XCTAssertFalse(strings.isEmpty, "\(catalog.path) has no keys at all")
            for (key, entry) in strings {
                let where_ = "\(catalog.lastPathComponent) '\(key)'"
                let localizations = try XCTUnwrap(
                    (entry as? [String: Any])?["localizations"] as? [String: Any],
                    "\(where_) has no localizations"
                )
                for language in ["en", "ko"] {
                    let unit = (localizations[language] as? [String: Any])?["stringUnit"]
                    let state = (unit as? [String: Any])?["state"] as? String
                    let value = (unit as? [String: Any])?["value"] as? String
                    XCTAssertEqual(state, "translated", "\(where_) is not translated into \(language)")
                    XCTAssertFalse(value?.isEmpty ?? true, "\(where_) is empty in \(language)")
                }
            }
        }
    }

    /// docs/07 rule 3: [UiMessage.key] resolves in **RecKit's** catalog and in no other, wherever
    /// the model that built the message lives.
    ///
    /// The regression: the disconnect and delete sentences were written into the phone's and the
    /// Mac's own catalogs, so the lookup found nothing and handed the key back — every one of them
    /// showed in English on a Korean device, with nothing to notice it by.
    ///
    /// Read off the sources rather than from a list here, so a sentence added in the wrong catalog
    /// fails this on the day it is written. RecKit's own are scanned too: the rules the shells share
    /// — a disconnect's completion, the blockers — carry their sentences with them, so most of these
    /// keys are written where the rule is rather than where it is drawn.
    func testEveryUiMessageKeyResolvesInRecKitsCatalog() throws {
        let sources = try Self.files(withExtension: "swift").filter { url in
            let parts = url.pathComponents
            // RecKit's *sources*: a test may hold a key on purpose that no catalog knows.
            return ["RecPhone", "RecMac"].contains(where: parts.contains)
                || (parts.contains("RecKit") && parts.contains("Sources"))
        }
        var keys: Set<String> = []
        for source in sources {
            let code = Self.strippingComments(try String(contentsOf: source, encoding: .utf8))
            keys.formUnion(Self.uiMessageKeys(in: code))
        }
        XCTAssertGreaterThanOrEqual(keys.count, 8, "the scan found almost no keys to check")

        AppLanguage.current = .ko
        defer { AppLanguage.current = .system }
        for key in keys.sorted() {
            // The English *is* the key, so a lookup that found nothing only shows in Korean.
            XCTAssertNotEqual(
                UiMessage.key(key).text, key,
                "'\(key)' is not in RecKit's catalog in Korean — UiMessage.key resolves nowhere else"
            )
        }
    }

    /// docs/07 rule 3 the other way round: a sentence a RecKit *view* draws resolves in RecKit's
    /// catalog, the way one a RecKit model *stores* does.
    ///
    /// The regression this exists for: the disconnect warning's "Drive's app data folder" line was
    /// written with curly apostrophes in the shells' code and straight ones in their catalogs, so
    /// the lookup found nothing and handed the key back — the sentence stood in English on a Korean
    /// device with nothing to notice it by. Now that the dialog and the workflow inspector are
    /// RecKit's, this is what catches it.
    ///
    /// The scan is over the sources for the same reason the two above are: a key added in the wrong
    /// spelling fails this on the day it is written.
    func testEveryKeyRecKitsOwnViewsDrawIsInItsCatalog() throws {
        let sources = try Self.files(withExtension: "swift").filter { url in
            let parts = url.pathComponents
            return parts.contains("RecKit") && parts.contains("Sources")
        }
        var keys: Set<String> = []
        for source in sources {
            let code = Self.strippingComments(try String(contentsOf: source, encoding: .utf8))
            keys.formUnion(Self.viewKeys(in: code))
        }
        // The families that are built rather than written, and so cannot be scanned for.
        keys.formUnion(AppLanguage.Choice.choices.map { "language." + $0.rawValue })
        // The three answers the theme section's chips carry (docs/09 "접근성").
        keys.formUnion(AppTheme.Choice.allCases.map(\.labelKey))
        // The label the alert banner's button carries — the surface the fix opens, named.
        keys.formUnion(FixSurface.allCases.map(\.labelKey))
        // The sentences [DisconnectDevice] hands the two dialogs, which are keys and not words.
        for device in [DisconnectDevice.mac, .phone] {
            keys.formUnion([
                device.deleted, device.done, device.deleteHereOnly, device.everyDeviceLosesAccess,
                device.unuploadedStay, device.queueWiped, device.alsoDeleteRecordings,
            ])
        }
        XCTAssertGreaterThan(keys.count, 60, "the scan found almost no keys to check")

        // Membership in the catalog rather than "the Korean differs": `URL` and `Invoke URL` are
        // field names that are the same word in both languages (docs/07 rule 4), and a lookup that
        // found them would be indistinguishable from one that found nothing.
        // [testEveryCatalogKeyIsWrittenInBothLanguages] is what answers for the two languages.
        let catalog = URL(
            fileURLWithPath: "RecKit/Sources/RecKit/Resources/Localizable.xcstrings",
            relativeTo: Self.appleRoot
        )
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: catalog))
        let written = try XCTUnwrap((json as? [String: Any])?["strings"] as? [String: Any])
        let absent = keys.sorted().filter { written[$0] == nil }
        XCTAssertEqual(
            absent, [],
            "drawn by a RecKit view and not in RecKit's catalog — the lookup hands the key back:\n"
                + absent.joined(separator: "\n")
        )
    }

    /// Every `loc("…")` / `RecKitStrings.localized("…")` whose argument is the *whole* literal.
    /// A literal that runs on into a `+` is a sentence built from pieces (the key-sync warning) or
    /// a family built from a stem (`"language." + choice`), and neither is a key this can check —
    /// the two families are named in the test instead.
    static func viewKeys(in code: String) -> Set<String> {
        var keys: Set<String> = []
        for call in ["RecKitStrings.localized(", "loc("] {
            var rest = Substring(code)
            while let found = rest.range(of: call) {
                // `loc(` also matches the tail of `RecKitStrings.localized(`, which is harmless:
                // the same key is simply seen twice.
                rest = rest[found.upperBound...]
                let argument = rest.drop(while: { $0.isWhitespace || $0.isNewline })
                guard argument.first == "\"" else { continue }
                let body = argument.dropFirst()
                guard let end = body.firstIndex(of: "\""), !body[..<end].contains("\\") else { continue }
                // Only a complete argument: `"…" + x` is a stem, not a key.
                let after = body[end...].dropFirst().drop(while: \.isWhitespace)
                guard after.first == ")" || after.first == "," else { continue }
                keys.insert(String(body[..<end]))
            }
        }
        return keys
    }

    /// Every `.key("…")` literal in one source. The first argument of [UiMessage.key] is always a
    /// literal — that is what rule 3 is about — so there is nothing here that has to be evaluated.
    static func uiMessageKeys(in code: String) -> Set<String> {
        var keys: Set<String> = []
        var rest = Substring(code)
        while let call = rest.range(of: ".key(") {
            rest = rest[call.upperBound...]
            let argument = rest.drop(while: \.isWhitespace)
            guard argument.first == "\"" else { continue }
            let body = argument.dropFirst()
            guard let end = body.firstIndex(of: "\""), !body[..<end].contains("\\") else { continue }
            keys.insert(String(body[..<end]))
        }
        return keys
    }

    /// docs/07 rule 9's allow-list, and the whole of it: a test may name a workflow in Korean
    /// because that is data a user would type, and `\.lproj` catalogs are where Korean belongs.
    static let allowsKorean = ["RecKitTests", "RecPhoneTests", "RecPhoneUITests"]

    func testNoKoreanSentenceIsLeftInTheSwiftSources() throws {
        let sources = try Self.files(withExtension: "swift")
            .filter { url in !Self.allowsKorean.contains(where: url.pathComponents.contains) }
        XCTAssertGreaterThan(sources.count, 60, "the scan found almost nothing to scan")
        var offenders: [String] = []
        for source in sources {
            let code = try Self.strippingComments(String(contentsOf: source, encoding: .utf8))
            for (number, line) in code.components(separatedBy: "\n").enumerated()
            where line.contains(where: Self.isHangul) {
                offenders.append("\(source.lastPathComponent):\(number + 1) \(line.trimmed)")
            }
        }
        XCTAssertEqual(
            offenders, [],
            "a user-visible string belongs in a catalog, not in the source:\n"
                + offenders.joined(separator: "\n")
        )
    }

    private static func isHangul(_ character: Character) -> Bool {
        character.unicodeScalars.contains { (0xAC00...0xD7A3).contains($0.value) }
    }

    /// Comments are exempt — a docs citation names a Korean heading, and renaming the heading in
    /// every reference to it is not what rule 9 is for. Everything else is not: a literal is a
    /// literal whether it is one line or three.
    static func strippingComments(_ source: String) -> String {
        enum State { case code, line, block, text, multiline }
        var state = State.code
        var kept = ""
        var index = source.startIndex
        func peek(_ ahead: Int) -> Character? {
            source.index(index, offsetBy: ahead, limitedBy: source.index(before: source.endIndex))
                .map { source[$0] }
        }
        while index < source.endIndex {
            let character = source[index]
            switch state {
            case .code:
                if character == "/", peek(1) == "/" { state = .line }
                else if character == "/", peek(1) == "*" { state = .block }
                else if character == "\"", peek(1) == "\"", peek(2) == "\"" { state = .multiline; kept += "\"\"\"" ; index = source.index(index, offsetBy: 3); continue }
                else if character == "\"" { state = .text }
                if state == .code || state == .text || state == .multiline { kept.append(character) }

            case .line:
                if character == "\n" { state = .code; kept.append(character) }

            case .block:
                if character == "*", peek(1) == "/" { state = .code; index = source.index(index, offsetBy: 2); continue }
                if character == "\n" { kept.append(character) }

            case .text:
                kept.append(character)
                if character == "\\" { // An escape cannot end the literal.
                    if let next = peek(1) { kept.append(next); index = source.index(index, offsetBy: 2); continue }
                } else if character == "\"" || character == "\n" {
                    state = .code
                }

            case .multiline:
                kept.append(character)
                if character == "\"", peek(1) == "\"", peek(2) == "\"" {
                    kept += "\"\""; index = source.index(index, offsetBy: 3); state = .code; continue
                }
            }
            index = source.index(after: index)
        }
        return kept
    }

    // MARK: - Walking the checkout

    private static func files(withExtension ext: String) throws -> [URL] {
        try walk { $0.pathExtension == ext }
    }

    private static func files(named names: Set<String>) throws -> [URL] {
        try walk { names.contains($0.lastPathComponent) }
    }

    private static func walk(_ include: (URL) -> Bool) throws -> [URL] {
        let manager = FileManager.default
        let enumerator = try XCTUnwrap(
            manager.enumerator(at: appleRoot, includingPropertiesForKeys: [.isDirectoryKey])
        )
        var found: [URL] = []
        for case let url as URL in enumerator {
            let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory
            if isDirectory == true {
                if ignoredDirectories.contains(url.lastPathComponent) { enumerator.skipDescendants() }
                continue
            }
            if include(url) { found.append(url) }
        }
        return found.sorted { $0.path < $1.path }
    }
}

/// docs/07 §5 on the Apple side: the core hands over a key and this is what a person reads.
final class CoreMessagesTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// Exhaustive over the enum itself rather than over a list written out here, so a key added to
    /// the core fails this until it has a sentence.
    func testEveryCoreMessageHasASentenceInBothLanguages() {
        for message in CoreMessage.allCases {
            AppLanguage.current = .en
            let english = CoreMessages.sentence(message, arg: "x")
            AppLanguage.current = .ko
            let korean = CoreMessages.sentence(message, arg: "x")

            XCTAssertFalse(english.isEmpty, "\(message.name) has no sentence")
            // The English *is* the key, so a lookup that found nothing only shows in Korean.
            XCTAssertNotEqual(
                korean, english,
                "\(message.name) is not translated — the catalog gave the key back"
            )
            if CoreMessages.takesArgument(message) {
                XCTAssertTrue(english.contains("x"), "\(message.name) dropped its argument")
                XCTAssertTrue(korean.contains("x"), "\(message.name) dropped its argument in Korean")
            }
        }
    }

    func testACodeBecomesASentenceAndKeepsItsDetailApart() {
        AppLanguage.current = .en
        let text = CoreMessages.text(CoreMessage.webhookHttp.code(arg: "500", detail: "{\"e\":1}"))

        XCTAssertEqual(text.sentence, "The webhook answered HTTP 500")
        XCTAssertEqual(text.detail, "{\"e\":1}")
    }

    /// docs/07 §5 compatibility: a `last_error` an older build wrote is prose, and it is shown
    /// exactly as it was stored rather than replaced with a guess.
    func testASentenceAnOlderBuildStoredIsShownAsItStands() {
        let stored = "Google Drive 권한 재동의가 필요합니다"

        let text = CoreMessages.text(stored)

        XCTAssertEqual(text.sentence, stored)
        XCTAssertNil(text.detail)
    }

    /// The one key whose argument is itself a key (docs/07 §5): the failure that spent the last
    /// attempt is translated inside the sentence that reports it.
    func testTheRetryBudgetNestsTheFailureThatSpentIt() {
        AppLanguage.current = .en

        let text = CoreMessages.text(
            CoreMessage.retryBudgetSpent.code(arg: CoreMessage.needsAuth.code(arg: nil, detail: nil), detail: nil)
        )

        XCTAssertEqual(text.sentence, "Out of retries: Sign in again to carry on")
    }
}

/// docs/07 rule 2·3: the setting is stored on the device and read from everywhere.
final class AppLanguageTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheChoiceSurvivesAndDecidesTheLocale() {
        AppLanguage.current = .ko

        XCTAssertEqual(AppLanguage.current, .ko)
        XCTAssertEqual(AppLanguage.locale.identifier, "ko")
        XCTAssertEqual(UserDefaults.standard.string(forKey: "appLanguage"), "ko")
    }

    /// `system` has no tag of its own: it is the device's, which is what the bundle loader already
    /// resolved the app to.
    func testSystemFollowsTheDevice() {
        AppLanguage.current = .system

        XCTAssertNil(AppLanguage.Choice.system.code)
        XCTAssertEqual(AppLanguage.locale, .current)
    }

    /// The point of the whole mechanism: the same key, two languages, without a relaunch.
    func testALookupFollowsTheChoice() {
        AppLanguage.current = .en
        let english = RecKitStrings.localized("Waiting")
        AppLanguage.current = .ko
        let korean = RecKitStrings.localized("Waiting")

        XCTAssertEqual(english, "Waiting")
        XCTAssertEqual(korean, "대기")
    }

    func testAnArgumentIsFormattedIntoTheSentence() {
        AppLanguage.current = .en

        XCTAssertEqual(RecKitStrings.localized("Failed: %@", "nope"), "Failed: nope")
    }

    /// docs/07 rule 2: what rides to the watch is the phone's *effective* language. `system` means
    /// "this device's locale", and on the other side of the link that device is the watch — which
    /// is the one thing the watch must not fall back to while a phone is telling it.
    func testTheWatchIsToldAResolvedLanguageRatherThanTheChoice() {
        AppLanguage.current = .system
        let followed = WatchWorkflows.context([], language: AppLanguage.resolvedCode)

        XCTAssertTrue(["en", "ko"].contains(followed[WatchWorkflows.languageKey] as? String ?? ""))
        XCTAssertNotEqual(WatchWorkflows.language(followed), .system)

        AppLanguage.current = .ko
        let picked = WatchWorkflows.context([], language: AppLanguage.resolvedCode)

        XCTAssertEqual(WatchWorkflows.language(picked), .ko)
    }

    /// docs/07 rule 2: the picker offers languages and not "follow the system" — a device that has
    /// never been given one is shown the language it followed the system to.
    func testThePickerOffersTheTwoLanguagesAndNotTheSystemDefault() {
        XCTAssertEqual(AppLanguage.Choice.choices, [.en, .ko])
    }

    /// What the row says and the picker marks, for a choice and for no choice at all.
    func testTheEffectiveLanguageIsKoreanOnlyForAKoreanSystem() {
        XCTAssertEqual(AppLanguage.effective(.system, system: "ko"), .ko)
        XCTAssertEqual(AppLanguage.effective(.system, system: "en"), .en)
        XCTAssertEqual(AppLanguage.effective(.system, system: "ja"), .en)
        XCTAssertEqual(AppLanguage.effective(.system, system: nil), .en)
        XCTAssertEqual(AppLanguage.effective(.ko, system: "en"), .ko)
        XCTAssertEqual(AppLanguage.effective(.en, system: "ko"), .en)
    }
}

/// docs/07 rule 3: a message a model holds on to is a key and its arguments, and the sentence is
/// made where it is read — so the same stored value says two different things under two languages,
/// with nothing re-stored in between.
final class UiMessageTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheSameStoredKeySpeaksBothLanguages() {
        let stored = UiMessage.key("Waiting")

        AppLanguage.current = .en
        XCTAssertEqual(stored.text, "Waiting")
        AppLanguage.current = .ko
        XCTAssertEqual(stored.text, "대기")
    }

    /// The argument is never translated — a count is a count — but the sentence around it is, and
    /// the count survives the switch because it was stored beside the key rather than baked in.
    func testAnArgumentIsFormattedInAtEachReading() {
        let stored = UiMessage.key(
            "Imported — %@ workflow(s) on this device now.",
            args: [.verbatim("3")]
        )

        AppLanguage.current = .en
        let english = stored.text
        AppLanguage.current = .ko
        let korean = stored.text

        XCTAssertEqual(english, "Imported — 3 workflow(s) on this device now.")
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
        XCTAssertTrue(korean.contains("3"), "the count did not survive: \(korean)")
    }

    /// docs/07 §5: a core code goes through [CoreMessages], which is the same rule one level down.
    func testACoreCodeIsTurnedIntoWordsWhereItIsRead() {
        let stored = UiMessage.core(CoreMessage.webhookHttp.code(arg: "500", detail: nil))

        AppLanguage.current = .en
        let english = stored.text
        AppLanguage.current = .ko
        let korean = stored.text

        XCTAssertEqual(english, "The webhook answered HTTP 500")
        XCTAssertNotEqual(korean, english)
    }

    /// docs/07 rule 3 on the secret form: the refusal is kept as a message and not as words, so a
    /// form still open when the language is changed answers it rather than standing in the old one.
    func testASecretFormKeepsItsRefusalAsAMessage() throws {
        var form = SecretForm()
        form.error = try XCTUnwrap(SecretName.problem("Hook"))

        AppLanguage.current = .en
        let english = form.error?.text
        AppLanguage.current = .ko
        let korean = form.error?.text

        XCTAssertEqual(
            english, "Starts with a lowercase letter; lowercase, digits and underscores, up to 32"
        )
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
    }

    /// docs/07 rule 4: a log line is read by whoever collected it, so what identifies a message
    /// there is what it was stored as — never the sentence, which changes under the reader.
    func testALogLineNamesTheKeyRatherThanTheSentence() {
        AppLanguage.current = .ko

        XCTAssertEqual(UiMessage.key("Waiting").logCode, "Waiting")
        XCTAssertEqual(
            UiMessage.core(CoreMessage.needsAuth.code(arg: nil, detail: nil)).logCode,
            CoreMessage.needsAuth.code(arg: nil, detail: nil)
        )
    }

    /// The parser's own list, a name the user typed: not everything on a banner is translatable.
    func testVerbatimTextIsLeftAlone() {
        let stored = UiMessage.verbatim("steps[0].folder is empty")

        AppLanguage.current = .ko

        XCTAssertEqual(stored.text, "steps[0].folder is empty")
    }
}

/// docs/07 rule 3 in the recent list: the row keeps the code the core wrote, not the sentence — so
/// a list already on screen answers a language change.
final class RecentItemMessageTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    private func item(lastError: String?) -> RecentItem {
        RecentItem(
            id: "01J9",
            jobId: "job-1",
            title: "",
            startedAt: "2026-08-29T00:00:00.000Z",
            state: "Failed",
            link: nil,
            lastError: lastError
        )
    }

    func testTheRowKeepsTheCodeAndMakesTheSentenceWhereItIsDrawn() {
        let row = item(lastError: CoreMessage.driveReauth.code(arg: nil, detail: "401"))

        AppLanguage.current = .en
        let english = row.reason
        AppLanguage.current = .ko
        let korean = row.reason

        XCTAssertEqual(english?.sentence, "Google Drive access has to be allowed again")
        XCTAssertNotEqual(korean?.sentence, english?.sentence, "the row froze the sentence")
        // The diagnostic under it is the core's own and is never translated.
        XCTAssertEqual(korean?.detail, "401")
    }

    /// docs/07 §5 compatibility: an older build's prose is passed through in either language.
    func testASentenceAnOlderBuildStoredIsStillShownAsItStands() {
        let row = item(lastError: "Google Drive 권한 재동의가 필요합니다")

        AppLanguage.current = .ko

        XCTAssertEqual(row.reason?.sentence, "Google Drive 권한 재동의가 필요합니다")
    }

    /// A job that is simply waiting has nothing to explain.
    func testNothingIsSaidWhenNothingWentWrong() {
        XCTAssertNil(item(lastError: nil).reason)
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespaces) }
}
