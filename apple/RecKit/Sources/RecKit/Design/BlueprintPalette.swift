import SwiftUI

/// docs/09 "토큰": a neutral palette with one accent, and nothing else. Every colour the four Apple
/// apps draw comes from here.
///
/// The tokens are kept as sRGB hexes rather than as `Color`s because two things need them: the
/// screens, which want a `Color`, and `BlueprintContrastTests`, which computes WCAG ratios over the
/// same numbers. A palette that answered only the first would leave the test checking a second copy
/// of the values, which is the copy that drifts.
public enum BlueprintToken: Int, CaseIterable, Sendable {
    case background
    case surface
    case grid
    case text
    case textMuted
    case accent
    case onAccent
    case danger
    case onDanger
    case success
    case warning
    /// The one token docs/09 does not name. Its `#B28600` amber is 3.3:1 on white, which is enough
    /// for a border and not enough for text (WCAG AA wants 4.5:1), so the palette carries a darker
    /// amber for the letters and keeps the documented one for the graphic. In dark they are the
    /// same colour, because there the documented one already passes.
    case warningInk
}

public struct BlueprintPalette: Equatable, Sendable {
    public let dark: Bool
    public let highContrast: Bool
    /// Indexed by `BlueprintToken.rawValue`.
    private let hexes: [UInt32]

    init(dark: Bool, highContrast: Bool, hexes: [UInt32]) {
        self.dark = dark
        self.highContrast = highContrast
        self.hexes = hexes
    }

    public func hex(_ token: BlueprintToken) -> UInt32 { hexes[token.rawValue] }

    public func color(_ token: BlueprintToken) -> Color { Color(blueprintHex: hex(token)) }

    public var background: Color { color(.background) }
    public var surface: Color { color(.surface) }
    public var grid: Color { color(.grid) }
    public var text: Color { color(.text) }
    public var textMuted: Color { color(.textMuted) }
    public var accent: Color { color(.accent) }
    public var onAccent: Color { color(.onAccent) }
    public var danger: Color { color(.danger) }
    public var onDanger: Color { color(.onDanger) }
    public var success: Color { color(.success) }
    public var warning: Color { color(.warning) }
    public var warningInk: Color { color(.warningInk) }

    /// docs/09 "선": 1pt, and 2pt in high contrast. Connectors, dividers, node borders.
    public var line: CGFloat { highContrast ? 2 : 1 }

    /// The border of the one that is chosen — a chip, a graph node. Always heavier than [line]
    /// rather than a flat 2pt: high contrast makes the plain hairline 2pt too, and a selection
    /// drawn at the same weight as everything around it is a selection said in colour alone
    /// (docs/09 "고대비 모드"). The Android palette's `selectedLine` is the same number.
    public var selectedLine: CGFloat { line + 1 }

    /// docs/09 "고대비 모드": the grid lines and the secondary text are promoted to the body colour,
    /// the accent keeps its saturation, and borders become 2pt (see [line]). The dot grid is
    /// switched off by the same flag.
    func promotedToHighContrast() -> BlueprintPalette {
        var promoted = hexes
        promoted[BlueprintToken.grid.rawValue] = hex(.text)
        promoted[BlueprintToken.textMuted.rawValue] = hex(.text)
        return BlueprintPalette(dark: dark, highContrast: true, hexes: promoted)
    }

    /// docs/09 팔레트 — Light.
    public static let light = BlueprintPalette(
        dark: false,
        highContrast: false,
        hexes: [
            0xF7F7F5, // background — paper
            0xFFFFFF, // surface
            0xE6E6E2, // grid
            0x111111, // text
            0x5E5E5A, // textMuted
            0x0F62FE, // accent — blueprint blue
            0xFFFFFF, // onAccent
            0xDA1E28, // danger
            0xFFFFFF, // onDanger
            0x198038, // success
            0xB28600, // warning
            0x8A6800, // warningInk
        ]
    )

    /// docs/09 팔레트 — Dark.
    public static let dark = BlueprintPalette(
        dark: true,
        highContrast: false,
        hexes: [
            0x0E0F12, // background
            0x16181D, // surface
            0x23262D, // grid
            0xF2F2F0, // text
            0x9A9CA3, // textMuted
            0x4589FF, // accent
            // Not white: `#4589FF` under white text is 3.3:1, under the page black it is 5.7:1.
            0x0E0F12, // onAccent
            0xFA4D56, // danger
            0x0E0F12, // onDanger
            0x42BE65, // success
            0xF1C21B, // warning
            0xF1C21B, // warningInk
        ]
    )

    /// The palette for the system's colour scheme and contrast setting — the one place the four
    /// variants are chosen.
    public static func palette(dark: Bool, highContrast: Bool) -> BlueprintPalette {
        let base = dark ? BlueprintPalette.dark : BlueprintPalette.light
        return highContrast ? base.promotedToHighContrast() : base
    }
}

extension Color {
    init(blueprintHex hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// docs/09 "접근성": WCAG AA — 4.5:1 for text, 3:1 for a graphic. The arithmetic is here rather than
/// in the test so that the rule the design claims to follow is written down where the colours are.
public enum WCAG {
    public static func relativeLuminance(_ hex: UInt32) -> Double {
        func channel(_ shift: UInt32) -> Double {
            let value = Double((hex >> shift) & 0xFF) / 255
            return value <= 0.03928 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    public static func contrast(_ one: UInt32, _ other: UInt32) -> Double {
        let a = relativeLuminance(one)
        let b = relativeLuminance(other)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
