import Foundation

/// docs/13 "Display" · docs/09 "Import" · "Quick start": the one app group the phone app shares with its
/// extensions — the Home and Lock Screen widgets read whether a recording is running from it, and the share
/// extension leaves imported files in it. Foundation only: the extensions link no RecKit and no core.
enum PhoneAppGroup {
    static let identifier = "group.app.recly"

    static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }
}

/// What the Record widget draws, as the app last left it — the watch complication's `WatchStatus`, for
/// the phone.
struct PhoneStatus: Codable, Equatable {
    var recording = false
    /// When the recording began, so the widget counts up with no update.
    var startedAt: Date?
    /// docs/07 rule 3: the app's language, which the extension cannot read on its own. Empty is the device's.
    var language = ""

    var appLocale: Locale { language.isEmpty ? .current : Locale(identifier: language) }
}

enum PhoneStatusStore {
    /// The widget kind the app reloads when the status changes.
    static let widgetKind = "app.recly.widget.record"

    private static var file: URL? { PhoneAppGroup.container?.appendingPathComponent("status.json") }

    static func load() -> PhoneStatus {
        guard let file, let data = try? Data(contentsOf: file) else { return PhoneStatus() }
        return (try? JSONDecoder().decode(PhoneStatus.self, from: data)) ?? PhoneStatus()
    }

    static func save(_ status: PhoneStatus) {
        guard let file, let data = try? JSONEncoder().encode(status) else { return }
        try? data.write(to: file, options: .atomic)
    }
}

/// docs/09 "Import": files `Import to Recly` copied out of another app, waiting for the app to import them.
enum ImportInbox {
    static var directory: URL? { PhoneAppGroup.container?.appendingPathComponent("Inbox", isDirectory: true) }

    /// What is waiting, oldest first: a folder per file, the file under its own name inside — the share
    /// extension's staging folders are hidden, so a file still being copied is not among them.
    static func pending() -> [URL] {
        guard let directory,
              let files = try? FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: [.creationDateKey], options: [.skipsHiddenFiles]
              )
        else { return [] }
        return files.sorted {
            let a = (try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
            return a < b
        }
    }
}
