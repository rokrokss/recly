#if os(iOS)
import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 "Storage location" — Local folder on the iPhone: the folder picked in Files is kept as a bookmark,
/// found again by a fresh launch, written and read through file coordination, and reads as gone once
/// it is.
final class PickedFolderTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!
    private var base: URL!
    private var picked: URL!

    override func setUpWithError() throws {
        suite = "PickedFolderTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        base = FileManager.default.temporaryDirectory.appendingPathComponent(suite, isDirectory: true)
        picked = base.appendingPathComponent("Notes", isDirectory: true)
        try FileManager.default.createDirectory(at: picked, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: base)
    }

    func testNothingIsPickedAtFirst() async {
        let folder = PickedFolder(defaults: defaults)

        XCTAssertFalse(folder.picked)
        let reachable = await folder.reachable()
        XCTAssertFalse(reachable)
        let name = await folder.name()
        XCTAssertNil(name)
    }

    /// A pick is the folder from then on, under its own name, and a copy lands under the path the core
    /// names — the folders above it made on the way — and reads back as it went in.
    func testAPickedFolderTakesCopiesAndGivesThemBack() async throws {
        let folder = PickedFolder(defaults: defaults)
        try folder.pick(picked)

        XCTAssertTrue(folder.picked)
        let reachable = await folder.reachable()
        XCTAssertTrue(reachable)
        let name = await folder.name()
        XCTAssertEqual(name, "Notes")

        let source = base.appendingPathComponent("part.m4a")
        try Data("audio".utf8).write(to: source)
        try await folder.__importFile(source: source.path, path: "recly/memo/2026-10/rec/rec_p001_mono.m4a")
        let landed = picked.appendingPathComponent("recly/memo/2026-10/rec/rec_p001_mono.m4a")
        XCTAssertEqual(try Data(contentsOf: landed), Data("audio".utf8))

        try Data("longer audio".utf8).write(to: source)
        try await folder.__importFile(source: source.path, path: "recly/memo/2026-10/rec/rec_p001_mono.m4a")
        let size = try await folder.__size(path: "recly/memo/2026-10/rec/rec_p001_mono.m4a")
        XCTAssertEqual(size?.int64Value, 12, "a second copy replaces the first")
        let md5 = try await folder.__md5(path: "recly/memo/2026-10/rec/rec_p001_mono.m4a")
        XCTAssertEqual(md5, "58b4eecd48ad0b1b22f43d57f76b7f71")
        let isDirectory = try await folder.__isDirectory(path: "recly/memo/2026-10/rec")
        XCTAssertTrue(isDirectory.boolValue)

        let back = base.appendingPathComponent("back.m4a")
        let exported = try await folder.__exportFile(path: "recly/memo/2026-10/rec/rec_p001_mono.m4a", destination: back.path)
        XCTAssertTrue(exported.boolValue)
        XCTAssertEqual(try Data(contentsOf: back), Data("longer audio".utf8))
        let missing = try await folder.__exportFile(path: "recly/nothing.m4a", destination: back.path)
        XCTAssertFalse(missing.boolValue)

        try await folder.__delete(path: "recly/memo/2026-10/rec")
        XCTAssertFalse(FileManager.default.fileExists(atPath: landed.deletingLastPathComponent().path))
        try await folder.__delete(path: "recly/memo/2026-10/rec")
    }

    /// The next launch has only the bookmark, and it is enough.
    func testANewLaunchFindsTheFolderByItsBookmark() async throws {
        try PickedFolder(defaults: defaults).pick(picked)

        let relaunched = PickedFolder(defaults: defaults)
        let reachable = await relaunched.reachable()
        XCTAssertTrue(reachable)
        try await relaunched.__makeDirectories(path: "recly/memo")
        XCTAssertTrue(FileManager.default.fileExists(atPath: picked.appendingPathComponent("recly/memo").path))
    }

    /// A folder deleted since it was picked: still picked, no longer reachable, and still named in
    /// the row that says so.
    func testAFolderThatIsGoneIsStillPickedButNotReachable() async throws {
        try PickedFolder(defaults: defaults).pick(picked)
        try FileManager.default.removeItem(at: picked)

        let relaunched = PickedFolder(defaults: defaults)
        XCTAssertTrue(relaunched.picked)
        let reachable = await relaunched.reachable()
        XCTAssertFalse(reachable)
        let name = await relaunched.name()
        XCTAssertEqual(name, "Notes")
    }
}
#endif
