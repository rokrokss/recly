#if os(macOS)
import Foundation
import ReclyCore

/// docs/03 "Storage location": the local folder the user picked on this Mac, kept as a plain path — the app is
/// not sandboxed (docs/12), so the path is all it takes to reach the folder again after a relaunch.
///
/// `UserDefaults` and not the processing settings, as this Mac's other device settings are: the
/// folder is a fact about this disk, and the exported settings file must not carry it to another
/// machine (docs/05 "Not synchronized").
public enum LocalFolderPath {
    public static let key = "storage.localFolder"

    /// The folder's absolute path; nil while none is picked.
    public static var current: String? {
        get { UserDefaults.standard.string(forKey: key).flatMap { $0.isEmpty ? nil : $0 } }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    /// The core's `PathFolder` over [current]. The root is read on every call, so a new pick applies
    /// to the next write without reopening the core.
    public static func folder() -> any ReclyCore.LocalFolder {
        PathFolder(fileSystem: OkioFileSystem.companion.SYSTEM, root: { current })
    }

    /// Where [path] — `/`-separated and relative to the picked folder, as the core keeps it — is on
    /// this Mac, for "Show in Finder". Nil while no folder is picked.
    public static func url(_ path: String, under root: String? = current) -> URL? {
        guard let root else { return nil }
        return path.split(separator: "/").reduce(URL(fileURLWithPath: root, isDirectory: true)) {
            $0.appendingPathComponent(String($1), isDirectory: true)
        }
    }
}
#endif
