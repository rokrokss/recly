import RecKit
import SwiftUI
import UIKit

/// docs/13: the iPhone shell — record, list, edit workflows, settings (M5-L2 and M5-L3).
@main
struct RecPhoneApp: App {
    /// Only for the two things a SwiftUI `App` cannot be handed: the background-task registration
    /// that has to happen inside `didFinishLaunching`, and the upload session's relaunch.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    /// The one model, built as the app is (`@StateObject` would build a second one for the scene).
    /// Touching it here is also what registers it with the intents: an app launched into the
    /// background by Siri has to find a model that is opening the core, not nothing.
    @StateObject private var model = RecordingModel.shared
    /// docs/07 rule 3: the one place the app's language reaches the screen. Observing it here is
    /// what redraws every tab when the setting changes — no relaunch, no scene rebuild.
    @StateObject private var language = AppLanguage.shared
    /// docs/09 "접근성": the one override of the system's light/dark. Observed here because the
    /// setting reaches the screen as this root's `.preferredColorScheme`, which is what every
    /// palette below reads back as `\.colorScheme`.
    @StateObject private var theme = AppTheme.shared
    /// docs/10: the notification delegate, before anything else this shell does.
    ///
    /// A job alert is most often opened from a *cold* launch, and iOS delivers the response as soon
    /// as the launch finishes — which is before the first `body` runs, and `@StateObject` builds
    /// its initial value there. A delegate installed any later is one installed after the tap was
    /// dropped. What arrives before the model can answer it is buffered inside the model.
    init() {
        RecordingModel.shared.installNotificationDelegate()
    }

    var body: some Scene {
        WindowGroup {
            RootTabs(model: model, language: language, theme: theme)
                .environment(\.locale, language.locale)
                // docs/09 "접근성": nil is no preference of the app's own, which is the device's
                // scheme — the palette follows `\.colorScheme` either way.
                .preferredColorScheme(theme.choice.colorScheme)
                // docs/09: the tokens, the type scale and the reduce-motion answer, handed to every
                // screen below through `\.blueprint`.
                .blueprint()
                // docs/06: the consent web view comes back on the reversed-client-id scheme, and
                // the SDK is the only thing that knows what to do with what it carries.
                .onOpenURL { url in _ = GoogleAuth.handle(url) }
        }
    }
}

/// docs/09 트렌드 7 · "아이콘": the iOS tab bar is the platform's own chrome, so it stays glass — what
/// is ours is the tint (the blueprint accent on the selected tab) and the thin geometric symbols.
///
/// A view of its own rather than a `TabView` in the scene: `\.blueprint` is put into the
/// environment above it, and only a view below that can read the palette the tint comes from.
private struct RootTabs: View {
    @ObservedObject var model: RecordingModel
    @ObservedObject var language: AppLanguage
    @ObservedObject var theme: AppTheme
    @Environment(\.blueprint) private var blueprint

    /// The selection is the model's rather than this view's own state, for two reasons that pull
    /// the same way: without a binding the selection is the tab bar's and goes back to the first
    /// tab whenever the bar is rebuilt — which opening the workflow editor does, from three tabs
    /// away — and docs/08 "오류" needs "check the key", which is on the list, to land on the editor,
    /// which is on the workflow tab.
    var body: some View {
        TabView(selection: $model.tab) {
            RecordingView(model: model)
                .tabItem { Label { Text("Record") } icon: { BlueprintIcon(.record) } }
                .tag(PhoneTab.record)
            RecordingsView(model: model)
                .tabItem { Label { Text("List") } icon: { BlueprintIcon(.list) } }
                .tag(PhoneTab.recordings)
            WorkflowsView(model: model)
                .tabItem { Label { Text("Workflows") } icon: { BlueprintIcon(.workflows) } }
                .tag(PhoneTab.workflows)
            SettingsView(model: model, language: language, theme: theme)
                .tabItem { Label { Text("Settings") } icon: { BlueprintIcon(.settings) } }
                .tag(PhoneTab.settings)
        }
        .tint(blueprint.palette.accent)
    }
}

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Registering a `BGTaskScheduler` handler any later than this is registering it after the
        // launch that was for it (docs/13 I4).
        RecordingModel.shared.registerBackgroundTasks()
        return true
    }

    /// docs/13 deliverable 3: the app was started again because the background upload session has
    /// events to deliver. The completion handler is what lets it be suspended again once they are.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        RecordingModel.shared.handleBackgroundSessionEvents(completion: completionHandler)
    }
}
