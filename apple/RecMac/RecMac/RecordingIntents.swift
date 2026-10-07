import AppIntents

/// docs/12 "Menu bar app": Shortcuts, Siri and Spotlight start and stop a recording and mark a moment
/// of it. The intents run in the app's own process, on the one [MenuModel] the popover is drawn from.
struct StartRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Start recording"
    static var description = IntentDescription("Start a recording with Recly.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await MenuModel.shared.whenReady()
        MenuModel.shared.start()
        return .result()
    }
}

struct StopRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Stop recording"
    static var description = IntentDescription("Stop the Recly recording that is running.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await MenuModel.shared.whenReady()
        if MenuModel.shared.canStop { MenuModel.shared.stop() }
        return .result()
    }
}

struct AddHighlightIntent: AppIntent {
    static var title: LocalizedStringResource = "Add Highlight"

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await MenuModel.shared.whenReady()
        await MenuModel.shared.addHighlight()
        return .result()
    }
}

/// The phrases, the iPhone's (`ReclyShortcuts`) plus the highlight. In the app target because that is
/// the only place App Intents metadata is extracted from; translated in `AppShortcuts.xcstrings`.
struct ReclyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartRecordingIntent(),
            phrases: [
                "Start recording with \(.applicationName)",
                "\(.applicationName) start recording",
            ],
            shortTitle: "Start recording",
            systemImageName: "record.circle"
        )
        AppShortcut(
            intent: StopRecordingIntent(),
            phrases: [
                "Stop recording with \(.applicationName)",
            ],
            shortTitle: "Stop recording",
            systemImageName: "stop.circle"
        )
        AppShortcut(
            intent: AddHighlightIntent(),
            phrases: [
                "Add a highlight with \(.applicationName)",
            ],
            shortTitle: "Add Highlight",
            systemImageName: "flag"
        )
    }
}
