import SwiftUI

/// docs/09 "Tokens", for the complication — which links no RecKit and no core (docs/13: the watch has
/// a 75 MB budget and would otherwise carry the whole database for the sake of one status line).
///
/// A watch face tints its own accessory widgets, so the complication draws one colour only: the
/// recording mark's red, which a full-colour face shows and a tinted face replaces with its accent
/// (2026-10-11, N4). The rest is measurement — the glyph, at the same 19pt `BlueprintIcon` uses
/// everywhere else, and the two corner radii.
enum WidgetTokens {
    /// `BlueprintIcon`'s own default size.
    static let iconSize: CGFloat = 19
    /// `BlueprintPalette.light.danger` — the recording mark, as the phone's widgets draw it.
    static let danger = Color(widgetHex: 0xDA1E28)

    /// docs/09 "Shape": 4 for a node, 2 for a badge.
    enum Radius {
        static let node: CGFloat = 4
        static let badge: CGFloat = 2
    }
}

extension Color {
    /// The phone widgets' own initializer: this extension cannot see that target's copy.
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
