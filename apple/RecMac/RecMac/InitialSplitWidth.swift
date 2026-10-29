import AppKit
import SwiftUI

/// The pane this view is behind opens [width] wide in its `HSplitView`, and is the user's to drag after that.
///
/// `HSplitView` ignores its panes' ideal widths and halves the window (checked 2026-10-29 on macOS 27), so the first
/// divider is set once on the split view AppKit draws, when the pane first lands in a window. The pane must be the
/// split's first; `setPosition` measures from its side, so a right-to-left layout opens it the same width.
struct InitialSplitWidth: NSViewRepresentable {
    let width: CGFloat

    func makeNSView(context: Context) -> NSView { PositionView(width: width) }

    func updateNSView(_ nsView: NSView, context: Context) {}

    private final class PositionView: NSView {
        let width: CGFloat
        private var placed = false

        init(width: CGFloat) {
            self.width = width
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard !placed, window != nil else { return }
            // After this pass, once the split view has laid its panes out at their first sizes.
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.placed else { return }
                var view = superview
                while let candidate = view, !(candidate is NSSplitView) { view = candidate.superview }
                guard let split = view as? NSSplitView else { return }
                split.setPosition(width, ofDividerAt: 0)
                placed = true
            }
        }
    }
}
