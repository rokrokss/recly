import AppKit
import RecKit
import SwiftUI

/// A modal panel drawn the way the rest of the app is drawn (docs/09), for the two places a
/// `LSUIElement` menu-bar app has to *ask* rather than show: it has no window to hang a SwiftUI
/// sheet off, and `NSAlert` brings the platform's own shape, its own material and its own buttons.
///
/// The shape is `NSAlert`'s, deliberately: [run] is synchronous and application-modal, because the
/// callers are too — the title prompt sits inside `TerminationGate`, which holds a `⌘Q` until the
/// stop has finished, and turning it into an `await` on a sheet would leave the quit waiting on a
/// window that has nowhere to be presented from.
@MainActor
enum BlueprintPanel {

    /// Runs [content] as an application-modal panel and hands back whatever it passed to the
    /// `finish` closure it was given — or nil if the panel was ended without one, which is the
    /// same answer a dismissed `NSAlert` gives.
    static func run<Result, Content: View>(
        width: CGFloat = 420,
        @ViewBuilder content: @escaping (@escaping (Result?) -> Void) -> Content
    ) -> Result? {
        var answer: Result?
        // No `.closable`: the panel is a question, and its own buttons are how it is answered.
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: width, height: 100),
            styleMask: [.titled, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        let view = NSHostingView(
            rootView: content { result in
                answer = result
                NSApp.stopModal()
            }
            .environment(\.locale, AppLanguage.locale)
            .blueprint()
            .frame(width: width)
        )
        // The panel is as tall as its question: a `BlueprintDialog` sizes itself to its content and
        // the window follows, rather than the content being squeezed into a guessed height.
        view.setFrameSize(view.fittingSize)
        window.contentView = view
        window.setContentSize(view.fittingSize)
        window.center()
        // `LSUIElement` means the app is not frontmost when the menu closes; without this the panel
        // opens behind whatever the user was doing.
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        NSApp.runModal(for: window)
        window.orderOut(nil)
        return answer
    }
}
