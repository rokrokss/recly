import SwiftUI

/// The one loader this design has: an 8pt square outline turning beside a value, for work that is
/// running with no percentage to show for it — the state node's `UPLOADING`, a model download, a
/// recording coming back from Drive.
///
/// docs/09 "모션": motion is a state signal. Straight edges, no rounding and no fade — the square is
/// the same shape everything else on the screen is. With reduce motion on it is not drawn: the words
/// beside it are the whole message either way.
struct BlueprintLoader: View {
    /// One full turn, slow enough to read as "still working" rather than "hurry".
    private static let turn: Double = 1.2

    @Environment(\.blueprint) private var blueprint
    @State private var angle: Double = 0
    let color: Color

    var body: some View {
        if !blueprint.reduceMotion {
            Rectangle()
                .strokeBorder(color, lineWidth: blueprint.line)
                .frame(width: 8, height: 8)
                .rotationEffect(.degrees(angle))
                .onAppear {
                    withAnimation(.linear(duration: Self.turn).repeatForever(autoreverses: false)) {
                        angle = 360
                    }
                }
                .accessibilityHidden(true)
        }
    }
}

/// A sentence about work in progress, with the loader beside it in the sentence's own colour.
struct LoadingText: View {
    let text: String
    let font: Font
    let color: Color

    var body: some View {
        HStack(spacing: Space.xs) {
            BlueprintLoader(color: color)
            Text(verbatim: text).font(font).foregroundStyle(color)
        }
    }
}
