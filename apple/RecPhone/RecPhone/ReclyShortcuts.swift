import AppIntents

/// docs/13 I7: the phrases Siri answers to, and — because an App Shortcut is what the action button
/// on an iPhone 15 Pro can be pointed at — the action button too. It lives in the app target
/// because that is the only place App Intents metadata is extracted from for Siri to find.
struct ReclyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartRecordingIntent(),
            // docs/07: the phrases are English here and translated in `AppShortcuts.xcstrings`;
            // Siri matches the ones for the system's own language.
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
    }
}
