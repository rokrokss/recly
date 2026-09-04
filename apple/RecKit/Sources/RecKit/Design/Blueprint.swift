import SwiftUI

/// docs/09, as one environment value: the palette the four variants resolved to, the type scale
/// Dynamic Type asked for, and whether motion is a state change or an animation.
///
/// Read as `@Environment(\.blueprint)`, which every component in this module does.
public struct Blueprint: Equatable, Sendable {
    public let palette: BlueprintPalette
    public let fonts: BlueprintFonts
    /// True when the system says so (docs/09 "접근성").
    public let reduceMotion: Bool

    public init(
        palette: BlueprintPalette = .light,
        fonts: BlueprintFonts = BlueprintFonts(),
        reduceMotion: Bool = false
    ) {
        self.palette = palette
        self.fonts = fonts
        self.reduceMotion = reduceMotion
    }

    /// Sugar for the two that are read on nearly every line.
    public var line: CGFloat { palette.line }
}

private struct BlueprintKey: EnvironmentKey {
    static let defaultValue = Blueprint()
}

extension EnvironmentValues {
    public var blueprint: Blueprint {
        get { self[BlueprintKey.self] }
        set { self[BlueprintKey.self] = newValue }
    }
}

/// docs/09 "접근성": the system's colour scheme, reduce-motion switch, contrast setting and font
/// size are read from the system, and nothing below has to ask again.
///
/// The Dynamic Type ramp is measured once, here: `@ScaledMetric` over a hundred points gives the
/// factor the user's size setting asks for, and every font in [BlueprintFonts] is that factor times
/// one of the six sizes docs/09 names.
public struct BlueprintRoot: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    /// iOS "Increase Contrast" / macOS "Increase contrast", read for the same reason
    /// [systemReduceMotion] is: a user who has already told the system is answered by the app
    /// without being asked again (docs/09 "접근성").
    @Environment(\.colorSchemeContrast) private var systemContrast
    @ScaledMetric(relativeTo: .body) private var unit: CGFloat = 100

    public init() {}

    public func body(content: Content) -> some View {
        content
            .environment(
                \.blueprint,
                Blueprint(
                    palette: .palette(
                        dark: colorScheme == .dark,
                        highContrast: systemContrast == .increased
                    ),
                    fonts: BlueprintFonts(scale: unit / 100),
                    reduceMotion: systemReduceMotion
                )
            )
    }
}

extension View {
    /// The one line each app's root scene needs.
    public func blueprint() -> some View {
        modifier(BlueprintRoot())
    }
}
