import SwiftUI
import XCTest
@testable import RecKit

/// docs/09 "접근성": WCAG AA — 4.5:1 for text, 3:1 for a graphic — for every pair the four Apple
/// apps actually draw, in all four palettes (light, dark, and each of them in high contrast).
///
/// What is *not* in here is the grid colour against its background. A hairline divider carries no
/// information (WCAG 1.4.11 is about graphics you must see to understand the content), and docs/09
/// asks for it to be quiet; in high contrast it is promoted to the body colour anyway, which is the
/// variant a user who needs to see it turns on.
final class BlueprintContrastTests: XCTestCase {

    private static let palettes: [(String, BlueprintPalette)] = [
        ("light", .palette(dark: false, highContrast: false)),
        ("dark", .palette(dark: true, highContrast: false)),
        ("light-hc", .palette(dark: false, highContrast: true)),
        ("dark-hc", .palette(dark: true, highContrast: true)),
    ]

    /// Every colour this design puts letters in, on both grounds it puts them on.
    private static let inks: [BlueprintToken] = [
        .text, .textMuted, .accent, .danger, .success, .warningInk,
    ]

    /// Badge borders and node edges — colour that means something without being read.
    private static let edges: [BlueprintToken] = [.accent, .danger, .success, .warning, .textMuted]

    func testEveryTextPairClears4_5To1InEveryPalette() {
        for (name, palette) in Self.palettes {
            for ink in Self.inks {
                for ground in [BlueprintToken.surface, .background] {
                    assertContrast(palette, ink, on: ground, atLeast: 4.5, in: name)
                }
            }
            // The two filled surfaces: the primary button and the recording node.
            assertContrast(palette, .onAccent, on: .accent, atLeast: 4.5, in: name)
            assertContrast(palette, .onDanger, on: .danger, atLeast: 4.5, in: name)
        }
    }

    func testEveryStatusGraphicClears3To1InEveryPalette() {
        for (name, palette) in Self.palettes {
            for edge in Self.edges {
                for ground in [BlueprintToken.surface, .background] {
                    assertContrast(palette, edge, on: ground, atLeast: 3, in: name)
                }
            }
        }
    }

    /// The badge draws its code in `tone.ink` on the surface, which is the pair a reader has to
    /// make out — and for amber that is deliberately not the colour of the border beside it.
    func testEveryBadgeToneIsReadableOnTheSurface() {
        for (name, palette) in Self.palettes {
            for tone in [BadgeTone.neutral, .accent, .success, .warning, .danger] {
                assertContrast(palette, tone.inkToken, on: .surface, atLeast: 4.5, in: "\(name)/\(tone)")
                assertContrast(palette, tone.edgeToken, on: .surface, atLeast: 3, in: "\(name)/\(tone)")
            }
        }
    }

