import AppKit
import Combine
import RecKit
import SwiftUI

/// The status item and the popover under it, in AppKit rather than `MenuBarExtra`.
///
/// `MenuBarExtra`'s `.window` style closes on any mouse-down outside itself — including one on this
/// app's *own* windows. Opening the details window or the title prompt from the popover leaves it in
/// place, but the first click in that window (its Save, its close button) takes the popover with it,
/// and the user is back to the menu bar to reopen the app they were just in. A popover of this
/// app's own makes the distinction SwiftUI's does not: a click in another app closes it, a click
/// in one of this app's windows does not, and the status item and Escape close it either way.
///
/// The panel is non-activating and can become key, so the settings fields take typing without the
/// app being brought to the front, and it floats at the pop-up-menu level the way the SwiftUI one
/// did. It sizes itself to the popover's content and keeps its top edge under the status item
/// while it does, so the settings pane grows downward from the menu bar rather than up into it.
@MainActor
final class MenuBarPanel {
    private static let gap: CGFloat = 4

    private let model: MenuModel
    private let item: NSStatusItem
    private let panel: Panel
    private let hosting: Hosting
    private var fitScheduled = false
    private var monitors: [Any] = []
    private var iconChanges: AnyCancellable?
    /// Where the panel's top-left corner goes: under the status item, remembered from the show so
    /// a resize of the content can put the corner back rather than growing the panel upward.
    private var anchor = NSPoint.zero

    init(model: MenuModel, language: AppLanguage, theme: AppTheme) {
        self.model = model

        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = model.icon

        let panel = Panel(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 300),
            styleMask: [.nonactivatingPanel, .borderless, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        self.panel = panel
        panel.level = .popUpMenu
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        // An `NSPanel` hides itself when the app deactivates by default, and the app deactivates
        // when the title prompt or the details window closes as its last window — the very moment
        // this panel is meant to still be there.
        panel.hidesOnDeactivate = false
        panel.animationBehavior = .none
        panel.hasShadow = true
        panel.backgroundColor = .clear
        panel.isOpaque = false

        // The panel is sized here, from the ideal size the hosting view reports as its intrinsic
        // size — not by SwiftUI following a hosting controller's `preferredContentSize`. That
        // following (`NSHostingView.windowDidLayout` → `updateAnimatedWindowSize`) sets the window's
        // frame with a display, the display lays the window out, the layout posts `didLayout`, and
        // that is the notification it was answering: on macOS 26 the popover's change to its
        // recording state — the timer and the strip appearing — recursed there until the stack ran
        // out (2026-09-04). A resize of this panel's own is asked for outside the layout pass that
        // invalidated the size, so nothing it does can land back in the pass that asked for it.
        // Escape closes the panel the way a click elsewhere does. The panel and not `self`: no
        // closure may hold `self` until every property is set, and this one is what sets `hosting`.
        hosting = Hosting(
            rootView: Root(model: model, language: language, theme: theme) { [weak panel] in
                panel?.orderOut(nil)
            }
        )
        hosting.sizingOptions = [.intrinsicContentSize]
        hosting.wantsLayer = true
        hosting.layer?.cornerRadius = 10
        hosting.layer?.masksToBounds = true
        panel.contentView = hosting
        hosting.idealSizeChanged = { [weak self] in self?.scheduleFit() }

        item.button?.target = self
        item.button?.action = #selector(toggle)

        // Clicks in other apps close it. A global monitor sees only events sent to other
        // applications, which is exactly the line: a click on this app's own windows never arrives
        // here, and the popover stays.
        monitors.append(NSEvent.addGlobalMonitorForEvents(
            matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        ) { [weak self] _ in
            Task { @MainActor in self?.hide() }
        } as Any)

        // `MenuBarExtra` redrew its label on every change of the model and of the language — the
        // icon's accessibility description carries a localized alert reason — and the button's
        // image follows the same way. `objectWillChange` fires before the change, so the read is
        // deferred a turn.
        iconChanges = model.objectWillChange
            .merge(with: language.objectWillChange)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.item.button?.image = self.model.icon
            }
    }

    @objc private func toggle() {
        if panel.isVisible { hide() } else { show() }
    }

    private func show() {
        guard let button = item.button, let bar = button.window else { return }
        let rect = bar.convertToScreen(button.convert(button.bounds, to: nil))
        guard let screen = NSScreen.screens.first(where: { $0.frame.intersects(rect) }) ?? NSScreen.main else { return }
        // A status item pushed into the menu bar's overflow reports a position off any screen; the
        // panel then opens at the screen's own top-right corner rather than where nobody can see it.
        let visible = screen.visibleFrame
        let onScreen = screen.frame.intersects(rect)
        let right = onScreen ? rect.maxX : visible.maxX
        let top = onScreen ? rect.minY : visible.maxY
        let width = panel.frame.width
        anchor = NSPoint(
            x: max(visible.minX, min(right - width, visible.maxX - width)),
            y: top - Self.gap
        )
        fit()
        panel.makeKeyAndOrderFront(nil)
        // docs/03: the popover coming up is when this Mac last looked, so it asks Drive what the
        // other devices have uploaded since. Beside the ledger and never in front of it — the rows
        // a pull adopts arrive on the model's own recordings observation.
        Task { await model.pullRemoteRecordings() }
    }

    /// One fit per turn of the run loop, however many invalidations a single update makes.
    private func scheduleFit() {
        guard !fitScheduled else { return }
        fitScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.fitScheduled = false
            self.fit()
        }
    }

    /// The panel takes the popover's ideal size and keeps its top-left corner where the show put
    /// it, so the settings pane grows downward from the menu bar rather than up into it.
    private func fit() {
        let size = hosting.intrinsicContentSize
        guard size.width > 0, size.height > 0 else { return }
        if panel.frame.size != size { panel.setContentSize(size) }
        panel.setFrameTopLeftPoint(anchor)
    }

    private func hide() {
        guard panel.isVisible else { return }
        panel.orderOut(nil)
    }

    /// A borderless window cannot become key unless it says so, and the settings fields need it to.
    private final class Panel: NSPanel {
        override var canBecomeKey: Bool { true }
        override var canBecomeMain: Bool { false }
    }

    /// The hosting view, saying when the popover's ideal size has changed — which is the one thing
    /// AppKit's `invalidateIntrinsicContentSize` is called for, and the moment the panel has to
    /// take a new size.
    private final class Hosting: NSHostingView<Root> {
        var idealSizeChanged: (() -> Void)?

        override func invalidateIntrinsicContentSize() {
            super.invalidateIntrinsicContentSize()
            idealSizeChanged?()
        }
    }

    /// The popover's root, observing the model and the language so that a change of either redraws
    /// the tree — the hosting view's `rootView` is set once, and this is what keeps it live.
    private struct Root: View {
        @ObservedObject var model: MenuModel
        @ObservedObject var language: AppLanguage
        @ObservedObject var theme: AppTheme
        let dismiss: () -> Void

        var body: some View {
            MenuPopover(model: model, language: language, theme: theme)
                .environment(\.locale, language.locale)
                .blueprint()
                // Escape, the way the SwiftUI popover closed on it.
                .onExitCommand(perform: dismiss)
        }
    }
}
