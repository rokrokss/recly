import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/09 §1 · docs/03 "Watch → phone transfer contract": a highlight marked while recording is in the
/// recording's `meta.json` once the stop has finalized it — the file the watch sends to the phone
/// last, which is the only way its marks reach the phone. `WCSession.transferFile` itself cannot run
/// in a simulator, so this is the end of the path that can be checked here.
final class HighlightMetaTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    func testAMarkMadeWhileRecordingIsInTheFinalizedMetaFile() async throws {
        let bridge = try await CoreBridge.make(
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "reckit-tests-\(UUID().uuidString).db",
            logger: OSLogLogger(),
            secureStore: InMemorySecureStore()
        )
        let input = FakeAudioInput(format: FakeAudioInput.format(16_000))
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { _ in }
        let recordingId = try await recorder.start(title: nil)
        XCTAssertTrue(input.push(frames: 32_000) { _ in 0.1 })

        let marked = try await bridge.core.recordings.addHighlight(recordingId: recordingId, atSec: 1.5)
        let repeated = try await bridge.core.recordings.addHighlight(recordingId: recordingId, atSec: 1.9)
        XCTAssertTrue(marked.boolValue)
        XCTAssertFalse(repeated.boolValue, "a second mark within a second is the same mark")
        guard case .finalized = await recorder.stop(title: nil) else { return XCTFail("the stop did not finalize") }

        let record = try XCTUnwrap(try await bridge.core.recordings.get(id: recordingId))
        XCTAssertEqual(record.meta.highlights.map(\.atSec), [1.5])
        let file = record.dir.url.appendingPathComponent(MetaWriter.shared.metaFileName(base: MetaWriter.shared.baseName(meta: record.meta)))
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
        let highlights = try XCTUnwrap(json?["highlights"] as? [[String: Any]])
        XCTAssertEqual(highlights.compactMap { $0["atSec"] as? Double }, [1.5])
        XCTAssertEqual(json?["status"] as? String, "finalized")
    }
}
