#if os(iOS)
import CryptoKit
import Foundation
import ReclyCore

/// docs/03 "Storage location" — Local folder on the iPhone: a folder the user picked in the Files picker —
/// `On My iPhone/Obsidian`, a folder in iCloud Drive, another app's provider — as the core's
/// `LocalFolder`.
///
/// The app is sandboxed, so a path is not enough to reach the folder again: what is kept is a
/// bookmark (`.minimalBookmark`, Apple's "Providing access to directories"), resolved into a
/// security-scoped URL whose access is held for as long as the app runs. The folder may belong to
/// another app or to iCloud, so every read and write goes through `NSFileCoordinator`, as that
/// article asks — a coordinated read also brings down a file that is only in iCloud. What the files
/// mean is the core's (`FolderFiles`).
///
/// The `__` names are the raw Kotlin members: SKIE hides them behind the `async` wrappers callers
/// use, and a Swift implementation of the interface fills in the originals (as `ICloudContainer`).
public final class PickedFolder: NSObject, ReclyCore.LocalFolder, @unchecked Sendable {
    public static let shared = PickedFolder()

    /// The bookmark, in this iPhone's settings and never in the processing settings: the folder is a
    /// fact about this device, and an exported settings file must not carry it elsewhere.
    public static let key = "storage.localFolderBookmark"

    /// The folder's name when it was picked, for the row that says it cannot be reached any more.
    public static let nameKey = "storage.localFolderName"

    public enum Failure: Error, LocalizedError {
        case unavailable

        public var errorDescription: String? { "the local folder cannot be used" }
    }

