import Foundation
import os

/// Answers to STT submissions that arrived with nobody left waiting for them — a form upload
/// (`clova`, `rtzr`) or a whole recording sent as the request body (`assemblyai`, docs/08).
///
/// A chunk PUT answered after the process died costs nothing: `DriveApi` asks Drive how far it got
/// and re-sends the slice. An **STT submission** is not like that. The answer *is* the work — the
/// whole transcript for `clova`, the job id for `rtzr` (docs/08) — so dropping it means the next
/// pass uploads the recording again and the user pays to transcribe it twice.
///
/// iOS kills apps mid-upload, and a background session relaunches the app to deliver the
/// completion; the delegate can reach `finished` before `execute` has re-registered a waiter for
/// that request. So a completed multipart response with no waiter is written down here, keyed by
/// the request key, and the next `execute` of the same submission takes it instead of uploading.
///
/// **What is on disk.** Only what the provider already sent back over the wire: status, response
/// headers and body. No API key, no request — the plan's headers are never written. Files live in
/// their own directory inside the app container and are consumed on read; anything older than
/// [maximumAge] is a submission nobody ever came back for and is swept.
///
/// Outside [BackgroundTransport] so it can be tested on the Mac — the transport is the phone's
/// alone (docs/13 I4).
struct MultipartResponses {
    struct Stored: Equatable, Codable {
        let status: Int
        let headers: [String: [String]]
        let body: Data
    }

    /// A submission that has not been answered yet is worth remembering the answer to. Without
    /// this marker every orphaned chunk PUT would be written down as well, and nothing reads those.
    private static let pendingSuffix = ".pending"
    private static let responseSuffix = ".response.json"

    /// A day. Long enough for a phone that was off overnight, short enough that a transcript that
    /// nobody claimed does not sit in the container for good.
    static let maximumAge: TimeInterval = 24 * 60 * 60

    let directory: URL

    private let logger = Logger(subsystem: CoreBridge.appName, category: "upload")

    init(directory: URL) {
        self.directory = directory
    }

    /// This submission is on its way; if its answer comes back unclaimed, keep it.
    func expect(key: String) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: file(key, Self.pendingSuffix).path, contents: nil)
    }

    /// Whether [expect] was called for this submission and its answer is still owed.
    func expects(key: String) -> Bool {
        FileManager.default.fileExists(atPath: file(key, Self.pendingSuffix).path)
    }

    /// Writes down an answer nobody was waiting for. A no-op unless [expect] named this key —
    /// that is what keeps chunk PUTs, which are re-sendable, out of here.
    func save(key: String, status: Int, headers: [String: [String]], body: Data) {
        guard expects(key: key) else { return }
        do {
            let encoded = try JSONEncoder().encode(Stored(status: status, headers: headers, body: body))
            try encoded.write(to: file(key, Self.responseSuffix), options: .atomic)
            try? FileManager.default.removeItem(at: file(key, Self.pendingSuffix))
            logger.info("upload.multipart.kept status=\(status, privacy: .public) bytes=\(body.count, privacy: .public)")
        } catch {
            // Nothing else can be done about it: the answer is lost and the next pass re-submits,
            // which is what would have happened without this file anyway.
            logger.error("upload.multipart.keep.failed")
        }
    }

    /// The stored answer for this submission, consumed — it is handed over exactly once.
    func take(key: String) -> Stored? {
        let url = file(key, Self.responseSuffix)
        guard let data = try? Data(contentsOf: url) else { return nil }
        defer { try? FileManager.default.removeItem(at: url) }
        return try? JSONDecoder().decode(Stored.self, from: data)
    }

    /// Forgets everything about this submission. Called before a fresh send of the same key, so a
    /// leftover from an earlier attempt can never be handed back as this one's answer.
    func discard(key: String) {
        try? FileManager.default.removeItem(at: file(key, Self.responseSuffix))
        try? FileManager.default.removeItem(at: file(key, Self.pendingSuffix))
    }

    /// Drops what nobody came back for. Called from the transport's own startup sweep.
    func sweep(now: Date = Date(), maximumAge: TimeInterval = MultipartResponses.maximumAge) {
        let manager = FileManager.default
        let stored = (try? manager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        var removed = 0
        for url in stored {
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
            guard let modified, now.timeIntervalSince(modified) > maximumAge else { continue }
            try? manager.removeItem(at: url)
            removed += 1
        }
        if removed > 0 {
            logger.info("upload.multipart.swept removed=\(removed, privacy: .public)")
        }
    }

    /// The key is a SHA-256 digest, so it is already a safe file name.
    private func file(_ key: String, _ suffix: String) -> URL {
        directory.appendingPathComponent(key + suffix, isDirectory: false)
    }
}
