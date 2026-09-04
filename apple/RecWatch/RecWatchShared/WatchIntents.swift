import AppIntents
import Foundation

/// What the watch's entry points drive — the app's `WatchRecordingModel`, or a fake in a test.
///
/// There is no workflow parameter here, unlike the phone's `StartRecordingIntent`: the watch's pick
/// is made on its own screen and `nil` runs the source's default (ADR-016), and an Ultra action
/// button press has nobody to ask.
@MainActor
protocol WatchRecordingCommands: AnyObject {
    func startFromIntent() async

    func stopFromIntent() async
}

/// Where an intent finds the model. The app registers itself in its initialiser; an intent that
/// arrives before the core is open — the action button *is* the launch — waits, because everything
/// on the model's side of this waits for the same load.
@MainActor
enum WatchRecordingTarget {
    static weak var commands: (any WatchRecordingCommands)?
}

/// docs/13 "Apple Watch" 진입점: the complication's tap and the App Shortcut the Ultra action button
/// runs. `openAppWhenRun` is the point rather than a detail — the audio session belongs to the app,
/// and a widget extension is not allowed to hold one for three hours.
struct StartWatchRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Start recording"
    static var description = IntentDescription("Start a recording with Recly.")
    static var openAppWhenRun = true

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await WatchRecordingTarget.commands?.startFromIntent()
        return .result()
    }
}

/// The complication's tap while a recording is running, and the App Shortcut's second phrase.
/// Double Tap reaches the same model through the button's `handGestureShortcut(.primaryAction)`.
struct StopWatchRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Stop recording"
    static var description = IntentDescription("Stop the Recly recording that is running.")
    static var openAppWhenRun = true

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await WatchRecordingTarget.commands?.stopFromIntent()
        return .result()
    }
}
