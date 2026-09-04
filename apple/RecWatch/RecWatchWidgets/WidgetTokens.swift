import SwiftUI

/// docs/09 "토큰", for the complication — which links no RecKit and no core (docs/13: the watch has
/// a 75 MB budget and would otherwise carry the whole database for the sake of one status line).
///
/// The complication draws no colour of its own: a watch face tints its own accessory widgets, and a
/// hard-coded ink there is one the face cannot honour. What is left is the one measurement — the
/// glyph, at the same 19pt `BlueprintIcon` uses everywhere else.
enum WidgetTokens {
    /// `BlueprintIcon`'s own default size.
    static let iconSize: CGFloat = 19
}
