import Foundation

/// One workflow as the watch is allowed to know it (docs/05 "워치" row): the id, the name, and
/// nothing else — never the steps, because the watch never runs one and never touches Drive
/// (ADR-002).
public struct WatchWorkflow: Equatable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

/// docs/13 deliverable 3: the phone's `updateApplicationContext` payload, which is a *replacing*
/// snapshot rather than a queue — the watch only ever wants the current list, and a context that
/// never arrived is simply the previous list still standing.
///
/// The Android original is `WearJson.workflows` on the `/rec/workflows` data item; the shape here is
/// a property list because that is what `WCSession` carries.
public enum WatchWorkflows {
    public static let contextKey = "workflows"
    /// docs/07 rule 2: the watch has no language setting of its own and follows the phone's, which
    /// rides in the same replacing snapshot.
    public static let languageKey = "language"

    public static func context(_ workflows: [WatchWorkflow], language: String) -> [String: Any] {
        [
            contextKey: workflows.map { ["id": $0.id, "name": $0.name] },
            languageKey: language,
        ]
    }

    /// `nil` when the context is not about workflows at all, so the watch leaves the list it has.
    /// An entry it cannot read is dropped rather than the whole list — the rest still name workflows
    /// the user can pick.
    public static func parse(_ context: [String: Any]) -> [WatchWorkflow]? {
        guard let entries = context[contextKey] as? [[String: Any]] else { return nil }
        return entries.compactMap { entry in
            guard let id = entry["id"] as? String, let name = entry["name"] as? String else {
                return nil
            }
            return WatchWorkflow(id: id, name: name)
        }
    }

    /// `nil` when the context says nothing about the language, so the watch keeps what it has —
    /// and a watch that has never heard from a phone is on the system's language (docs/07 rule 1).
    public static func language(_ context: [String: Any]) -> AppLanguage.Choice? {
        (context[languageKey] as? String).flatMap(AppLanguage.Choice.init(rawValue:))
    }
}
