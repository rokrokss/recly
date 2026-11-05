#if os(macOS)
import CryptoKit
import Foundation
import XCTest
@testable import RecKit

/// docs/12 "Updates": the signed feed, the package checks, and the swap — the last against a real DMG made here, with a
/// small ad-hoc signed app inside it, so `hdiutil`, `ditto` and the code-signature check run as they do for a release.
final class DesktopUpdateTests: XCTestCase {
    private var directory: URL!
    private let key = Curve25519.Signing.PrivateKey()
    private var publicKey: String { key.publicKey.rawRepresentation.base64EncodedString() }

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("recly-update-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: - Feed

    private func feed(build: String = "42", schema: Int = 1, mac: Bool = true, size: Int64 = 10, sha256: String = String(repeating: "a", count: 64)) -> Data {
        var json: [String: Any] = [
            "schema": schema, "version": "0.3.3", "tag": "v0.3.3-build.42",
            "notes": "https://github.com/rokrokss/recly/releases/tag/v0.3.3-build.42",
            "windows": ["installerVersion": "0.3.3", "url": "https://example.invalid/Recly-0.3.3.msi", "size": 1, "sha256": sha256],
        ]
        if mac {
            json["macos"] = ["build": build, "url": "https://example.invalid/Recly-macOS-0.3.3-42.dmg", "size": size, "sha256": sha256]
        }
        return try! JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])
    }

    private func sign(_ body: Data, with key: Curve25519.Signing.PrivateKey? = nil) -> Data {
        Data(((try! (key ?? self.key).signature(for: body)).base64EncodedString() + "\n").utf8)
    }

    func testANewerBuildIsOfferedAndTheSameBuildIsNot() throws {
        let body = feed()
        let update = try XCTUnwrap(UpdateFeed.newer(body: body, signature: sign(body), publicKey: publicKey, currentBuild: 41))
        XCTAssertEqual(update.version, "0.3.3")
        XCTAssertEqual(update.build, 42)
        XCTAssertEqual(update.notes.absoluteString, "https://github.com/rokrokss/recly/releases/tag/v0.3.3-build.42")
        XCTAssertNil(try UpdateFeed.newer(body: body, signature: sign(body), publicKey: publicKey, currentBuild: 42))
        XCTAssertNil(try UpdateFeed.newer(body: body, signature: sign(body), publicKey: publicKey, currentBuild: 43))
    }

    func testAFeedWithoutAMacPackageOffersNothing() throws {
        let body = feed(mac: false)
        XCTAssertNil(try UpdateFeed.newer(body: body, signature: sign(body), publicKey: publicKey, currentBuild: 41))
    }

    func testAFeedIsReadOnlyWhenItsSignatureIsTheKeys() {
        let body = feed()
        var tampered = body
        tampered.append(contentsOf: Array(" ".utf8))
        XCTAssertThrowsError(try UpdateFeed.newer(body: tampered, signature: sign(body), publicKey: publicKey, currentBuild: 41))
        let other = Curve25519.Signing.PrivateKey()
        XCTAssertThrowsError(try UpdateFeed.newer(body: body, signature: sign(body, with: other), publicKey: publicKey, currentBuild: 41))
        XCTAssertThrowsError(try UpdateFeed.newer(body: body, signature: Data("not base64".utf8), publicKey: publicKey, currentBuild: 41))
        let future = feed(schema: 2)
        XCTAssertThrowsError(try UpdateFeed.newer(body: future, signature: sign(future), publicKey: publicKey, currentBuild: 41))
    }

    func testTheReleaseKeyIsTheOneTheWindowsAppCarries() throws {
        let windows = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("windows/app/src/main/kotlin/app/recly/windows/update/UpdateFeed.kt")
        let source = try String(contentsOf: windows, encoding: .utf8)
        XCTAssertTrue(source.contains("\"\(UpdateFeed.publicKey)\""))
        XCTAssertNotNil(try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(base64Encoded: UpdateFeed.publicKey))))
    }

    func testCheckReadsTheFeedAndItsSignatureFromTheirURLs() async throws {
        let body = feed()
        let url = directory.appendingPathComponent("update.json")
        try body.write(to: url)
        try sign(body).write(to: directory.appendingPathComponent("update.json.sig"))
        let update = try await UpdateFeed(feed: url, publicKey: publicKey).check(currentBuild: 41)
        XCTAssertEqual(update?.build, 42)
        try FileManager.default.removeItem(at: directory.appendingPathComponent("update.json.sig"))
        do {
            _ = try await UpdateFeed(feed: url, publicKey: publicKey).check(currentBuild: 41)
            XCTFail("a feed without its signature was read")
        } catch {
            XCTAssertEqual(error as? UpdateFailure, .check)
        }
    }

    // MARK: - Package

    private func update(for package: URL, size: Int64? = nil, sha256: String? = nil) throws -> AvailableUpdate {
        AvailableUpdate(
            version: "0.3.3", build: 42, package: package,
            size: size ?? UpdateInstaller.size(of: package),
            sha256: try sha256 ?? UpdateInstaller.sha256(of: package),
            notes: URL(string: "https://github.com/rokrokss/recly/releases/tag/v0.3.3-build.42")!
        )
    }

    func testTheDownloadIsKeptOnlyWhenItIsTheFeedsFile() async throws {
        let package = directory.appendingPathComponent("served.dmg")
        try Data("a disk image".utf8).write(to: package)
        let installer = UpdateInstaller(installed: directory.appendingPathComponent("Old.app"), downloads: directory.appendingPathComponent("downloads"))

        let kept = try await installer.download(update(for: package))
        XCTAssertEqual(kept.lastPathComponent, "Recly-0.3.3-42.dmg")
        XCTAssertEqual(try Data(contentsOf: kept), Data("a disk image".utf8))

        for wrong in [try update(for: package, size: 3), try update(for: package, sha256: String(repeating: "0", count: 64))] {
            do {
                _ = try await installer.download(wrong)
                XCTFail("a package that is not the feed's was kept")
            } catch {
                XCTAssertEqual(error as? UpdateFailure, .signature)
            }
        }
    }

    // MARK: - Swap

    /// A one-file app, ad-hoc signed with [identifier]: `/usr/bin/true` as its executable.
    private func makeApp(at url: URL, identifier: String = "app.recly.test", build: String, minimumSystem: String = "14.0") throws {
        let contents = url.appendingPathComponent("Contents")
        try FileManager.default.createDirectory(at: contents.appendingPathComponent("MacOS"), withIntermediateDirectories: true)
        let info: [String: Any] = [
            "CFBundleIdentifier": identifier, "CFBundleExecutable": "Test", "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": "0.3.3", "CFBundleVersion": build, "LSMinimumSystemVersion": minimumSystem,
        ]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
            .write(to: contents.appendingPathComponent("Info.plist"))
        try FileManager.default.copyItem(atPath: "/usr/bin/true", toPath: contents.appendingPathComponent("MacOS/Test").path)
        try run("/usr/bin/codesign", ["--force", "--sign", "-", url.path])
    }

    private func makeDMG(containing app: URL) throws -> URL {
        let source = directory.appendingPathComponent("dmg-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: app, to: source.appendingPathComponent(app.lastPathComponent))
        // As the release's: the app beside a link to /Applications.
        try FileManager.default.createSymbolicLink(at: source.appendingPathComponent("Applications"), withDestinationURL: URL(fileURLWithPath: "/Applications"))
        let dmg = directory.appendingPathComponent("Recly-\(UUID().uuidString).dmg")
        try run("/usr/bin/hdiutil", ["create", "-volname", "Recly", "-srcfolder", source.path, "-ov", "-format", "UDZO", dmg.path])
        return dmg
    }

    private func installer(installed: URL, requirement: String = #"identifier "app.recly.test""#) -> UpdateInstaller {
        UpdateInstaller(installed: installed, bundleIdentifier: "app.recly.test", currentBuild: 41, requirement: requirement,
                        downloads: directory.appendingPathComponent("downloads"))
    }

    private func build(of app: URL) -> String? {
        (NSDictionary(contentsOf: app.appendingPathComponent("Contents/Info.plist")) as? [String: Any])?["CFBundleVersion"] as? String
    }

    func testTheNewAppComesOutOfTheDMGAndTakesTheOldOnesPlace() throws {
        let installed = directory.appendingPathComponent("Applications/Test.app")
        try makeApp(at: installed, build: "41")
        let app = directory.appendingPathComponent("new/Test.app")
        try makeApp(at: app, build: "42")
        let dmg = try makeDMG(containing: app)
        let installer = installer(installed: installed)
        XCTAssertTrue(installer.canReplace)

        let staged = try installer.stage(dmg: dmg)
        XCTAssertEqual(build(of: staged), "42")
        XCTAssertEqual(build(of: installed), "41", "staging touched the installed app")
        try installer.replace(with: staged)
        XCTAssertEqual(build(of: installed), "42")
        XCTAssertTrue(UpdateInstaller.satisfies(app: installed, requirement: #"identifier "app.recly.test""#))
        XCTAssertFalse(FileManager.default.fileExists(atPath: staged.path), "the staged copy was left behind")
    }

    func testAnAppThatIsNotThisOneOrNotNewerOrNotForThisMacIsRefused() throws {
        let installed = directory.appendingPathComponent("Applications/Test.app")
        try makeApp(at: installed, build: "41")
        let cases: [(String, String, String, UpdateFailure)] = [
            ("app.recly.other", "42", "14.0", .signature),
            ("app.recly.test", "41", "14.0", .signature),
            ("app.recly.test", "42", "99.0", .system),
        ]
        for (identifier, build, minimum, failure) in cases {
            let app = directory.appendingPathComponent("new-\(UUID().uuidString)/Test.app")
            try makeApp(at: app, identifier: identifier, build: build, minimumSystem: minimum)
            let dmg = try makeDMG(containing: app)
            XCTAssertThrowsError(try installer(installed: installed).stage(dmg: dmg), "\(identifier) \(build) \(minimum)") {
                XCTAssertEqual($0 as? UpdateFailure, failure)
            }
        }
        // Signed, newer, the same identifier — but not by the signer this app requires.
        let app = directory.appendingPathComponent("new-\(UUID().uuidString)/Test.app")
        try makeApp(at: app, build: "42")
        let dmg = try makeDMG(containing: app)
        XCTAssertThrowsError(try installer(installed: installed, requirement: #"anchor apple generic and identifier "app.recly.test""#).stage(dmg: dmg)) {
            XCTAssertEqual($0 as? UpdateFailure, .signature)
        }
        XCTAssertEqual(self.build(of: installed), "41")
    }

    func testATranslocatedOrReadOnlyCopyCannotReplaceItself() throws {
        XCTAssertFalse(installer(installed: URL(fileURLWithPath: "/private/var/folders/x/AppTranslocation/ABC/d/Test.app")).canReplace)
        XCTAssertFalse(installer(installed: URL(fileURLWithPath: "/System/Applications/Calculator.app")).canReplace)
    }

    // MARK: - Updater

    @MainActor
    func testOnlyAManualCheckSaysItFailed() async throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "recly-update-tests-\(UUID().uuidString)"))
        let missing = directory.appendingPathComponent("missing/update.json")
        let updater = DesktopUpdater(
            feed: UpdateFeed(feed: missing, publicKey: publicKey),
            installer: installer(installed: directory.appendingPathComponent("Test.app")),
            defaults: defaults
        )
        await updater.check(manual: false)
        XCTAssertEqual(updater.status, .idle)
        await updater.check(manual: true)
        XCTAssertEqual(updater.status, .failed(.check, update: nil))
    }

    @MainActor
    func testNothingIsCheckedOnItsOwnUntilTheUserSaysYes() async throws {
        let body = feed()
        let url = directory.appendingPathComponent("update.json")
        try body.write(to: url)
        try sign(body).write(to: directory.appendingPathComponent("update.json.sig"))
        let suite = "recly-update-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let updater = DesktopUpdater(
            feed: UpdateFeed(feed: url, publicKey: publicKey),
            installer: installer(installed: directory.appendingPathComponent("Test.app")),
            defaults: defaults,
            firstCheckDelay: .milliseconds(10)
        )
        XCTAssertTrue(updater.asks)
        XCTAssertFalse(updater.checksAutomatically, "off until answered")
        updater.start()
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(updater.status, .idle, "no check before an answer")

        updater.answer(true)
        XCTAssertFalse(updater.asks)
        XCTAssertEqual(defaults.object(forKey: "updates.checkAutomatically") as? Bool, true)
        for _ in 0..<100 where updater.offered == nil { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertNotNil(updater.offered, "a yes starts the checks")

        updater.checksAutomatically = false
        XCTAssertEqual(defaults.object(forKey: "updates.checkAutomatically") as? Bool, false)
        let again = DesktopUpdater(feed: UpdateFeed(feed: url, publicKey: publicKey), defaults: defaults)
        XCTAssertFalse(again.asks, "asked once")
        XCTAssertFalse(again.checksAutomatically)
        defaults.removePersistentDomain(forName: suite)
    }

    @MainActor
    func testAFoundUpdateIsOfferedAndNotInstalledWhileRecording() async throws {
        let body = feed()
        let url = directory.appendingPathComponent("update.json")
        try body.write(to: url)
        try sign(body).write(to: directory.appendingPathComponent("update.json.sig"))
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "recly-update-tests-\(UUID().uuidString)"))
        let updater = DesktopUpdater(
            feed: UpdateFeed(feed: url, publicKey: publicKey),
            installer: installer(installed: directory.appendingPathComponent("Test.app")),
            defaults: defaults
        )
        await updater.check()
        guard case .available(let update) = updater.status else { return XCTFail("\(updater.status)") }
        XCTAssertEqual(updater.offered, update)
        var terminated = false
        updater.install(idle: false) { terminated = true }
        XCTAssertEqual(updater.status, .available(update))
        XCTAssertFalse(terminated)
    }

    private func run(_ tool: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0, "\(tool) \(arguments)")
    }
}
#endif
