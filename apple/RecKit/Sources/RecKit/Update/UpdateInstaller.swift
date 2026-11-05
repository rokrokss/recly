#if os(macOS)
import CryptoKit
import Foundation
import Security

/// docs/12 "Updates": puts a newer Recly in this one's place — the release's own notarized DMG, downloaded and checked
/// against the signed feed, the app copied out of it and checked against this app's own code signature, swapped in
/// where this copy is, and opened again once this process has quit.
///
/// No installer of its own and no helper tool: the app is not sandboxed and lives where the user dragged it, so a
/// folder it may write is all a swap needs. Where it may not — a standard account's `/Applications`, a copy macOS runs
/// translocated from Downloads — [canReplace] says so before anything is downloaded, and the user is sent to the
/// release page instead.
public struct UpdateInstaller: Sendable {
    /// This app's bundle.
    public let installed: URL
    /// What the new app must satisfy: by default this app's own designated requirement — the same team, the same
    /// bundle identifier — so the microphone and system-audio permissions and the keychain items carry over.
    let requirement: String?
    let bundleIdentifier: String
    let currentBuild: Int
    let downloads: URL

    public init(
        installed: URL = Bundle.main.bundleURL,
        bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "app.recly.mac",
        currentBuild: Int = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0,
        requirement: String? = nil,
        downloads: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("app.recly.mac/Updates", isDirectory: true)
    ) {
        self.installed = installed
        self.bundleIdentifier = bundleIdentifier
        self.currentBuild = currentBuild
        self.requirement = requirement
        self.downloads = downloads
    }

    /// Whether this copy can be swapped where it is: its folder writable by this user, and not an App Translocation
    /// mount, which is read-only and vanishes with the process.
    public var canReplace: Bool {
        let parent = installed.deletingLastPathComponent().path
        return !installed.path.contains("/AppTranslocation/")
            && FileManager.default.isWritableFile(atPath: parent)
            && FileManager.default.isWritableFile(atPath: installed.path)
    }

    /// Downloads the DMG into [downloads] (one at a time — an earlier one is removed), refusing it unless its size
    /// and SHA-256 are the signed feed's.
    public func download(_ update: AvailableUpdate, session: URLSession = .shared) async throws -> URL {
        var request = URLRequest(url: update.package, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 60)
        request.setValue(UpdateFeed.userAgent, forHTTPHeaderField: "User-Agent")
        let temporary: URL
        let response: URLResponse
        do {
            (temporary, response) = try await session.download(for: request)
        } catch {
            throw UpdateFailure.download
        }
        defer { try? FileManager.default.removeItem(at: temporary) }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 { throw UpdateFailure.download }
        guard Self.size(of: temporary) == update.size, (try? Self.sha256(of: temporary)) == update.sha256 else {
            throw UpdateFailure.signature
        }
        do {
            try? FileManager.default.removeItem(at: downloads)
            try FileManager.default.createDirectory(at: downloads, withIntermediateDirectories: true)
            let destination = downloads.appendingPathComponent("Recly-\(update.version)-\(update.build).dmg")
            try FileManager.default.moveItem(at: temporary, to: destination)
            return destination
        } catch {
            throw UpdateFailure.download
        }
    }

    /// Opens the DMG read-only where nothing sees it, copies the app out next to [installed] — in the volume's own
    /// replacement directory, so the swap is a rename — and checks it before anything is replaced.
    public func stage(dmg: URL) throws -> URL {
        let mountPoint = FileManager.default.temporaryDirectory.appendingPathComponent("recly-update-\(UUID().uuidString)")
        do {
            try FileManager.default.createDirectory(at: mountPoint, withIntermediateDirectories: true)
            try Self.attach(dmg, at: mountPoint)
        } catch {
            try? FileManager.default.removeItem(at: mountPoint)
            throw UpdateFailure.install
        }
        defer {
            if (try? Self.run("/usr/bin/hdiutil", ["detach", mountPoint.path])) == nil {
                _ = try? Self.run("/usr/bin/hdiutil", ["detach", mountPoint.path, "-force"])
            }
            try? FileManager.default.removeItem(at: mountPoint)
        }
        let contents = (try? FileManager.default.contentsOfDirectory(at: mountPoint, includingPropertiesForKeys: nil)) ?? []
        guard let app = contents.first(where: { $0.pathExtension == "app" && !Self.isSymlink($0) }) else {
            throw UpdateFailure.install
        }
        let staged: URL
        do {
            let directory = try FileManager.default.url(
                for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: installed, create: true
            )
            staged = directory.appendingPathComponent(installed.lastPathComponent)
            // `ditto`, as the Finder copies a bundle: symlinks, extended attributes and the signature's files intact.
            try Self.run("/usr/bin/ditto", [app.path, staged.path])
        } catch {
            throw UpdateFailure.install
        }
        do {
            try verify(app: staged)
        } catch {
            try? FileManager.default.removeItem(at: staged.deletingLastPathComponent())
            throw error
        }
        return staged
    }

