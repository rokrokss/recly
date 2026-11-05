#if os(macOS)
import CryptoKit
import Foundation

/// docs/12 "Updates": what one release offers the desktops — `update.json`, written and signed by
/// `scripts/desktop-update.swift` and attached to every app release (`spec/desktop-update.schema.json`).
public struct UpdateManifest: Decodable, Equatable, Sendable {
    public struct MacPackage: Decodable, Equatable, Sendable {
        /// `CFBundleVersion` of the app inside the DMG — the Apple build number, which only rises.
        public let build: String
        public let url: URL
        public let size: Int64
        public let sha256: String
    }

    public let schema: Int
    /// The display version every platform shares (`CFBundleShortVersionString`).
    public let version: String
    public let tag: String
    public let notes: URL
    /// Absent when the release has no Mac package.
    public let macos: MacPackage?
}

/// A newer Mac build than this one, as the signed feed describes it.
public struct AvailableUpdate: Equatable, Sendable {
    public let version: String
    public let build: Int
    public let package: URL
    public let size: Int64
    public let sha256: String
    /// The release page, for a copy that cannot replace itself where it is.
    public let notes: URL
}

/// Why a check or an install stopped — what the settings row says, and the `reason` of `shell.update.*`.
public enum UpdateFailure: String, Error, Equatable, Sendable {
    /// The feed could not be fetched, its signature did not verify, or it was not a feed this build reads.
    case check
    /// The package could not be fetched.
    case download
    /// The package's size or SHA-256 is not the signed feed's, or the app inside it is not signed as this one is.
    case signature
    /// The DMG would not open, the app could not be copied out of it, or it could not take this one's place.
    case install
    /// This copy cannot replace itself: its folder is not writable, or macOS runs it translocated.
    case location
    /// The new app needs a newer macOS than this Mac runs.
    case system
}

/// Fetches `update.json` and `update.json.sig`, verifies the Ed25519 signature over the file's exact bytes
/// before reading a field of it, and answers whether it offers a newer Mac build.
public struct UpdateFeed: Sendable {
    /// The public half of `~/.recly/update-signing.key` (docs/development.md "App releases"). The Windows app carries
    /// the same key (`windows/…/update/UpdateFeed.kt`), and the release script refuses a key both do not carry.
    public static let publicKey = "16YvI/g8VLFPMd5TPNTcsbmU7Iy9kqZB8OsvTr4u/mE="
    /// `releases/latest` is the newest release that is neither a draft nor a pre-release (docs/15 "Desktop updates").
    public static let feedURL = URL(string: "https://github.com/rokrokss/recly/releases/latest/download/update.json")!

    public let feed: URL
    public let publicKey: String
    private let session: URLSession

    public init(feed: URL = UpdateFeed.feedURL, publicKey: String = UpdateFeed.publicKey, session: URLSession = .shared) {
        self.feed = feed
        self.publicKey = publicKey
        self.session = session
    }

    /// The newer build, or nil when this one is the newest the feed offers a Mac.
    public func check(currentBuild: Int) async throws -> AvailableUpdate? {
        let signatureURL = feed.deletingLastPathComponent().appendingPathComponent(feed.lastPathComponent + ".sig")
        async let body = fetch(feed)
        async let signature = fetch(signatureURL)
        return try Self.newer(body: try await body, signature: try await signature, publicKey: publicKey, currentBuild: currentBuild)
    }

    /// The pure half of [check]: verify, decode, compare.
    static func newer(body: Data, signature: Data, publicKey: String, currentBuild: Int) throws -> AvailableUpdate? {
        guard verify(body: body, signature: signature, publicKey: publicKey) else { throw UpdateFailure.check }
        guard let manifest = try? JSONDecoder().decode(UpdateManifest.self, from: body), manifest.schema == 1 else {
            throw UpdateFailure.check
        }
        guard let mac = manifest.macos else { return nil }
        guard let build = Int(mac.build), mac.size > 0, mac.sha256.count == 64 else { throw UpdateFailure.check }
        guard build > currentBuild else { return nil }
        return AvailableUpdate(
            version: manifest.version, build: build, package: mac.url, size: mac.size,
            sha256: mac.sha256.lowercased(), notes: manifest.notes
        )
    }

    static func verify(body: Data, signature: Data, publicKey: String) -> Bool {
        let text = String(decoding: signature, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let raw = Data(base64Encoded: text), let keyData = Data(base64Encoded: publicKey),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData)
        else { return false }
        return key.isValidSignature(raw, for: body)
    }

    private func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.setValue(UpdateFeed.userAgent, forHTTPHeaderField: "User-Agent")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw UpdateFailure.check
        }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { throw UpdateFailure.check }
        return data
    }

    /// Only the app and its version — no device, account or install identifier (docs/15 "Desktop updates").
    static var userAgent: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return "Recly/\(version) (macOS)"
    }
}
#endif
