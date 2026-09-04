import AppKit
import RecKit
import SwiftUI

/// docs/12: the menu bar shell. `LSUIElement` is on, so there is no Dock icon and no window —
/// the status item's popover ([MenuBarPanel]) is the whole UI. M4-L2 adds start/stop; the
/// recent-recordings list, the workflow editor and sign-in follow in M4-L3 onwards.
@main
struct RecMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = MenuModel.shared
    /// docs/07 rule 3: observed here so that picking a language redraws the popover and hands the
    /// window its new locale, with no relaunch.
    @StateObject private var language = AppLanguage.shared
    /// docs/10: the notification delegate, before anything else this shell does.
    ///
    /// A job alert or a meeting offer is often opened from a *cold* launch, and macOS delivers the
    /// response as soon as the launch finishes — which is before the first `body` runs, and
    /// `@StateObject` builds its initial value there. A delegate installed any later is one
    /// installed after the tap was dropped. What arrives before the model can answer it is buffered
    /// inside the model.
    init() {
        MenuModel.shared.installNotificationDelegate()
    }

    var body: some Scene {
        // docs/09 화면 원칙 6: a popover rather than an `NSMenu`, because the design is three state
        // nodes over a ledger and an `NSMenu` can draw neither. The popover itself is
        // [MenuBarPanel], made by the delegate: SwiftUI's `MenuBarExtra` closes on a click in this
        // app's own windows, which is the one thing it must not do. This one is never inserted —
        // it is here so that the first scene is not a `Window`, which SwiftUI would open at launch.
        MenuBarExtra("Recly", isInserted: .constant(false)) {
            EmptyView()
        }

        // docs/12 M7: the editor is a window of its own, opened from the menu. `LSUIElement` keeps
        // it out of the Dock; it is simply closed when the user is done with it.
        // docs/07 rule 3: the window's *contents* are SwiftUI and follow `\.locale` below without a
        // relaunch, but its title is not inside that subtree — it is resolved by the scene, against
        // the app's environment, where the modifier never reaches. So it is looked up explicitly in
        // the app's language, from a body that observes [AppLanguage]: picking a language rebuilds
        // this scene with the new title.
        Window(AppStrings.localized("Workflows"), id: WorkflowWindow.id) {
            WorkflowWindow(menu: model)
                .environment(\.locale, language.locale)
                .blueprint()
        }
        // The editor's node graph runs sideways and the list rows carry three controls: opened at
        // its minimum the window is all edges. This is only the first size — macOS keeps whatever
        // the user drags it to.
        .defaultSize(width: 960, height: 640)

        // docs/08 "결과 파일": what is behind a recording — the audio, the transcript — which does
        // not fit in a popover. Its title is resolved the same way the editor's is, and for the
        // same reason.
        Window(AppStrings.localized("Details"), id: RecordingsWindow.id) {
            RecordingsWindow(menu: model)
                .environment(\.locale, language.locale)
                .blueprint()
        }
    }
}

/// The status item and its popover, and the one thing the shell has to answer itself: whether the
/// app may go. Everything else about the quit is [MenuModel.terminate] — this is only the wire.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var menuBar: MenuBarPanel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        MainActor.assumeIsolated {
            // docs/09 "접근성": the stored override of the system's light/dark, written onto the
            // application before anything of it is drawn — the popover, the editor and the details
            // window are three AppKit windows and all of them inherit it.
            AppTheme.shared.apply()
            menuBar = MenuBarPanel(
                model: MenuModel.shared,
                language: AppLanguage.shared,
                theme: AppTheme.shared
            )
        }
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        MainActor.assumeIsolated { MenuModel.shared.terminate() }
    }

    /// With no `MenuBarExtra` inserted, SwiftUI would otherwise quit the app when the details or
    /// the workflows window closes as the last one open. A menu bar app outlives its windows.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    /// docs/06: the consent web view comes back on the reversed-client-id scheme, and GoogleSignIn
    /// asks every macOS app to hand that URL over (the guide's `kAEGetURL` step; this is its AppKit
    /// form). The phone does the same with `.onOpenURL`.
    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls {
            if GoogleAuth.handle(url) { return }
        }
    }
}
