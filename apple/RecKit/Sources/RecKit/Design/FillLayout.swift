import SwiftUI

/// A row of choice chips that fills its line — the theme and the transcription choices of the
/// phone's settings, whose chips otherwise sat at the start of an empty line while every other
/// control on the screen ends at its trailing edge. The Android shell's `FillRow` is the same rule.
///
/// The chips are the same width when they all fit that way; when one label is too long for an even
/// split (`System default`), each keeps its own width plus an equal share of what is left, so no chip
/// is ever narrower than it would be on its own. A line that does not hold them all wraps as
/// [FlowLayout] does, and every line fills. The chips have to accept the width they are given —
/// [BlueprintChip]'s `fill`.
public struct FillLayout: Layout {
    private let spacing: CGFloat

    public init(spacing: CGFloat = Space.s) {
        self.spacing = spacing
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let natural = naturalSizes(subviews)
        let width = proposal.width ?? natural.map(\.width).reduce(0, +) + spacing * CGFloat(max(natural.count - 1, 0))
        let heights = lineHeights(fillLines(natural.map(\.width), width: width, spacing: spacing), subviews)
        return CGSize(width: width, height: heights.reduce(0, +) + spacing * CGFloat(max(heights.count - 1, 0)))
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let lines = fillLines(naturalSizes(subviews).map(\.width), width: bounds.width, spacing: spacing)
        let heights = lineHeights(lines, subviews)
        var index = 0
        var y = bounds.minY
        for (line, height) in zip(lines, heights) {
            var x = bounds.minX
            for width in line {
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: width, height: height))
                x += width + spacing
                index += 1
            }
            y += height + spacing
        }
    }

    private func naturalSizes(_ subviews: Subviews) -> [CGSize] {
        subviews.map { $0.sizeThatFits(.unspecified) }
    }

    /// One height per line, so a chip whose label wrapped does not stand taller than its neighbours.
    private func lineHeights(_ lines: [[CGFloat]], _ subviews: Subviews) -> [CGFloat] {
        var index = 0
        return lines.map { line in
            line.map { width in
                defer { index += 1 }
                return subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil)).height
            }.max() ?? 0
        }
    }
}

/// The widths [FillLayout] gives its chips, line by line: a greedy wrap on their [natural] widths,
/// then each line filled to [width].
func fillLines(_ natural: [CGFloat], width: CGFloat, spacing: CGFloat) -> [[CGFloat]] {
    var lines: [[CGFloat]] = []
    var used: CGFloat = 0
    for chip in natural.map({ min($0, width) }) {
        if lines.isEmpty || used + spacing + chip > width {
            lines.append([chip])
            used = chip
        } else {
            lines[lines.count - 1].append(chip)
            used += spacing + chip
        }
    }
    return lines.map { line in
        let count = CGFloat(line.count)
        let room = width - spacing * (count - 1)
        guard let widest = line.max(), widest * count > room else {
            return Array(repeating: room / count, count: line.count)
        }
        let share = (room - line.reduce(0, +)) / count
        return line.map { $0 + share }
    }
}

#if os(iOS)
/// The row a settings choice's chips sit in: filled on the phone, where every other control of the
/// screen ends at its trailing edge; a plain [FlowLayout] in a desktop window, where a filled row
/// would stretch three words across the whole panel.
public typealias ChoiceRow = FillLayout
#else
public typealias ChoiceRow = FlowLayout
#endif
