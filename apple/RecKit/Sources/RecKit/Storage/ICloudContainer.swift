#if os(iOS) || os(macOS)
import CryptoKit
import Foundation
import ReclyCore

/// docs/03 "저장 위치" (ADR-024): the app's iCloud Drive folder — `Documents` of the container
/// [identifier], which the Files app and Finder show as "Recly" — as the core's `UbiquityContainer`.
///
/// Everything the system asks of an app that writes there is here and nowhere else: every read and
/// write goes through `NSFileCoordinator`, the listing is a live `NSMetadataQuery` (it also knows the
/// files whose content is not on this device yet), a file that is only listed is asked for and waited
/// on, and a file iCloud holds two versions of is settled on the one it made current. What the files
/// mean is the core's (`ICloudFiles`).
///
/// The `__` names are the raw Kotlin members: SKIE hides them behind the `async` wrappers callers
/// use, and a Swift implementation of the interface fills in the originals.
public final class ICloudContainer: NSObject, ReclyCore.UbiquityContainer, @unchecked Sendable {
    /// The container this build is signed for: `ReclyICloudContainer` in Info.plist, which is
    /// `$(RECLY_ICLOUD_CONTAINER)` and empty unless Local.xcconfig turns iCloud on. Nil means the app
    /// has no iCloud entitlement, and iCloud is not offered at all.
    public static var configured: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "ReclyICloudContainer") as? String,
              value.hasPrefix("iCloud.")
        else { return nil }
        return value
    }

    public enum Failure: Error, LocalizedError {
        case unavailable
        case notDownloaded(String)
        /// The metadata query has not finished its first gathering: what it has is not every file.
        case listingIncomplete

        public var errorDescription: String? {
            switch self {
            case .unavailable: return "iCloud Drive is not available to this app"
            case .notDownloaded(let path): return "'\(path)' did not come down from iCloud in time"
            case .listingIncomplete: return "the iCloud file list is not complete yet"
            }
        }
    }

    public let identifier: String
    private let lock = NSLock()
    private var root: URL?
    private var identity: (any NSObjectProtocol)?
    private let listing = Listing()

    public init(identifier: String) {
        self.identifier = identifier
        super.init()
    }

    // MARK: - UbiquityContainer

    public func __available() async throws -> KotlinBoolean {
        KotlinBoolean(bool: (try? await work { self.documents() != nil }) ?? false)
    }

    public func __files() async throws -> [UbiquityFile] {
        let root = try await work { try self.requireDocuments() }
        // A partial listing would read as files deleted elsewhere, and the shared list drops what it
        // does not see (docs/03 "다른 기기의 녹음") — so it is a failure, not an answer.
        guard let items = await listing.snapshot(of: root) else { throw Failure.listingIncomplete }
        return items.compactMap { Self.file($0, under: root) }
    }

    public func __file(path: String) async throws -> UbiquityFile? {
        let root = try await work { try self.requireDocuments() }
        if let local = try await work({ try self.localFile(path, under: root) }) { return local }
        // Not on this device in any form the file system shows: the metadata query may still know it.
        guard let items = await listing.snapshot(of: root) else { throw Failure.listingIncomplete }
        return items.lazy.compactMap { Self.file($0, under: root) }.first { $0.path == path }
    }

    public func __isDirectory(path: String) async throws -> KotlinBoolean {
        try await work {
            let url = try self.url(path)
            var directory: ObjCBool = false
            return KotlinBoolean(bool: FileManager.default.fileExists(atPath: url.path, isDirectory: &directory) && directory.boolValue)
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
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let source = URL(fileURLWithPath: source, isDirectory: false)
            try Self.coordinate(writing: url, options: .forReplacing) { target in
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.copyItem(at: source, to: target)
            }
        }
    }

    public func __writeText(path: String, text: String) async throws {
        try await work {
            let url = try self.url(path)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Self.coordinate(writing: url, options: .forReplacing) { target in
                try Data(text.utf8).write(to: target, options: .atomic)
            }
        }
    }

    public func __readText(path: String, waitSeconds: Int32) async throws -> String? {
        try await work {
            let url = try self.url(path)
            guard try Self.download(url, waitSeconds: Int(waitSeconds)) == true else { return nil }
            Self.settleConflicts(at: url)
            var text: String?
            try Self.coordinate(reading: url) { source in text = try String(contentsOf: source, encoding: .utf8) }
            return text
        }
    }

    public func __exportFile(path: String, destination: String, waitSeconds: Int32) async throws -> KotlinBoolean {
        try await work {
            let url = try self.url(path)
            guard let arrived = try Self.download(url, waitSeconds: Int(waitSeconds)) else { return KotlinBoolean(bool: false) }
            guard arrived else { throw Failure.notDownloaded(path) }
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
            // Only what is here: hashing a file that is only listed would download it to answer.
            guard FileManager.default.fileExists(atPath: url.path), Self.local(url) else { return nil }
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
            guard Self.exists(url) else { return }
            try Self.coordinate(writing: url, options: .forDeleting) { target in
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
            }
        }
    }

    /// The local URL of [path] in the container — what "Show in Finder" opens. Nil while iCloud
    /// cannot be used.
    public func folderURL(_ path: String) async -> URL? {
        try? await work { try self.url(path) }
    }

    // MARK: - The container

    /// `Documents` of the container, made on first use. Nil without an iCloud account, with iCloud
    /// Drive off for the app, or without the entitlement. Asked again when the signed-in account
    /// changes. Off the main thread: resolving the container can block.
    private func documents() -> URL? {
        guard let token = FileManager.default.ubiquityIdentityToken else {
            lock.withLock { root = nil; identity = nil }
            return nil
        }
        if let known = lock.withLock({ identity?.isEqual(token) == true ? root : nil }) { return known }
        guard let container = FileManager.default.url(forUbiquityContainerIdentifier: identifier) else { return nil }
        let documents = container.appendingPathComponent("Documents", isDirectory: true)
        try? FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
        lock.withLock {
            root = documents
            identity = token
        }
        return documents
    }

    private func requireDocuments() throws -> URL {
        guard let documents = documents() else { throw Failure.unavailable }
        return documents
    }

    private func url(_ path: String) throws -> URL {
        let root = try requireDocuments()
        return path.isEmpty ? root : root.appendingPathComponent(path)
    }

    /// Blocking file work — coordination, downloads — away from the main thread and from Swift's own
    /// cooperative pool, which must not be held up by a coordinated read waiting on the network.
    private func work<T>(_ body: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                do { continuation.resume(returning: try body()) } catch { continuation.resume(throwing: error) }
            }
        }
    }

    /// The file at [path] as the file system describes it, or nil when it shows nothing there.
    private func localFile(_ path: String, under root: URL) throws -> UbiquityFile? {
        var url = root.appendingPathComponent(path)
        guard Self.exists(url) else { return nil }
        url.removeAllCachedResourceValues()
        let values = try url.resourceValues(forKeys: [
            .isDirectoryKey, .fileSizeKey, .contentModificationDateKey,
            .ubiquitousItemDownloadingStatusKey, .ubiquitousItemIsUploadedKey, .ubiquitousItemUploadingErrorKey,
        ])
        if values.isDirectory == true { return nil }
        // A placeholder has no size of its own here; the metadata query has the real one.
        guard let size = values.fileSize else { return nil }
        return UbiquityFile(
            path: path,
            size: Int64(size),
            modifiedAt: Self.millis(values.contentModificationDate),
            downloaded: values.ubiquitousItemDownloadingStatus.map(Self.isLocal) ?? true,
            uploaded: values.ubiquitousItemIsUploaded ?? false,
            uploadFailure: Self.failure(values.ubiquitousItemUploadingError)
        )
    }

    // MARK: - Files

    /// Whether [url] is there in any form: the file itself, or the `.name.icloud` stand-in an iPhone
    /// keeps for one it has not downloaded.
    private static func exists(_ url: URL) -> Bool {
        if FileManager.default.fileExists(atPath: url.path) { return true }
        let stub = url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).icloud")
        return FileManager.default.fileExists(atPath: stub.path)
    }

    private static func local(_ url: URL) -> Bool {
        var url = url
        url.removeAllCachedResourceValues()
        guard let status = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey]).ubiquitousItemDownloadingStatus
        else { return FileManager.default.fileExists(atPath: url.path) }
        return isLocal(status)
    }

    private static func isLocal(_ status: URLUbiquitousItemDownloadingStatus) -> Bool {
        status == .current || status == .downloaded
    }

    /// Asks for [url]'s content and waits up to [waitSeconds] for it. Nil when there is no such file,
    /// false when it did not arrive in time. A copy that is here but not the newest is used as it is
    /// and refreshed for next time — waiting on "current" can wait forever on iOS 18.4 and later
    /// (FB17662379).
    private static func download(_ url: URL, waitSeconds: Int) throws -> Bool? {
        guard exists(url) else { return nil }
        var item = url
        item.removeAllCachedResourceValues()
        let status = try? item.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey]).ubiquitousItemDownloadingStatus
        if status == .current || (status == nil && FileManager.default.fileExists(atPath: url.path)) { return true }
        try FileManager.default.startDownloadingUbiquitousItem(at: url)
        if status == .downloaded { return true }
        let deadline = Date().addingTimeInterval(TimeInterval(waitSeconds))
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            if local(url) { return true }
        }
        return local(url)
    }

    /// Two devices changed the same file: iCloud has made one version current already, and that is
    /// the one kept — the last write wins, as on Drive (docs/03 "제목"). The others would otherwise go
    /// on syncing, and counting against the user's storage.
    private static func settleConflicts(at url: URL) {
        guard let conflicts = NSFileVersion.unresolvedConflictVersionsOfItem(at: url), !conflicts.isEmpty else { return }
        try? coordinate(writing: url, options: []) { target in
            for version in conflicts { version.isResolved = true }
            try NSFileVersion.removeOtherVersionsOfItem(at: target)
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

    // MARK: - Metadata

    private static func file(_ item: NSMetadataItem, under root: URL) -> UbiquityFile? {
        guard let url = item.value(forAttribute: NSMetadataItemURLKey) as? URL else { return nil }
        if (item.value(forAttribute: NSMetadataItemContentTypeTreeKey) as? [String])?.contains("public.folder") == true {
            return nil
        }
        let base = root.standardizedFileURL.resolvingSymlinksInPath().path
        let full = url.standardizedFileURL.resolvingSymlinksInPath().path
        guard full.hasPrefix(base + "/") else { return nil }
        let status = item.value(forAttribute: NSMetadataUbiquitousItemDownloadingStatusKey) as? String
        return UbiquityFile(
            path: String(full.dropFirst(base.count + 1)),
            size: (item.value(forAttribute: NSMetadataItemFSSizeKey) as? NSNumber)?.int64Value ?? 0,
            modifiedAt: millis(item.value(forAttribute: NSMetadataItemFSContentChangeDateKey) as? Date),
            downloaded: status == NSMetadataUbiquitousItemDownloadingStatusCurrent
                || status == NSMetadataUbiquitousItemDownloadingStatusDownloaded,
            uploaded: (item.value(forAttribute: NSMetadataUbiquitousItemIsUploadedKey) as? NSNumber)?.boolValue ?? false,
            uploadFailure: failure(item.value(forAttribute: NSMetadataUbiquitousItemUploadingErrorKey) as? NSError)
        )
    }

    private static func millis(_ date: Date?) -> Int64 {
        Int64(((date ?? .distantPast).timeIntervalSince1970 * 1000).rounded())
    }

    /// `NSUbiquitousFileNotUploadedDueToQuotaError` is the account out of space; anything else is
    /// said as the system says it.
    private static func failure(_ error: Error?) -> String? {
        guard let error = error as NSError? else { return nil }
        if error.domain == NSCocoaErrorDomain && error.code == NSUbiquitousFileNotUploadedDueToQuotaError {
            return UbiquityFile.companion.QUOTA
        }
        return error.localizedDescription
    }
}

/// The metadata query behind [ICloudContainer.__files]: started once, on the main run loop, and
/// kept running so every listing after the first is the system's current answer rather than a new
/// search. Started again for another account's container.
@MainActor
private final class Listing {
    private var query: NSMetadataQuery?
    private var root: URL?
    private var gathered = false
    private var waiting: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var observer: NSObjectProtocol?

    /// Made with the container, off the main thread; nothing runs until the first [snapshot].
    nonisolated init() {}

    /// The query's current results, or nil while its first gathering has not finished — waited for
    /// up to 30 seconds, and then not passed off as a complete listing.
    func snapshot(of root: URL) async -> [NSMetadataItem]? {
        if self.root != root { restart(at: root) }
        await gathering(timeout: 30)
        guard gathered, let query else { return nil }
        query.disableUpdates()
        defer { query.enableUpdates() }
        return (0 ..< query.resultCount).compactMap { query.result(at: $0) as? NSMetadataItem }
    }

    private func restart(at root: URL) {
        query?.stop()
        if let observer { NotificationCenter.default.removeObserver(observer) }
        gathered = false
        self.root = root
        let query = NSMetadataQuery()
        query.searchScopes = [NSMetadataQueryUbiquitousDocumentsScope]
        query.predicate = NSPredicate(format: "%K LIKE '*'", NSMetadataItemFSNameKey)
        observer = NotificationCenter.default.addObserver(
            forName: .NSMetadataQueryDidFinishGathering, object: query, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.didGather() }
        }
        self.query = query
        query.start()
    }

    /// Until the first gathering is done, or [timeout] seconds — a query that never finishes is a
    /// listing that failed this time, not a pull that hangs.
    private func gathering(timeout: TimeInterval) async {
        if gathered { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let id = UUID()
            waiting[id] = continuation
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self?.waiting.removeValue(forKey: id)?.resume()
            }
        }
    }

    private func didGather() {
        gathered = true
        let all = waiting.values
        waiting.removeAll()
        all.forEach { $0.resume() }
    }
}
#endif
