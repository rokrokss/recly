import ReclyCore
import XCTest
@testable import RecKit

/// docs/08 `assemblyai`: `POST /v2/upload` puts the whole recording in the request body, so the
/// phone sends it through the background `URLSession` like every other STT submission. What that
/// needs of this type is a key a relaunch recognises and a request the provider accepts.
final class FileRangeBodyTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// Two recordings uploaded to the same endpoint are two different submissions, and a session
    /// that keyed them the same would hand one of them the other's transcript.
    func testTwoRecordingsDoNotShareAKey() throws {
        let first = FileRangeBody.key(for: plan(), range: range(file: directory.appendingPathComponent("a.m4a")))
        let second = FileRangeBody.key(for: plan(), range: range(file: directory.appendingPathComponent("b.m4a")))

        XCTAssertNotEqual(first, second)
    }

    /// The span is part of what a submission is: the same file sent from a different offset, or in
    /// a different length, is not the upload whose answer is already on disk.
    func testADifferentSpanOfTheSameFileIsADifferentKey() throws {
        let file = directory.appendingPathComponent("a.m4a")
        let whole = FileRangeBody.key(for: plan(), range: range(file: file))

        XCTAssertNotEqual(whole, FileRangeBody.key(for: plan(), range: range(file: file, offset: 16)))
        XCTAssertNotEqual(whole, FileRangeBody.key(for: plan(), range: range(file: file, length: 32)))
    }

    /// And the same submission keeps its key across launches, which is what lets a relaunch adopt
    /// the task it left running instead of uploading the recording a second time.
    func testTheSameSubmissionKeepsItsKey() throws {
        let file = directory.appendingPathComponent("a.m4a")

        XCTAssertEqual(
            FileRangeBody.key(for: plan(), range: range(file: file)),
            FileRangeBody.key(for: plan(), range: range(file: file))
        )
    }

    /// The request is the plan: method, headers and the budget the plan set. `Content-Type` is the
    /// one thing the plan does not carry — Ktor writes it from the body, so this has to.
    func testTheRequestIsThePlanWithTheBodysContentType() throws {
        let request = FileRangeBody.request(
            for: plan(timeoutSec: 900),
            range: range(file: directory.appendingPathComponent("a.m4a"))
        )

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://api.assemblyai.com/v2/upload")
        XCTAssertEqual(request.value(forHTTPHeaderField: "authorization"), "assemblyai-key")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
        XCTAssertEqual(request.timeoutInterval, 900)
    }

    /// A plan with no budget of its own leaves the transport's default in place rather than
    /// inventing one.
    func testAPlanWithNoTimeoutLeavesTheDefault() throws {
        let request = FileRangeBody.request(
            for: plan(timeoutSec: nil),
            range: range(file: directory.appendingPathComponent("a.m4a"))
        )

        XCTAssertEqual(request.timeoutInterval, URLRequest(url: URL(string: "https://x.y")!).timeoutInterval)
    }

    private func plan(timeoutSec: Int32? = 900) -> HttpPlan {
        HttpPlan(
            method: "POST",
            url: "https://api.assemblyai.com/v2/upload",
            headers: ["authorization": "assemblyai-key"],
            body: nil,
            followRedirects: true,
            timeoutSec: timeoutSec.map { KotlinInt(int: $0) }
        )
    }

    private func range(file: URL, offset: Int64 = 0, length: Int64 = 64) -> HttpBody.FileRange {
        HttpBody.FileRange(
            path: file.okioPath,
            offset: offset,
            length: length,
            contentType: "application/octet-stream"
        )
    }
}
