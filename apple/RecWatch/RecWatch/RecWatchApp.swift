import AppIntents
import os
import RecKit
import SwiftUI
import WatchKit

/// docs/13 WA1~WA6: the watch shell, a companion of `RecPhone` and embedded in it. It records on
/// RecKit's `SegmentedRecorder` and hands every part to the phone over `WCSession`; there is no
/// sign-in and no network here, ever (ADR-002).
@main
struct RecWatchApp: App {
    /// The one model, built as the app is. Touching it here is also what registers it with the
    /// intents: an app launched by the action button or the complication has to find a model that is
    /// opening the core, not nothing.
    @StateObject private var model = WatchRecordingModel.shared

    /// docs/07 rule 2: the watch has no language picker — it follows the phone's choice, which
    /// arrives on the application context. Observing it here is what redraws the screen when it does.
    @StateObject private var language = AppLanguage.shared
    var body: some Scene {
        WindowGroup {
            RecordingView(model: model)
                .environment(\.locale, language.locale)
                .blueprint()
        }
    }
}

/// docs/13 "Apple Watch" 진입점: the Ultra action button runs an App Shortcut, and this is the app's
/// list of them. It has to live in the app target rather than in the extension — the system reads it
/// from the app.
struct RecWatchShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartWatchRecordingIntent(),
            // docs/07: English here and translated in `AppShortcuts.xcstrings`.
            phrases: ["Start recording with \(.applicationName)"],
            shortTitle: "Start recording",
            // docs/09 "형태": a square, as the record node and the mark are everywhere else.
            systemImageName: "smallcircle.filled.square"
        )
        AppShortcut(
            intent: StopWatchRecordingIntent(),
            phrases: ["Stop recording with \(.applicationName)"],
            shortTitle: "Stop recording",
            systemImageName: "stop.fill"
        )
    }
}
