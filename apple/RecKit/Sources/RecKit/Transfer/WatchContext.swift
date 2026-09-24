import Foundation

/// docs/13 deliverable 3: the phone's `updateApplicationContext` payload, which is a *replacing*
/// snapshot rather than a queue — a context that never arrived is simply the previous one still
/// standing. It carries the app's language and nothing else (docs/15 §4).
public enum WatchContext {
    /// docs/07 rule 2: the watch has no language setting of its own and follows the phone's.
    public static let languageKey = "language"

    public static func context(language: String) -> [String: Any] {
        [languageKey: language]
    }

    /// `nil` when the context says nothing about the language, so the watch keeps what it has —
    /// and a watch that has never heard from a phone is on the system's language (docs/07 rule 1).
    public static func language(_ context: [String: Any]) -> AppLanguage.Choice? {
        (context[languageKey] as? String).flatMap(AppLanguage.Choice.init(rawValue:))
    }
}
