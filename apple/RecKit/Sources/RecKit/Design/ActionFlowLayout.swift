import SwiftUI

/// docs/09 screen principle 2: an expanded ledger row's buttons, across the row's whole width and onto more
/// lines when they do not fit, with the last one — the row's Delete, when [trailingLast] — at the end
/// of the last line. It used to stand apart under the status badge, and whatever it took from the
/// row the other buttons lost (2026-09-29). The Android shell's `ActionFlow` is the same rule.
public struct ActionFlowLayout: Layout {
    private let spacing: CGFloat
    private let trailingLast: Bool

    public init(spacing: CGFloat = Space.s, trailingLast: Bool) {
        self.spacing = spacing
        self.trailingLast = trailingLast
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let width = proposal.width ?? sizes.map(\.width).reduce(0, +) + spacing * CGFloat(max(sizes.count - 1, 0))
        let spots = actionFlow(sizes.map(\.width), width: width, spacing: spacing, trailingLast: trailingLast)
        let heights = lineHeights(spots, sizes)
        return CGSize(width: width, height: heights.reduce(0, +) + spacing * CGFloat(max(heights.count - 1, 0)))
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let spots = actionFlow(sizes.map(\.width), width: bounds.width, spacing: spacing, trailingLast: trailingLast)
        let heights = lineHeights(spots, sizes)
        var tops: [CGFloat] = []
        var y = bounds.minY
        for height in heights {
            tops.append(y)
            y += height + spacing
        }
        for (index, spot) in spots.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + spot.x, y: tops[spot.line]),
                proposal: ProposedViewSize(width: min(sizes[index].width, bounds.width), height: nil)
            )
        }
    }

    private func lineHeights(_ spots: [ActionSpot], _ sizes: [CGSize]) -> [CGFloat] {
        var heights = [CGFloat](repeating: 0, count: (spots.map(\.line).max() ?? -1) + 1)
        for (index, spot) in spots.enumerated() { heights[spot.line] = max(heights[spot.line], sizes[index].height) }
        return heights
    }
}

/// Where one button of an [ActionFlowLayout] goes: its leading edge and which line it is on.
struct ActionSpot: Equatable {
    let x: CGFloat
    let line: Int
}

/// The greedy wrap of [widths] into lines of [width]; with [trailingLast] the last one is instead put
/// at the end of the last line when it fits there, and at the end of a line of its own when it does
/// not.
func actionFlow(_ widths: [CGFloat], width: CGFloat, spacing: CGFloat, trailingLast: Bool) -> [ActionSpot] {
    var spots: [ActionSpot] = []
    var line = 0
    var used: CGFloat = 0
    for w in trailingLast ? Array(widths.dropLast()) : widths {
        if used > 0, used + spacing + w > width {
            line += 1
            used = 0
        }
        spots.append(ActionSpot(x: used == 0 ? 0 : used + spacing, line: line))
        used = used == 0 ? w : used + spacing + w
    }
    if trailingLast, let w = widths.last {
        if used > 0, used + spacing + w > width { line += 1 }
        spots.append(ActionSpot(x: max(width - w, 0), line: line))
    }
    return spots
}
