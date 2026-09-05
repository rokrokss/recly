import Combine
import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/08 "결과 파일": the model behind the detail. `loading` is what the screen shows a spinner
/// for, and the bug this covers was a screen that never came out of it — the Mac keeps one detail
/// view in its split pane and hands it a new model per pick, so every model after the first has to
/// be loaded by a `.task` that notices the swap. This is the half of that a test can hold: a load
/// that runs always ends, whatever it found.
final class RecordingDetailTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecordingDetailTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    @MainActor
    func testALoadAlwaysEndsAndSaysWhatItFound() async throws {
        let bridge = try await makeBridge()
        let model = RecordingDetailModel(
            core: bridge.core,
            recordingId: "01J9REC0000000000000000000",
            title: "Meeting"
        )
        XCTAssertTrue(model.loading, "a fresh model has not loaded yet")
        XCTAssertEqual(model.driveFetch, .deciding, "and has decided nothing about Drive either")

        await model.load()

        XCTAssertFalse(model.loading, "a finished load is not still loading")
        XCTAssertNil(model.transcript)
        // A recording this device has never had: nothing to play, and no bar to hide either.
        XCTAssertFalse(model.hasAudio)
        XCTAssertEqual(model.playlist, [])
        XCTAssertEqual(model.totalSec, 0)
        XCTAssertFalse(model.writing)
        // docs/03 ADR-017: nothing was ever uploaded, so there is nothing to fetch back and the
        // bar keeps "No audio on this device".
        XCTAssertEqual(model.driveFetch, .idle)
        // Nothing has ever been recorded on this device, so the page may offer Play.
        XCTAssertFalse(model.deviceRecording)
    }

    /// The teardown every way out of the page goes through — the window closed, the sheet
    /// dismissed, another recording picked, the view deallocated. Calling it on a player with
    /// nothing going has to be nothing rather than a crash, because most of those callers cannot
    /// know whether one of the others got there first.
    @MainActor
    func testStoppingAPlayerThatIsNotPlayingIsHarmless() {
        let player = RecordingPlayer()

        player.stop()
        player.stop()

        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.positionSec, 0)
    }

    /// The same, on a player that has a recording loaded but was never started: `load` is what a
    /// finished `.task` does, and the page can be left before Play is ever pressed.
    @MainActor
    func testStoppingALoadedButUnstartedPlayerIsHarmless() {
        let player = RecordingPlayer()
        player.load(
            RecordingPlaylist.Selection(
                urls: [URL(fileURLWithPath: "/recordings/01J9REC/p001_mono.m4a")],
                durations: [300]
            )
        )

        player.stop()
        player.stop()

        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.positionSec, 0)
    }

    /// The bug behind this: the Mac keeps one player behind a detail view whose model is swapped
    /// per pick, and the swap stops the player before the new model has anything to play. A player
    /// that kept the last recording's selection would answer the next Play with the last recording,
    /// so `stop` leaves it holding nothing and the press hands it the current selection.
    @MainActor
    func testStoppingAPlayerLeavesItNothingToPlay() {
        let player = RecordingPlayer()
        player.load(
            RecordingPlaylist.Selection(
                urls: [URL(fileURLWithPath: "/recordings/01J9REC/p001_mono.m4a")],
                durations: [300]
            )
        )

        player.stop()
        player.play()

        XCTAssertFalse(player.isPlaying, "there is nothing loaded for Play to start")
    }

    /// docs/03 ADR-017: the fetch gate is asked over the network, and the seconds it takes are
    /// seconds in which the bar is already out of `loading`. It stays `.deciding` across them, so
    /// Play is never offered for a recording whose audio is not settled yet.
    @MainActor
    func testTheDriveGateIsUndecidedUntilTheLoadHasAskedDrive() async throws {
        let bridge = try await makeBridge()
        let model = RecordingDetailModel(
            core: bridge.core,
            recordingId: "01J9REC0000000000000000000",
            title: "Meeting"
        )
        // From a model that has already decided once, so the reset is this load's own doing and not
        // the state a fresh model happens to start in.
        await model.load()
        XCTAssertEqual(model.driveFetch, .idle)

        var seen: [RecordingDetailModel.DriveFetch] = []
        let watch = model.$driveFetch.sink { seen.append($0) }
        await model.load()
        watch.cancel()

        XCTAssertEqual(
            seen,
            [.idle, .deciding, .idle],
            "the load reopens the gate before it asks, and closes it only on the answer"
        )
    }

    /// The same model loaded twice — what a second pick of the row already open comes to — ends in
    /// the same place rather than stuck in the state the second load opened with.
    @MainActor
    func testLoadingAgainEndsAgain() async throws {
        let bridge = try await makeBridge()
        let model = RecordingDetailModel(
            core: bridge.core,
            recordingId: "01J9REC0000000000000000000",
            title: "Meeting"
        )

        await model.load()
        await model.load()

        XCTAssertFalse(model.loading)
    }

    /// docs/03 "제목": the rename the detail offers. What the core wrote is what the header says
    /// straight away — the ledger behind the page catches up on its own — and an empty answer takes
    /// the recording back to having no name of its own rather than storing a blank one.
    @MainActor
    func testARenameIsWrittenAndSaidInTheHeaderAtOnce() async throws {
        let bridge = try await makeBridge()
        let recordingId = try await seed(bridge)
        let model = RecordingDetailModel(
            core: bridge.core,
            recordingId: recordingId,
            title: RecKitStrings.localized("Untitled")
        )
        await model.load()
        XCTAssertEqual(model.givenTitle, "", "the recording was seeded without a title of its own")

        // The spaces are the user's typing and not part of the name.
        await model.rename(to: "  Budget review  ")

        XCTAssertEqual(model.title, "Budget review", "the header answers the rename itself")
        XCTAssertEqual(model.givenTitle, "Budget review", "and so does the prompt, next time it opens")
        let named = try await bridge.core.recordings.get(id: recordingId)
        XCTAssertEqual(named?.meta.title, "Budget review", "the core wrote it")

        await model.rename(to: "")

        XCTAssertEqual(model.title, RecKitStrings.localized("Untitled"))
        XCTAssertEqual(model.givenTitle, "")
        let cleared = try await bridge.core.recordings.get(id: recordingId)
        XCTAssertNil(cleared?.meta.title, "an empty answer clears the title rather than storing one")
    }

    /// A rename the core refuses — no such recording, or one still being written — leaves the
    /// header saying what it said, rather than a name nothing was written under.
    @MainActor
    func testARefusedRenameLeavesTheHeaderAlone() async throws {
        let bridge = try await makeBridge()
        let model = RecordingDetailModel(
            core: bridge.core,
            recordingId: "01J9REC0000000000000000000",
            title: "Meeting"
        )

        await model.rename(to: "Budget review")

        XCTAssertEqual(model.title, "Meeting")
    }

    /// A finalized recording and its directory, with no title of its own — the row a rename is
    /// about, minus the microphone that would otherwise have to make one.
    private func seed(_ bridge: CoreBridge) async throws -> String {
        let startedAt = bridge.deps.clock.now()
        let recordingId = Ulid.shared.generate(clock: FixedKotlinClock(startedAt))
        let meta = RecordingMeta(
            schema: 1,
            recordingId: recordingId,
            source: Source.desktop,
            platform: Platform.macos,
            deviceId: bridge.deps.device.deviceId,
            deviceName: bridge.deps.device.name,
            workflowId: nil,
            title: nil,
            startedAt: startedAt.isoUtc,
            endedAt: startedAt.isoUtc,
            durationSec: 1,
            timezone: "Asia/Seoul",
            audio: AudioSettings(
                codec: Codec.aacLc,
                container: Container.m4A,
                sampleRateHz: Int32(SegmentedRecorder.sampleRateHz),
                channels: 1,
                bitrateKbps: Int32(SegmentedRecorder.bitrateKbps),
                segmentSec: Int32(SegmentedRecorder.defaultSegmentSec)
            ),
            tracks: [Track.mono],
            parts: [],
            gaps: [],
            silenced: [],
            context: nil,
            drive: nil,
            status: RecordingStatus.finalized
        )
        let directory = dataDirectory
            .appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(MetaWriter.shared.baseName(meta: meta), isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try await bridge.core.recordings.create(meta: meta, dir: directory.okioPath)
        return recordingId
    }

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "recording-detail-tests.db",
            secureStore: InMemorySecureStore()
        )
    }
}
