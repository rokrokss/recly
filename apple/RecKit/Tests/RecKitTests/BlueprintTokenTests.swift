import SwiftUI
#if os(macOS)
import AppKit
#endif
import XCTest
@testable import RecKit

/// docs/09 "Accessibility": WCAG AA — 4.5:1 for text, 3:1 for a graphic — for every pair the four Apple
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

    #if os(macOS)
    @MainActor
    func testLongActionLabelsWrapWithinAConstrainedFlow() {
        let view = NSHostingView(rootView: FlowLayout {
            BlueprintButton("Search a very long transcript with a localized action label") {}
        }.frame(width: 160))
        view.layoutSubtreeIfNeeded()
        XCTAssertEqual(view.fittingSize.width, 160, accuracy: 1)
        XCTAssertGreaterThan(view.fittingSize.height, minTouch + 8,
            "The action must grow vertically instead of drawing one line outside the viewport")
    }
    #endif

    #if os(macOS)
    /// docs/09 "Disabled, focus and structure" (2026-10-15): a button showing its own work — `…`, `✓` — takes no press
    /// but keeps its ink and edge, drawn exactly as the live button is; only a disabled one is drawn in `disabled`.
    @MainActor
    func testAButtonShowingItsWorkKeepsItsInk() throws {
        func drawn(_ view: some View) throws -> Data {
            let host = NSHostingView(rootView: view.environment(\.blueprint, Blueprint()).background(Color.white))
            host.setFrameSize(host.fittingSize)
            let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
            window.contentView = host
            host.layoutSubtreeIfNeeded()
            let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
            host.cacheDisplay(in: host.bounds, to: bitmap)
            return try XCTUnwrap(bitmap.tiffRepresentation)
        }
        for tone in [ButtonTone.primary, .accent, .quiet] {
            let live = try drawn(BlueprintButton("…", tone: tone) {})
            XCTAssertEqual(try drawn(BlueprintButton("…", tone: tone) {}.showingWork()), live, "\(tone) dimmed while it works")
            XCTAssertNotEqual(try drawn(BlueprintButton("…", tone: tone) {}.disabled(true)), live, "\(tone) disabled looks live")
        }
    }
    #endif

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
        for (_, palette) in Self.palettes {
            XCTAssertEqual(palette.inputBorder, palette.textMuted)
        }
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

    /// docs/09 "Tokens": the two widget extensions link no RecKit — a widget process that carried the
    /// core would carry the database with it (docs/13) — so the handful of values they draw are
    /// written out in their own `WidgetTokens.swift`. This is what keeps those copies honest: a
    /// hex that moves in the palette has to move there too, or this fails.
    ///
    /// 2026-10-15: every ground those extensions draw on is dark — the Live Activity, the Dynamic Island, the
    /// widget's container, a watch face — so every colour copied there is the **dark** palette's, read here by
    /// its name. The light red on the activity's page black was 3.8:1.
    func testTheWidgetsCopyOfTheTokensIsThePalettes() throws {
        let source = try Self.source("RecPhone/RecPhoneWidgets/WidgetTokens.swift")
        let phone = try Self.widgetColours(in: source)
        XCTAssertEqual(Set(phone.keys), ["danger", "text", "background"], "the phone widget's copies changed")
        for (name, hex) in phone {
            let token = try XCTUnwrap(Self.token(named: name), "\(name) is not a palette token")
            XCTAssertEqual(hex, BlueprintPalette.dark.hex(token), "the phone widget's \(name) is not the dark palette's")
        }
        XCTAssertTrue(source.contains("node: CGFloat = \(Int(Radius.node))"))
        XCTAssertTrue(source.contains("badge: CGFloat = \(Int(Radius.badge))"))
        XCTAssertTrue(source.contains("minTouch: CGFloat = \(Int(minTouch))"))
        // What the activity draws in words on that ground — the red Stop, the white clock — reads as text does.
        for ink in [BlueprintToken.danger, .text] {
            assertContrast(.dark, ink, on: .background, atLeast: 4.5, in: "widget")
        }

        let watch = try Self.source("RecWatch/RecWatchWidgets/WidgetTokens.swift")
        XCTAssertTrue(watch.contains("iconSize: CGFloat = 19"), "the complication's glyph size drifted")
        // 2026-10-11 (N4): the complication's recording mark is the palette's red too — on a watch face, the dark one.
        XCTAssertEqual(try Self.widgetColours(in: watch), ["danger": BlueprintPalette.dark.hex(.danger)])
        XCTAssertTrue(watch.contains("node: CGFloat = \(Int(Radius.node))"))
        XCTAssertTrue(watch.contains("badge: CGFloat = \(Int(Radius.badge))"))
    }

    /// docs/09 "Tokens": the share extension's line and page are the palette's too — the light value on the light
    /// page, the dark on the dark, and the secondary ink promoted to the body colour in high contrast.
    func testTheShareExtensionsCopyOfTheTokensIsThePalettes() throws {
        let source = try Self.source("RecPhone/RecPhoneShare/ShareViewController.swift")
        let regex = try NSRegularExpression(
            pattern: #"static let (\w+) = color\(light: 0x([0-9A-Fa-f]{6}), dark: 0x([0-9A-Fa-f]{6})(?:, contrastLight: 0x([0-9A-Fa-f]{6}), contrastDark: 0x([0-9A-Fa-f]{6}))?\)"#
        )
        let matches = regex.matches(in: source, range: NSRange(source.startIndex..., in: source))
        XCTAssertEqual(matches.count, 2, "the share extension's copies changed")
        for match in matches {
            func group(_ index: Int) -> String? {
                Range(match.range(at: index), in: source).map { String(source[$0]) }
            }
            let name = try XCTUnwrap(group(1))
            let token = try XCTUnwrap(Self.token(named: name), "\(name) is not a palette token")
            XCTAssertEqual(group(2), String(format: "%06X", BlueprintPalette.light.hex(token)), "\(name), light")
            XCTAssertEqual(group(3), String(format: "%06X", BlueprintPalette.dark.hex(token)), "\(name), dark")
            if token == .textMuted {
                XCTAssertEqual(group(4), String(format: "%06X", BlueprintPalette.palette(dark: false, highContrast: true).hex(token)))
                XCTAssertEqual(group(5), String(format: "%06X", BlueprintPalette.palette(dark: true, highContrast: true).hex(token)))
            }
        }
    }

    private static func source(_ path: String) throws -> String {
        try String(contentsOf: LocalizationCatalogTests.appleRoot.appendingPathComponent(path), encoding: .utf8)
    }

    /// `static let <name> = Color(widgetHex: 0x……)`, as name → hex.
    private static func widgetColours(in source: String) throws -> [String: UInt32] {
        let regex = try NSRegularExpression(pattern: #"static let (\w+) = Color\(widgetHex: 0x([0-9A-Fa-f]{6})\)"#)
        var colours: [String: UInt32] = [:]
        for match in regex.matches(in: source, range: NSRange(source.startIndex..., in: source)) {
            guard let name = Range(match.range(at: 1), in: source), let hex = Range(match.range(at: 2), in: source) else { continue }
            colours[String(source[name])] = UInt32(source[hex], radix: 16)
        }
        return colours
    }

    private static func token(named name: String) -> BlueprintToken? {
        BlueprintToken.allCases.first { String(describing: $0) == name }
    }

    /// docs/09 "Derived tokens" (2026-10-15): `disabled` is the secondary colour at half opacity, so it is lighter than
    /// enabled ink in every palette — in high contrast too, where the secondary colour is the body colour and a disabled
    /// control drawn in it would look live.
    func testDisabledInkIsLighterThanEnabledInkInEveryPalette() {
        for (name, palette) in Self.palettes {
            for ground in [BlueprintToken.surface, .background] {
                let page = palette.hex(ground)
                let disabled = Self.composite(palette.hex(.textMuted), opacity: BlueprintPalette.disabledOpacity, over: page)
                XCTAssertLessThan(
                    WCAG.contrast(disabled, page), WCAG.contrast(palette.hex(.textMuted), page),
                    "\(name)/disabled on \(ground) is no lighter than the secondary ink"
                )
                XCTAssertLessThan(WCAG.contrast(disabled, page), WCAG.contrast(palette.hex(.text), page), "\(name)/\(ground)")
            }
        }
    }

    /// docs/09 "Spacing": the named steps, 2 to 32, every one a multiple of two and the ones past `xxs` of four.
    func testTheSpacingStepsAreTheNamedOnes() {
        XCTAssertEqual([Space.xxs, Space.xs, Space.s, Space.sm, Space.m, Space.l, Space.xl], [2, 4, 8, 12, 16, 24, 32])
    }

    /// [ink] at [opacity] over [ground], channel by channel in sRGB — what drawing it does.
    private static func composite(_ ink: UInt32, opacity: Double, over ground: UInt32) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let top = Double((ink >> shift) & 0xFF)
            let bottom = Double((ground >> shift) & 0xFF)
            return UInt32((top * opacity + bottom * (1 - opacity)).rounded()) << shift
        }
        return channel(16) | channel(8) | channel(0)
    }

    /// The check itself, on a pair whose answer is known: black on white is 21:1.
    func testTheRatioIsTheWCAGOne() {
        XCTAssertEqual(WCAG.contrast(0x000000, 0xFFFFFF), 21, accuracy: 0.05)
        XCTAssertEqual(WCAG.contrast(0x777777, 0x777777), 1, accuracy: 0.0001)
    }

    /// docs/09 "High-contrast mode": the two quiet tokens are promoted to the body colour and the line grows.
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

/// docs/09 "Accessibility": the system's light/dark is followed without being asked about, and [AppTheme] is
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
    /// docs/12 "Menu bar": the Mac has no one SwiftUI root to hang a `.preferredColorScheme` on — the
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
