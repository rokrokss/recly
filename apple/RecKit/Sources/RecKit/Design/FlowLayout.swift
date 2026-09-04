import SwiftUI

/// A row of chips that turns into several rows when one is not enough — the same thing Compose's
/// `FlowRow` does for the Android shell.
///
/// docs/09 유동 타이포 makes the label size the user's, so a row of provider names, language tags or
/// secret names is only a row at the size it was drawn for. An `HStack` answers that by squeezing
/// its children below [minTouch] and then clipping their letters; this answers it by wrapping,
/// which is the only arrangement that keeps every chip its own size.
public struct FlowLayout: Layout {
    private let spacing: CGFloat

    public init(spacing: CGFloat = Space.s) {
        self.spacing = spacing
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = wrap(subviews.map { $0.sizeThatFits(.unspecified) }, into: width)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        // The widest row rather than the proposal: a single chip in a wide inspector must not
        // claim the whole width, or the switch beside it is pushed off.
        return CGSize(width: rows.map(\.width).max() ?? 0, height: height)
    }

    public func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        var index = 0
        var y = bounds.minY
        for row in wrap(sizes, into: bounds.width) {
            var x = bounds.minX
            for _ in 0 ..< row.count {
                let size = sizes[index]
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
                index += 1
            }
            y += row.height + spacing
        }
    }

    /// One line of the wrap: how many chips are on it, and how big it is.
    struct Row {
        var count = 0
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    /// The greedy wrap: a chip goes on the current line while it fits, and starts a new one when it
    /// does not. A chip wider than the whole width still gets its own line rather than none.
    func wrap(_ sizes: [CGSize], into width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for size in sizes {
            let next = row.count == 0 ? size.width : row.width + spacing + size.width
            if row.count > 0, next > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.count == 0 ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.count += 1
        }
        if row.count > 0 { rows.append(row) }
        return rows
    }
}
