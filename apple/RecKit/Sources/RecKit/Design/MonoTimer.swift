import SwiftUI

/// docs/09 "Typography": the timer is the one piece of data big enough to be the screen.
public struct MonoTimer: View {
    @Environment(\.blueprint) private var blueprint
    private let text: String
    private let color: Color?

    public init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    public var body: some View {
        Text(verbatim: text)
            // docs/09 "Typography" (2026-10-15): mono at regular weight, with 1 of tracking.
            .font(blueprint.fonts.monoTimer)
            .tracking(timerTracking)
            .foregroundStyle(color ?? blueprint.palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
    }
}

/// A 1pt rule (2pt in high contrast) — the only divider this design has.
public struct HairLine: View {
    @Environment(\.blueprint) private var blueprint

    public init() {}

    public var body: some View {
        Rectangle()
            .fill(blueprint.palette.grid)
            .frame(height: blueprint.line)
            .accessibilityHidden(true)
    }
}
