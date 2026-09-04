import XCTest
@testable import RecKit

/// The half of docs/08's STT submission that survives the process dying: iOS relaunches the app to
/// deliver a background session's completion, and that event can arrive before anything has asked
/// for the submission again. A chunk PUT dropped that way costs nothing; a transcript does.
final class MultipartResponsesTests: XCTestCase {
    private var directory: URL!
    private var responses: MultipartResponses!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        responses = MultipartResponses(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// (a) An answer that arrived with no waiter is written down.
    func testAnAnswerWithNoWaiterIsKept() throws {
        responses.expect(key: key)

        responses.save(key: key, status: 200, headers: headers, body: transcript)

        XCTAssertEqual(
            responses.take(key: key),
            MultipartResponses.Stored(status: 200, headers: headers, body: transcript)
        )
    }

    /// (b) The next attempt at the same submission gets it back instead of uploading again — and
    /// only once, so a third pass does not replay a transcript that has already been used.
    func testTheKeptAnswerIsHandedOverExactlyOnce() throws {
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: headers, body: transcript)

        XCTAssertNotNil(responses.take(key: key))
        XCTAssertNil(responses.take(key: key), "an answer is consumed by the pass that takes it")
    }

    /// (c) Another recording's submission is a different upload and must not eat this one's answer.
    func testAnotherSubmissionDoesNotConsumeIt() throws {
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: headers, body: transcript)

        XCTAssertNil(responses.take(key: otherKey))
        XCTAssertNotNil(responses.take(key: key), "it is still there for the submission it belongs to")
    }

    /// The marker is what keeps chunk PUTs out: they are re-sendable, and writing every orphaned
    /// one down would fill the container with answers nobody reads.
    func testAnAnswerNobodyAskedToKeepIsNotKept() throws {
        responses.save(key: key, status: 308, headers: headers, body: Data())

        XCTAssertNil(responses.take(key: key))
    }

    /// A fresh send of the same submission starts from nothing: an answer left over from an
    /// attempt that is over would otherwise be handed back as this upload's result.
    func testDiscardForgetsBothTheMarkerAndTheAnswer() throws {
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: headers, body: transcript)

        responses.discard(key: key)

        XCTAssertNil(responses.take(key: key))
        XCTAssertFalse(responses.expects(key: key))
    }

    /// A submission nobody ever came back for does not sit in the app container for good.
    func testWhatNobodyClaimedIsSweptAfterADay() throws {
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: headers, body: transcript)

        responses.sweep(now: Date().addingTimeInterval(MultipartResponses.maximumAge + 60))

        XCTAssertNil(responses.take(key: key))
    }

    func testWhatIsStillFreshSurvivesASweep() throws {
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: headers, body: transcript)

        responses.sweep(now: Date())

        XCTAssertNotNil(responses.take(key: key))
    }

    /// Only what the provider sent back. The request's own headers carry the API key, and none of
    /// them are this type's to see, let alone to write down.
    func testOnlyTheResponseItselfIsOnDisk() throws {
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: headers, body: transcript)

        let written = try XCTUnwrap(
            FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
                .first { $0.lastPathComponent.hasPrefix(key) }
        )
        let text = try XCTUnwrap(String(data: Data(contentsOf: written), encoding: .utf8))
        XCTAssertFalse(text.contains("CLOVASPEECH"), "no request header reached the disk")
        XCTAssertFalse(text.contains("clova-key"), "no API key reached the disk")
    }

    private let key = String(repeating: "a", count: 64)
    private let otherKey = String(repeating: "b", count: 64)
    private let headers = ["Content-Type": ["application/json"]]
    private let transcript = Data(#"{"result":"COMPLETED","segments":[]}"#.utf8)
}
