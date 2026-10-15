import AppIntents
import Foundation

/// What the intents drive — the app's `RecordingModel`, or a fake in a test.
///
/// The intents run in the app's process (`openAppWhenRun` for the start, `LiveActivityIntent` for
/// the stop), so this is a call into the same model the screen is drawn from, never a second one.
@MainActor
protocol RecordingCommands: AnyObject {
    /// Starts a recording with the fixed recording processing settings (docs/05) — or, while the consent
    /// reminder is still owed, raises it on the Record tab and starts once it is confirmed (docs/12 M8).
    ///
    /// - Returns: nil when the recording started or the reminder is up, or the sentence to report when
    ///   the app refused to start one (a disconnect is running).
    func startFromIntent() async -> String?

    func stopFromIntent() async

    /// docs/09 "Highlights": a mark on the recording that is running, at the recorder's clock.
    func highlightFromIntent() async
}

/// What the intent tells the user when the app would not start a recording — a disconnect is running. `AppIntents` reads a thrown `CustomLocalizedStringResourceConvertible` out
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
/// `openAppWhenRun` is the point rather than a detail (docs/13 "an iOS 18 Control starts by opening the app with `OpenIntent`"): a long audio session started inside a widget extension is unreliable, so every
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

/// docs/09 "Highlights": "Add a highlight with Recly" — a mark on the running recording, the iPhone's counterpart
/// of the Mac's intent. A `LiveActivityIntent` for the stop's reason: it runs in the app's process, where the
/// recording is, without bringing the app to the front.
struct AddHighlightIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Add Highlight"

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        await RecordingIntentTarget.commands?.highlightFromIntent()
        return .result()
    }
}
