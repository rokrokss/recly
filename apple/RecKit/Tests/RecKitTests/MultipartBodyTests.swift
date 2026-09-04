import ReclyCore
import XCTest
@testable import RecKit

/// The phone assembles a `multipart/form-data` body into a file so a background `URLSession` can
/// upload it (docs/08 `clova`·`rtzr`). What it writes has to be byte-for-byte what a server parses,
/// and the boundary it picked has to be the one the `Content-Type` names.
final class MultipartBodyTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTheFilePartIsCopiedThroughAndTheFieldPartIsInlined() throws {
        let audio = directory.appendingPathComponent("joined.m4a")
        try Data(audioBytes.utf8).write(to: audio)
        let out = directory.appendingPathComponent("body")

        let boundary = try MultipartBody.write(
            HttpBody.Multipart(parts: [
                HttpBody.MultipartPart(
                    name: "media",
                    contentType: "audio/mp4",
                    source: HttpBodyMultipartSourceFile(path: audio.okioPath),
                    filename: "joined.m4a"
                ),
                HttpBody.MultipartPart(
                    name: "params",
                    contentType: "application/json",
                    source: HttpBodyMultipartSourceBytes(bytes: Data(params.utf8).kotlinByteArray),
                    filename: nil
                ),
            ]),
            to: out
        )

        let written = try XCTUnwrap(String(data: Data(contentsOf: out), encoding: .utf8))
        XCTAssertEqual(
            written,
            """
            --\(boundary)\r
            Content-Disposition: form-data; name="media"; filename="joined.m4a"\r
            Content-Type: audio/mp4\r
            \r
            \(audioBytes)\r
            --\(boundary)\r
            Content-Disposition: form-data; name="params"\r
            Content-Type: application/json\r
            \r
            \(params)\r
            --\(boundary)--\r\n
            """
        )
        XCTAssertEqual(
            MultipartBody.contentType(boundary: boundary),
            "multipart/form-data; boundary=\(boundary)"
        )
    }

    /// Two submissions to the same provider URL are different uploads, and a background session
    /// keyed on the URL alone would hand one of them the other's answer.
    func testTwoRecordingsDoNotShareAnIdentity() throws {
        let first = MultipartBody.identity(body(file: directory.appendingPathComponent("a.m4a")))
        let second = MultipartBody.identity(body(file: directory.appendingPathComponent("b.m4a")))

        XCTAssertNotEqual(first, second)
        // And the same submission keeps its identity, so a relaunch adopts the task it left running.
        XCTAssertEqual(first, MultipartBody.identity(body(file: directory.appendingPathComponent("a.m4a"))))
    }

    /// docs/08: a `clova` submission answers with the whole transcript on the same request, so the
    /// connection sits idle for up to fifteen minutes. Foundation's own default is sixty seconds.
    func testTheRequestCarriesThePlansTimeout() throws {
        let plan = plan(file: directory.appendingPathComponent("a.m4a"), timeoutSec: 900)

        let request = MultipartBody.request(for: plan, boundary: "abc")

        XCTAssertEqual(request.timeoutInterval, 900)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "multipart/form-data; boundary=abc")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-CLOVASPEECH-API-KEY"), "clova-key")
    }

    /// A plan with no budget of its own leaves the transport's default in place rather than
    /// inventing one.
    func testAPlanWithNoTimeoutLeavesTheDefault() throws {
        let plan = plan(file: directory.appendingPathComponent("a.m4a"), timeoutSec: nil)

        let request = MultipartBody.request(for: plan, boundary: "abc")

        XCTAssertEqual(request.timeoutInterval, URLRequest(url: URL(string: "https://x.y")!).timeoutInterval)
    }

    private func plan(file: URL, timeoutSec: Int32?) -> HttpPlan {
        HttpPlan(
            method: "POST",
            url: "https://clovaspeech-gw.ncloud.com/external/v1/1/a/recognizer/upload",
            headers: ["X-CLOVASPEECH-API-KEY": "clova-key"],
            body: body(file: file),
            followRedirects: true,
            timeoutSec: timeoutSec.map { KotlinInt(int: $0) }
        )
    }

    private func body(file: URL) -> HttpBody.Multipart {
        HttpBody.Multipart(parts: [
            HttpBody.MultipartPart(
                name: "file",
                contentType: "audio/mp4",
                source: HttpBodyMultipartSourceFile(path: file.okioPath),
                filename: file.lastPathComponent
            ),
        ])
    }

    private let audioBytes = "aac-frames-of-a-whole-recording"
    private let params = #"{"completion":"sync"}"#
}
