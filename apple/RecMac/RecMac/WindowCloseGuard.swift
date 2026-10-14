import AppKit
import SwiftUI

/// The window this view is in asks [shouldClose] before it closes — its close button, ⌘W, `performClose` — and stays
/// open on false (docs/09 "Editing and speakers": leaving an editor with changes asks, by every route, and closing the
/// Details window is one).
///
/// SwiftUI owns the window's delegate, so the guard does not replace it: it stands in front of it, answers
/// `windowShouldClose` itself, and hands every other message to SwiftUI's delegate as it was.
struct WindowCloseGuard: NSViewRepresentable {
    let shouldClose: @MainActor () -> Bool

    func makeNSView(context: Context) -> NSView {
        let view = GuardView()
        view.guardian.shouldClose = shouldClose
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        (nsView as? GuardView)?.guardian.shouldClose = shouldClose
    }

    private final class GuardView: NSView {
        let guardian = CloseGuardDelegate()

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window, window.delegate !== guardian else { return }
            guardian.original = window.delegate
            window.delegate = guardian
        }
    }
}

/// The stand-in delegate: `windowShouldClose` is its own, everything else is forwarded to the delegate it replaced.
/// The view that installed it holds it — a window does not retain its delegate.
private final class CloseGuardDelegate: NSObject, NSWindowDelegate {
    weak var original: NSWindowDelegate?
    var shouldClose: @MainActor () -> Bool = { true }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard MainActor.assumeIsolated({ shouldClose() }) else { return false }
        return original?.windowShouldClose?(sender) ?? true
    }

    override func responds(to selector: Selector!) -> Bool {
        super.responds(to: selector) || original?.responds(to: selector) == true
    }

    override func forwardingTarget(for selector: Selector!) -> Any? {
        original?.responds(to: selector) == true ? original : super.forwardingTarget(for: selector)
    }
}