    /// The new app is this app, newer, for this Mac, and signed as this app is.
    public func verify(app: URL) throws {
        guard let info = NSDictionary(contentsOf: app.appendingPathComponent("Contents/Info.plist")) as? [String: Any],
              info["CFBundleIdentifier"] as? String == bundleIdentifier,
              let build = Int(info["CFBundleVersion"] as? String ?? ""), build > currentBuild
        else { throw UpdateFailure.signature }
        if let minimum = info["LSMinimumSystemVersion"] as? String, !Self.runs(minimum) { throw UpdateFailure.system }
        guard Self.satisfies(app: app, requirement: requirement) else { throw UpdateFailure.signature }
    }

    /// Swaps the staged app into [installed]'s place. The running process keeps the files it has open.
    public func replace(with staged: URL) throws {
        defer { try? FileManager.default.removeItem(at: staged.deletingLastPathComponent()) }
        do {
            _ = try FileManager.default.replaceItemAt(installed, withItemAt: staged)
        } catch {
            throw UpdateFailure.install
        }
    }

    /// Opens [installed] once process [pid] has gone — a shell left behind this one, as nothing of this app outlives it.
    /// It gives up after ten minutes, so a quit that never came does not leave it waiting for good.
    public func relaunch(after pid: Int32 = ProcessInfo.processInfo.processIdentifier) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = [
            "-c",
            #"i=0; while /bin/kill -0 "$1" 2>/dev/null && [ "$i" -lt 3000 ]; do /bin/sleep 0.2; i=$((i+1)); done; /usr/bin/open "$2""#,
            "recly-relaunch", String(pid), installed.path,
        ]
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            throw UpdateFailure.install
        }
    }

    // MARK: - Checks

    static func satisfies(app: URL, requirement text: String?) -> Bool {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &code) == errSecSuccess, let code else { return false }
        var requirement: SecRequirement?
        if let text {
            guard SecRequirementCreateWithString(text as CFString, [], &requirement) == errSecSuccess else { return false }
        } else {
            // This process's own designated requirement. An unsigned build has none, and updates nothing.
            var running: SecCode?
            var onDisk: SecStaticCode?
            guard SecCodeCopySelf([], &running) == errSecSuccess, let running,
                  SecCodeCopyStaticCode(running, [], &onDisk) == errSecSuccess, let onDisk,
                  SecCodeCopyDesignatedRequirement(onDisk, [], &requirement) == errSecSuccess
            else { return false }
        }
        let flags = SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSCheckNestedCode | kSecCSStrictValidate)
        return SecStaticCodeCheckValidity(code, flags, requirement) == errSecSuccess
    }

    /// Whether this Mac's macOS is at least [minimum] (`14.4`, `15`).
    static func runs(_ minimum: String) -> Bool {
        let parts = minimum.split(separator: ".").map { Int($0) ?? 0 }
        let version = OperatingSystemVersion(
            majorVersion: parts.count > 0 ? parts[0] : 0,
            minorVersion: parts.count > 1 ? parts[1] : 0,
            patchVersion: parts.count > 2 ? parts[2] : 0
        )
        return ProcessInfo.processInfo.isOperatingSystemAtLeast(version)
    }

    static func size(of url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? -1
    }

    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func isSymlink(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true
    }

    /// `hdiutil attach`, tried three times a second apart: an image another disk-image process is still letting go of,
    /// or a mount macOS turns down for a moment while it is busy, fails the first try and not the next (seen 2026-11-05).
    private static func attach(_ dmg: URL, at mountPoint: URL) throws {
        var attempt = 1
        while true {
            do {
                try run("/usr/bin/hdiutil", ["attach", dmg.path, "-nobrowse", "-noautoopen", "-readonly", "-mountpoint", mountPoint.path])
                return
            } catch {
                guard attempt < 3 else { throw error }
                attempt += 1
                Thread.sleep(forTimeInterval: 1)
            }
        }
    }

    private struct ToolFailed: Error {}

    /// Runs a system tool to its end; anything but a zero exit is a failure.
    private static func run(_ tool: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw ToolFailed() }
    }
}
#endif
