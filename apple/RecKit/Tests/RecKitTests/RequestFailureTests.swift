import Foundation
import Network
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/01: a request that fails, which only Kotlin/Native can show. The phone's transport is Swift and calls back into
/// the Kotlin one (docs/13 I4), so a failure crosses to Swift on its way up to the core. Undeclared, it ends the process
/// there, and with it this test, before anything in the core can read it.
final class RequestFailureTests: XCTestCase {
    /// Nothing listens on the discard port, so the connection is refused, as an unreachable host's is.
    private let unreachable = "http://127.0.0.1:9/"
    private var directory: URL!
    private var server: DroppingServer?

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("RequestFailureTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        server = nil
        try? FileManager.default.removeItem(at: directory)
    }

    /// The server below works, so a request that fails against it fails for the reason its test says.
    func testAWholeAnswerComesBack() async throws {
        let url = try await serve(Data("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".utf8))
        let result = try await CoreBridge.ktorTransport().execute(plan: plan(url))
        XCTAssertEqual(result.status, 200)
        XCTAssertEqual(result.body.size, 2)
    }

    func testARefusedConnectionThrows() async {
        await assertThrows { try await CoreBridge.ktorTransport().execute(plan: self.plan(self.unreachable)) }
    }

    /// The read is under way when the connection goes, which is another exception from a connection that never answered.
    func testABodyCutOffPartWayThrows() async throws {
        var answer = Data("HTTP/1.1 200 OK\r\nContent-Length: 4000000\r\n\r\n".utf8)
        answer.append(Data(count: 512 * 1024))
        let url = try await serve(answer, holdSeconds: 0.5)
        await assertThrows { try await CoreBridge.ktorTransport().execute(plan: self.plan(url)) }
    }

    /// What the list screen does when it comes up (docs/03 "Recordings from other devices"), through the transport this
    /// device's core calls: the failure reaches the pull, which says so in its summary.
    func testAListingThatFailsIsReportedByThePull() async throws {
        let bridge = try await CoreBridge.make(
            deviceName: "RequestFailureTests", dataDirectory: directory,
            databaseName: "request-failure-tests.db", secureStore: InMemorySecureStore(),
            tokenProvider: SignedIn(), transport: Readdressed(to: unreachable, through: try deviceTransport())
        )
        let summary = try await bridge.core.pullRemoteRecordings(force: true)
        XCTAssertTrue(summary.skipped?.contains("NSURLErrorDomain") == true, "\(summary.skipped ?? "nil")")
    }

    /// docs/13 "iCloud": the settings screen makes the storage folder, and this test is not entitled to any container.
    func testAContainerThatCannotMakeAFolderThrows() async {
        let container: any UbiquityContainer = ICloudContainer(identifier: "iCloud.app.recly.tests")
        await assertThrows { try await container.makeDirectories(path: "Recly") }
    }

    private func plan(_ url: String) -> HttpPlan {
        HttpPlan(method: "GET", url: url, headers: [:], body: nil, followRedirects: true, timeoutSec: nil)
    }

    private func serve(_ answer: Data, holdSeconds: Double = 0) async throws -> String {
        let server = try DroppingServer(answer: answer, holdSeconds: holdSeconds)
        self.server = server
        return try await server.url()
    }

    private func deviceTransport() throws -> (HttpPlan) async throws -> HttpResult {
        #if os(iOS)
        let staging = directory.appendingPathComponent("staging", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let transport = BackgroundTransport(staging: staging, isBackground: { false }, uploads: { _ in NoChunkUploads() })
        return { try await transport.__execute(plan: $0) }
        #else
        let transport = CoreBridge.ktorTransport()
        return { try await transport.execute(plan: $0) }
        #endif
    }

    private func assertThrows<T>(
        _ work: () async throws -> T,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await work()
            XCTFail("expected it to throw", file: file, line: line)
        } catch {}
    }
}

/// A made-up token: nothing here signs in, and every request stays on this machine.
private final class SignedIn: ReclyCore.TokenProvider {
    func __accessToken() async throws -> String { "token" }
    func __invalidate() async throws {}
}

/// Sends what the core plans to [target] instead of where it was going.
private final class Readdressed: ReclyCore.Transport {
    private let target: String
    private let send: (HttpPlan) async throws -> HttpResult

    init(to target: String, through send: @escaping (HttpPlan) async throws -> HttpResult) {
        self.target = target
        self.send = send
    }

    func __execute(plan: HttpPlan) async throws -> HttpResult {
        try await send(HttpPlan(
            method: plan.method, url: target, headers: plan.headers, body: plan.body,
            followRedirects: plan.followRedirects, timeoutSec: plan.timeoutSec
        ))
    }
}

#if os(iOS)
/// Only the foreground branch runs here, so no chunk ever starts.
private final class NoChunkUploads: ChunkUploads {
    func start(key: String, request: URLRequest, file: URL) {}
    func running() async -> [String] { [] }
}
#endif

/// Answers every connection with the same bytes and then drops it, the way a network that goes away does.
private final class DroppingServer {
    private let listener: NWListener

    init(answer: Data, holdSeconds: Double) throws {
        listener = try NWListener(using: .tcp, on: .any)
        listener.newConnectionHandler = { connection in
            connection.start(queue: .global())
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { _, _, _, _ in
                connection.send(content: answer, completion: .contentProcessed { _ in
                    DispatchQueue.global().asyncAfter(deadline: .now() + holdSeconds) { connection.forceCancel() }
                })
            }
        }
        listener.start(queue: .global())
    }

    func url() async throws -> String {
        for _ in 0 ..< 200 {
            if let port = listener.port?.rawValue, port != 0 { return "http://127.0.0.1:\(port)/" }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw URLError(.cannotConnectToHost)
    }

    deinit { listener.cancel() }
}