    /// docs/09 "토큰": the two widget extensions link no RecKit — a widget process that carried the
    /// core would carry the database with it (docs/13) — so the handful of values they draw are
    /// written out in their own `WidgetTokens.swift`. This is what keeps those copies honest: a
    /// hex that moves in the palette has to move there too, or this fails.
    func testTheWidgetsCopyOfTheTokensIsThePalettes() throws {
        let source = try String(
            contentsOf: LocalizationCatalogTests.appleRoot
                .appendingPathComponent("RecPhone/RecPhoneWidgets/WidgetTokens.swift"),
            encoding: .utf8
        )
        for (name, token) in [("danger", BlueprintToken.danger), ("background", .background)] {
            let hex = String(format: "0x%06X", name == "danger"
                ? BlueprintPalette.light.hex(token)
                : BlueprintPalette.dark.hex(token))
            XCTAssertTrue(
                source.contains(hex),
                "the phone widget's \(name) is not the palette's \(hex)"
            )
        }
        XCTAssertTrue(source.contains("node: CGFloat = \(Int(Radius.node))"))
        XCTAssertTrue(source.contains("badge: CGFloat = \(Int(Radius.badge))"))
        XCTAssertTrue(source.contains("minTouch: CGFloat = \(Int(minTouch))"))

        let watch = try String(
            contentsOf: LocalizationCatalogTests.appleRoot
                .appendingPathComponent("RecWatch/RecWatchWidgets/WidgetTokens.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(watch.contains("iconSize: CGFloat = 19"), "the complication's glyph size drifted")
    }

    /// The check itself, on a pair whose answer is known: black on white is 21:1.
    func testTheRatioIsTheWCAGOne() {
        XCTAssertEqual(WCAG.contrast(0x000000, 0xFFFFFF), 21, accuracy: 0.05)
        XCTAssertEqual(WCAG.contrast(0x777777, 0x777777), 1, accuracy: 0.0001)
    }

    /// docs/09 "고대비 모드": the two quiet tokens are promoted to the body colour and the line grows.
    func testHighContrastPromotesTheQuietTokensAndThickensTheLine() {
        let plain = BlueprintPalette.palette(dark: false, highContrast: false)
        let contrast = BlueprintPalette.palette(dark: false, highContrast: true)

        XCTAssertEqual(contrast.hex(.grid), contrast.hex(.text))
        XCTAssertEqual(contrast.hex(.textMuted), contrast.hex(.text))
        XCTAssertEqual(contrast.hex(.accent), plain.hex(.accent), "the accent lost its saturation")
        XCTAssertEqual(plain.line, 1)
        XCTAssertEqual(contrast.line, 2)
    }

    private func assertContrast(
        _ palette: BlueprintPalette,
        _ ink: BlueprintToken,
        on ground: BlueprintToken,
        atLeast wanted: Double,
        in name: String,
        line: UInt = #line
    ) {
        let ratio = WCAG.contrast(palette.hex(ink), palette.hex(ground))
        XCTAssertGreaterThanOrEqual(
            ratio, wanted,
            "\(name)/\(ink) on \(ground) is \(String(format: "%.2f", ratio)):1, WCAG AA wants \(wanted):1",
            line: line
        )
    }
}

/// docs/09 "접근성": the system's light/dark is followed without being asked about, and [AppTheme] is
/// the one override of it — stored on the device, and nothing stored means nothing chosen.
final class AppThemeTests: XCTestCase {

    override func tearDown() {
        AppTheme.current = .system
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheChoiceSurvivesAndNoChoiceFollowsTheSystem() {
        AppTheme.current = .dark

        XCTAssertEqual(AppTheme.current, .dark)
        XCTAssertEqual(UserDefaults.standard.string(forKey: "appTheme"), "dark")

        UserDefaults.standard.removeObject(forKey: "appTheme")
        XCTAssertEqual(AppTheme.current, .system, "an install that has never been asked follows the OS")
    }

    /// Nil and not a scheme of its own: that is what `.preferredColorScheme` and `NSApp.appearance`
    /// both take for "follow the device", and it is the whole of what `system` means.
    func testOnlyAnOverrideNamesAColourScheme() {
        XCTAssertNil(AppTheme.Choice.system.colorScheme)
        XCTAssertEqual(AppTheme.Choice.light.colorScheme, .light)
        XCTAssertEqual(AppTheme.Choice.dark.colorScheme, .dark)
        #if os(macOS)
        XCTAssertNil(AppTheme.Choice.system.appearance)
        XCTAssertEqual(AppTheme.Choice.light.appearance?.name, .aqua)
        XCTAssertEqual(AppTheme.Choice.dark.appearance?.name, .darkAqua)
        #endif
    }

    #if os(macOS)
    /// docs/12 "메뉴바": the Mac has no one SwiftUI root to hang a `.preferredColorScheme` on — the
    /// popover is an `NSPanel` and the editor and details are windows — so the setting is written
    /// onto the application itself, which all three inherit and SwiftUI reads back as
    /// `\.colorScheme`. Nothing written is the system's own again.
    @MainActor
    func testTheMacWritesTheOverrideOntoTheApplicationItself() {
        AppTheme.shared.choice = .dark
        XCTAssertEqual(NSApplication.shared.appearance?.name, .darkAqua)

        AppTheme.shared.choice = .light
        XCTAssertEqual(NSApplication.shared.appearance?.name, .aqua)

        AppTheme.shared.choice = .system
        XCTAssertNil(NSApplication.shared.appearance, "the system's answer is no appearance of our own")
    }
    #endif

    /// The chip row: the system's answer first, then the two overrides — the same three the Windows
    /// settings window offers, in the same words (docs/07 rule 11).
    func testTheChipsAreTheSystemAnswerAndTheTwoOverrides() {
        XCTAssertEqual(AppTheme.Choice.allCases, [.system, .light, .dark])

        AppLanguage.current = .en
        XCTAssertEqual(AppTheme.Choice.allCases.map(\.label), ["System default", "Light", "Dark"])
        AppLanguage.current = .ko
        XCTAssertEqual(AppTheme.Choice.allCases.map(\.label), ["시스템 기본", "밝게", "어둡게"])
    }
}
