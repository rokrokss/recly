import AppIntents
import Foundation

/// What the intents drive — the app's `RecordingModel`, or a fake in a test.
///
/// The intents run in the app's process (`openAppWhenRun` for the start, `LiveActivityIntent` for
/// the stop), so this is a call into the same model the screen is drawn from, never a second one.
@MainActor
protocol RecordingCommands: AnyObject {
    /// Starts a recording with the fixed recording processing settings (docs/05).
    ///
    /// - Returns: nil when the recording started, or the sentence to report when the app refused
    ///   to start one from the background (docs/12 M8: the consent reminder is still owed).
    func startFromIntent() async -> String?

    func stopFromIntent() async
}

/// docs/12 M8: what the intent tells the user when the app would not start a recording nobody is
/// there to consent to. `AppIntents` reads a thrown `CustomLocalizedStringResourceConvertible` out
/// to whoever asked — Siri says it, Shortcuts shows it — which is the only channel a background
/// intent has; the reason is made in the app's language by the model (docs/07 rule 3).
struct RecordingRefused: Error, CustomLocalizedStringResourceConvertible {
    let reason: String

    var localizedStringResource: LocalizedStringResource { "\(reason)" }
}

/// Where an intent finds the model. The app registers itself once the core is open; an intent that
/// arrives before that — the app is launched into the background to serve it — waits, because
/// everything on the model's side of this waits for the same load.
@MainActor
enum RecordingIntentTarget {
    static weak var commands: (any RecordingCommands)?
}

/// docs/13 I7: Siri, Shortcuts, the action button (iPhone 15 Pro and later, through the App
/// Shortcut) and the iOS 18 Control all start a recording through this one intent.
///
/// `openAppWhenRun` is the point rather than a detail (docs/13 "iOS 18 Control은 `OpenIntent`로 앱을
/// 열어 시작"): a long audio session started inside a widget extension is unreliable, so every
/// entry point brings the app to the front and the app opens the microphone.
struct StartRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Start recording"
    static var description = IntentDescription("Start a recording with Recly.")
    static var openAppWhenRun = true

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        let refusal = await RecordingIntentTarget.commands?.startFromIntent()
        if let refusal { throw RecordingRefused(reason: refusal) }
        return .result()
    }
}

/// The Live Activity's stop button and "Hey Siri, stop recording".
///
/// `LiveActivityIntent` is what makes the button on the Lock Screen run *in the app's process*
/// rather than in the widget extension — the recording lives there, and only the app can finalize
/// it and queue the job.
struct StopRecordingIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Stop recording"
    static var description = IntentDescription("Stop the Recly recording that is running.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await RecordingIntentTarget.commands?.stopFromIntent()
        return .result()
    }
}
