import Foundation

/// docs/12 M8 · ADR-011: what a start the screen did not ask for does about the consent reminder.
///
/// Siri, a Shortcut, the action button, the iOS 18 Control and the widgets all start a recording through
/// an intent that opens the app (`openAppWhenRun`), so the app is in front by the time the start runs. The
/// reminder is a *question* — "did you tell the participants?" — and it is put to the user there, on the
/// Record tab, exactly as the screen's own start puts it: the recording begins once it is confirmed, and
/// Cancel leaves it unstarted. It is never skipped: a first recording made by saying "Hey Siri" is exactly
/// the one the reminder is for.
///
/// Once the reminder has been answered, or the setting is off, every entry point starts at once.
public enum BackgroundStart {
    public enum Next: Equatable, Sendable {
        /// Nothing is owed: the recorder is asked for a recording now.
        case start
        /// The reminder is owed: it is raised on the Record tab, and only its confirmation starts.
        case askConsent
    }

    /// - Parameter askConsent: the shell's own `Defaults.askConsent` — the reminder is on *and*
    ///   has not been answered on this device yet.
    public static func next(askConsent: Bool) -> Next {
        askConsent ? .askConsent : .start
    }
}
