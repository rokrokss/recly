import SwiftUI

/// The one loader this design has: an 8pt square outline turning beside a value, for work that is
/// running with no percentage to show for it — the state node's `UPLOADING`, a model download, a
/// recording coming back from Drive.
///
/// docs/09 "Motion": motion is a state signal. Straight edges, no rounding and no fade — the square is
/// the same shape everything else on the screen is. With reduce motion on it is not drawn: the words
/// beside it are the whole message either way.
///
/// The turn is a keyframe animator over the rotation alone. It was `withAnimation(.repeatForever)` in `onAppear`, and
/// a loader appearing in the same update as a layout change — the Mac's list after its window was resized — lent its
/// endless animation to every frame that moved with it: the list shook until the app quit (2026-11-04).
struct BlueprintLoader: View {
    /// One full turn, slow enough to read as "still working" rather than "hurry".
    private static let turn: Double = 1.2

    @Environment(\.blueprint) private var blueprint
    let color: Color

    var body: some View {
        if !blueprint.reduceMotion {
            Rectangle()
                .strokeBorder(color, lineWidth: blueprint.line)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
                .keyframeAnimator(initialValue: 0.0, repeating: true) { content, angle in
                    content.rotationEffect(.degrees(angle))
                } keyframes: { _ in
                    LinearKeyframe(360.0, duration: Self.turn)
                }
        }
    }
}

/// A sentence about work in progress, with the loader beside it in the sentence's own colour.
public struct LoadingText: View {
    let text: String
    let font: Font
    let color: Color

    public init(text: String, font: Font, color: Color) {
        self.text = text
        self.font = font
        self.color = color
    }

    public var body: some View {
        HStack(spacing: Space.xs) {
            BlueprintLoader(color: color)
            Text(verbatim: text).font(font).foregroundStyle(color)
        }
    }
}
