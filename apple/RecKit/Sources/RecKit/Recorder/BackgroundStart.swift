import Foundation

/// docs/12 M8 · ADR-011: whether a recording nobody is looking at may begin.
///
/// Siri, a Shortcut, the action button and the iOS 18 Control all start a recording with the phone
/// locked and the app in the background. The consent reminder is a *question* — "did you tell the
/// participants?" — and there is nobody there to answer it, so the entry point that cannot ask it
/// cannot skip it either: it refuses, in words, and the recording is the user's to start from the
/// screen where the question can be put to them.
///
/// Once the reminder has been answered, or the setting is off, every entry point starts as before.
public enum BackgroundStart {

    /// Nil when the start may go ahead; otherwise the sentence the intent reports back.
    ///
    /// - Parameter askConsent: the shell's own `Defaults.askConsent` — the reminder is on *and*
    ///   has not been answered on this device yet.
    public static func refusal(askConsent: Bool) -> String? {
        guard askConsent else { return nil }
        return RecKitStrings.localized("Open Recly once to answer the recording reminder")
    }
}
