import SwiftUI

/// docs/09 "토큰", for the two extensions that cannot import RecKit.
///
/// A widget extension links no core (docs/13: it would carry the whole database with it for the
/// sake of one status line, and the watch has a 75 MB budget to keep), and RecKit is where the
/// palette lives — so the handful of values the Live Activity and the complication actually draw
/// are written here as the same sRGB hexes `BlueprintPalette` holds. Six numbers, not a palette:
/// what an extension draws is a mark, a rule and a scrim.
///
/// `BlueprintTokenTests` checks these against the palette itself, so a token that moves in docs/09
/// cannot leave the widgets behind.
enum WidgetTokens {
    /// `BlueprintPalette.light.danger` — the recording mark, everywhere it appears.
    static let danger = Color(widgetHex: 0xDA1E28)
    /// `BlueprintPalette.dark.background` — the ground a Lock Screen activity is tinted with.
    static let background = Color(widgetHex: 0x0E0F12)

    /// docs/09 "형태": 4 for a node, 2 for a badge.
    enum Radius {
        static let node: CGFloat = 4
        static let badge: CGFloat = 2
    }

    /// docs/09 "접근성": whatever it draws, nothing you can tap is smaller than this.
    static let minTouch: CGFloat = 44
}

extension Color {
    init(widgetHex hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
