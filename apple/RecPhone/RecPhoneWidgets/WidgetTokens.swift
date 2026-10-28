import SwiftUI

/// docs/09 "Tokens", for the two extensions that cannot import RecKit.
///
/// A widget extension links no core (docs/13: it would carry the whole database with it for the
/// sake of one status line, and the watch has a 75 MB budget to keep), and RecKit is where the
/// palette lives — so the handful of values the Live Activity and the widget actually draw are
/// written here as the same sRGB hexes `BlueprintPalette` holds. A few numbers, not a palette: what an
/// extension draws is a mark, a rule and a line of words.
///
/// Every ground here is dark — the Lock Screen activity's page black, the Dynamic Island, the widget's
/// own container — so every copy is the **dark** palette's (docs/09 "Tokens", 2026-10-15: the light red
/// was 3.8:1 on the activity's ground). There is no light ground in this extension and so no light value.
///
/// `BlueprintTokenTests` checks these against the palette itself, so a token that moves in docs/09
/// cannot leave the widgets behind.
enum WidgetTokens {
    /// `BlueprintPalette.dark.danger` — the recording mark and Stop, everywhere they appear.
    static let danger = Color(widgetHex: 0xFA4D56)
    /// `BlueprintPalette.dark.text` — the words and the clock.
    static let text = Color(widgetHex: 0xF2F2F0)
    /// `BlueprintPalette.dark.background` — the ground, and the stop square on the danger fill.
    static let background = Color(widgetHex: 0x0E0F12)

    /// docs/09 "Shape": 4 for a node, 2 for a badge.
    enum Radius {
        static let node: CGFloat = 4
        static let badge: CGFloat = 2
    }

    /// docs/09 "Accessibility": whatever it draws, nothing you can tap is smaller than this.
    static let minTouch: CGFloat = 44

    /// docs/09 "Typography": the scale's sizes this extension draws — the clock 20 where it has a line of its own, the
    /// words 14, and 12 in a compact slot (2026-10-28).
    enum TypeSize {
        static let small: CGFloat = 12
        static let bodySmall: CGFloat = 14
        static let title: CGFloat = 20
    }

    /// `BlueprintIcon`'s own default size — a glyph, as the watch complication draws its glyphs.
    static let iconSize: CGFloat = 19
}

/// docs/09 "Typography": one of the scale's sizes, in the sans or the mono, grown with the user's text size by the factor
/// `BlueprintRoot` measures for the app — not a system text style, whose own sizes (15, 17, 22) are not the scale's
/// (2026-10-28).
private struct WidgetFont: ViewModifier {
    @ScaledMetric(relativeTo: .body) private var unit: CGFloat = 100
    let size: CGFloat
    let weight: Font.Weight
    let design: Font.Design

    func body(content: Content) -> some View {
        content.font(.system(size: size * unit / 100, weight: weight, design: design))
    }
}

extension View {
    func widgetFont(_ size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default) -> some View {
        modifier(WidgetFont(size: size, weight: weight, design: design))
    }
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