    private let defaults: UserDefaults
    private let lock = NSLock()
    /// The bookmark the open URL came from, the URL, and whether its security scope is held — a
    /// folder in this app's own container has none to hold.
    private var opened: (bookmark: Data, url: URL, accessing: Bool)?

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        super.init()
    }

    // MARK: - Picking

    /// The user picked [url] in the Files picker. It is kept as a bookmark, made while its security
    /// scope is open, and becomes the folder every later call reaches; the one it replaces lets go.
    public func pick(_ url: URL) throws {
        let accessing = url.startAccessingSecurityScopedResource()
        do {
            let bookmark = try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
            defaults.set(bookmark, forKey: Self.key)
            defaults.set((try? url.resourceValues(forKeys: [.localizedNameKey]).localizedName) ?? url.lastPathComponent, forKey: Self.nameKey)
            lock.withLock {
                release()
                // The picked URL's own scope is the open one now: nothing to resolve until a relaunch.
                opened = (bookmark, url, accessing)
            }
        } catch {
            if accessing { url.stopAccessingSecurityScopedResource() }
            throw error
        }
    }

    /// The folder's name as Files shows it — read again while it can be reached, the one it had when
    /// it was picked once it cannot. Nil while none is picked.
    public func name() async -> String? {
        let live = try? await work { () -> String? in
            guard let url = self.root() else { return nil }
            return (try? url.resourceValues(forKeys: [.localizedNameKey]).localizedName) ?? url.lastPathComponent
        }
        return live ?? defaults.string(forKey: Self.nameKey)
    }

    /// Whether a folder has been picked at all — the row's "No folder chosen" is about this, not
    /// about whether it can be reached now.
    public var picked: Bool { defaults.data(forKey: Self.key) != nil }

    /// Whether the picked folder can be used now. The Swift side's own way to ask, so the settings
    /// row does not go through the Kotlin interface it implements.
    public func reachable() async -> Bool {
        (try? await work { () -> Bool in
            guard let url = self.root() else { return false }
            var directory: ObjCBool = false
            return FileManager.default.fileExists(atPath: url.path, isDirectory: &directory) && directory.boolValue
        }) ?? false
    }

    // MARK: - LocalFolder

    public func __available() async throws -> KotlinBoolean {
        KotlinBoolean(bool: await reachable())
    }

    public func __isDirectory(path: String) async throws -> KotlinBoolean {
        try await work {
            var directory: ObjCBool = false
            return KotlinBoolean(bool: FileManager.default.fileExists(atPath: try self.url(path).path, isDirectory: &directory) && directory.boolValue)
        }
    }

    public func __size(path: String) async throws -> KotlinLong? {
        try await work {
            var url = try self.url(path)
            url.removeAllCachedResourceValues()
            guard let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey]),
                  values.isDirectory != true, let size = values.fileSize
            else { return nil }
            return KotlinLong(value: Int64(size))
        }
    }

    public func __makeDirectories(path: String) async throws {
        try await work {
            let url = try self.url(path)
            try Self.coordinate(writing: url, options: []) { target in
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            }
        }
    }

    public func __importFile(source: String, path: String) async throws {
        try await work {
            let url = try self.url(path)
            let parent = url.deletingLastPathComponent()
            try Self.coordinate(writing: parent, options: []) { target in
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
            }
            let source = URL(fileURLWithPath: source, isDirectory: false)
            try Self.coordinate(writing: url, options: .forReplacing) { target in
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.copyItem(at: source, to: target)
            }
        }
    }

    public func __exportFile(path: String, destination: String) async throws -> KotlinBoolean {
        try await work {
            let url = try self.url(path)
            guard FileManager.default.fileExists(atPath: url.path) else { return KotlinBoolean(bool: false) }
            let destination = URL(fileURLWithPath: destination, isDirectory: false)
            try Self.coordinate(reading: url) { source in
                if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
                try FileManager.default.copyItem(at: source, to: destination)
            }
            return KotlinBoolean(bool: true)
        }
    }

    public func __md5(path: String) async throws -> String? {
        try await work {
            let url = try self.url(path)
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            var digest: String?
            try Self.coordinate(reading: url) { source in
                let handle = try FileHandle(forReadingFrom: source)
                defer { try? handle.close() }
                var md5 = Insecure.MD5()
                while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { md5.update(data: chunk) }
                digest = md5.finalize().map { String(format: "%02x", $0) }.joined()
            }
            return digest
        }
    }

    public func __delete(path: String) async throws {
        try await work {
            let url = try self.url(path)
            guard FileManager.default.fileExists(atPath: url.path) else { return }
            try Self.coordinate(writing: url, options: .forDeleting) { target in
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            }
        }
    }

    // MARK: - The folder

    /// The picked folder with its security scope open, resolved from the bookmark once per bookmark.
    /// A bookmark the system calls stale is made again while the scope is open, so the next launch
    /// still finds the folder. Nil while none is picked, or when it no longer resolves — deleted,
    /// or its access taken back in Settings › Privacy & Security › Files and Folders.
    private func root() -> URL? {
        guard let bookmark = defaults.data(forKey: Self.key) else { return nil }
        if let open = lock.withLock({ opened?.bookmark == bookmark ? opened?.url : nil }) { return open }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale) else { return nil }
        // False for a folder this app may use anyway (its own container); one it may not use fails
        // on the first read instead, and reads as unreachable.
        let accessing = url.startAccessingSecurityScopedResource()
        var kept = bookmark
        if stale, let renewed = try? url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil) {
            defaults.set(renewed, forKey: Self.key)
            kept = renewed
        }
        lock.withLock {
            release()
            opened = (kept, url, accessing)
        }
        return url
    }

    /// Lets go of the scope held for the folder picked before. Under [lock].
    private func release() {
        if let previous = opened, previous.accessing { previous.url.stopAccessingSecurityScopedResource() }
        opened = nil
    }

    private func url(_ path: String) throws -> URL {
        guard let root = root() else { throw Failure.unavailable }
        return path.split(separator: "/").reduce(root) { $0.appendingPathComponent(String($1)) }
    }

    /// Blocking file work — coordination, a download from iCloud — away from the main thread and
    /// from Swift's cooperative pool.
    private func work<T>(_ body: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                do { continuation.resume(returning: try body()) } catch { continuation.resume(throwing: error) }
            }
        }
    }

    private static func coordinate(reading url: URL, _ body: (URL) throws -> Void) throws {
        var coordination: NSError?
        var failure: Error?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: url, options: [], error: &coordination) { target in
            do { try body(target) } catch { failure = error }
        }
        if let error = coordination ?? failure { throw error }
    }

    private static func coordinate(
        writing url: URL,
        options: NSFileCoordinator.WritingOptions,
        _ body: (URL) throws -> Void
    ) throws {
        var coordination: NSError?
        var failure: Error?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: url, options: options, error: &coordination) { target in
            do { try body(target) } catch { failure = error }
        }
        if let error = coordination ?? failure { throw error }
    }
}
#endif
