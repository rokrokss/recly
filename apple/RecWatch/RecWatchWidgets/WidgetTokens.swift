import SwiftUI

/// docs/09 "Tokens", for the complication — which links no RecKit and no core (docs/13: the watch has
/// a 75 MB budget and would otherwise carry the whole database for the sake of one status line).
///
/// A watch face tints its own accessory widgets, so the complication draws one colour only: the
/// recording mark's red, which a full-colour face shows and a tinted face replaces with its accent
/// (2026-10-11, N4). The rest is measurement — the glyph, at the same 19pt `BlueprintIcon` uses
/// everywhere else, the two corner radii, and the type sizes.
enum WidgetTokens {
    /// `BlueprintIcon`'s own default size.
    static let iconSize: CGFloat = 19
    /// `BlueprintPalette.dark.danger` — the recording mark, as the phone's widgets draw it. A watch face is a
    /// dark ground, so the copy is the dark palette's (docs/09 "Tokens", 2026-10-15).
    static let danger = Color(widgetHex: 0xFA4D56)

    /// docs/09 "Shape": 4 for a node, 2 for a badge.
    enum Radius {
        static let node: CGFloat = 4
        static let badge: CGFloat = 2
    }

    /// docs/09 "Typography": the Apple Watch's own — the timer 20 and a status word 14 where the face gives the line
    /// room, and 12 on a curved or circular label (2026-10-28).
    enum TypeSize {
        static let small: CGFloat = 12
        static let bodySmall: CGFloat = 14
        static let title: CGFloat = 20
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
