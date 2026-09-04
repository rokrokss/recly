// [BackgroundTransport] is the phone's (docs/13 I4); on macOS and watchOS there is nothing to test.
#if os(iOS)
import ReclyCore
import XCTest
@testable import RecKit

/// docs/13 I4 · ADR-015: the round trip the background session makes on the planner's behalf — a
/// chunk PUT goes out as an upload task from a staged file, and the delegate's completion event
/// comes back as the `HttpResult` the planner reads to decide what to send next.
///
/// The session itself is the one thing faked. Everything else is the real transport: the slicing,
/// the staging, the keys a relaunch adopts by, and the cleanup.
final class BackgroundTransportTests: XCTestCase {
    private let session = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=AB1"
    private let chunk: Int64 = ResumableUploadPlanner.shared.CHUNK_UNIT
    private var directory: URL!
    private var part: URL!
    private var staging: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BackgroundTransportTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        staging = directory.appendingPathComponent("chunks", isDirectory: true)
        // Two whole chunks, so the first one is a non-final chunk the planner will accept.
        part = directory.appendingPathComponent("part-000-mono.m4a")
        var bytes = Data(count: Int(chunk) * 2)
        for index in 0 ..< bytes.count { bytes[index] = UInt8(index % 251) }
        try bytes.write(to: part)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// The whole of deliverable 3 in one pass: the plan the core made becomes an upload task whose
    /// body is exactly the slice, and the 308 that comes back through the delegate is what tells
    /// the planner where the next chunk starts.
    func testAChunkEventComesBackAsThePlannersNextOffset() async throws {
        let uploads = FakeChunkUploads()
        let transport = makeTransport(uploads)

        let first = plan(offset: 0, token: "ya29.first")
        async let answer = transport.__execute(plan: first)
        let started = try await uploads.started(after: 0)

        // The request is the plan, and the file beside it is the slice — never the whole part.
        XCTAssertEqual(started.request.httpMethod, "PUT")
        XCTAssertEqual(started.request.url?.absoluteString, session)
        XCTAssertEqual(started.request.value(forHTTPHeaderField: "Content-Range"), "bytes 0-\(chunk - 1)/\(chunk * 2)")
        XCTAssertEqual(started.request.value(forHTTPHeaderField: "Authorization"), "Bearer ya29.first")
        let staged = try Data(contentsOf: started.file)
        XCTAssertEqual(staged.count, Int(chunk))
        XCTAssertEqual(staged, try Data(contentsOf: part).prefix(Int(chunk)))

        transport.finished(
            key: started.key,
            status: 308,
            headers: ["Range": ["bytes=0-\(chunk - 1)"]],
            body: Data(),
            error: nil
        )

        let result = try await answer
        XCTAssertEqual(result.status, 308)
        guard case .continue(let next) = onEnum(of: ResumableUploadPlanner.shared.onResponse(result: result)) else {
            return XCTFail("a 308 with a Range header is where the next chunk starts")
        }
        XCTAssertEqual(next.nextOffset, chunk)
        // The staged slice went with the answer: nothing will read it again.
        XCTAssertFalse(FileManager.default.fileExists(atPath: started.file.path))

        // And the chunk the planner asked for next really is sent as the next task.
        async let second = transport.__execute(plan: plan(offset: next.nextOffset, token: "ya29.first"))
        let follows = try await uploads.started(after: 1)
        XCTAssertEqual(
            follows.request.value(forHTTPHeaderField: "Content-Range"),
            "bytes \(chunk)-\(chunk * 2 - 1)/\(chunk * 2)"
        )
        transport.finished(key: follows.key, status: 200, headers: [:], body: Data(#"{"id":"f1"}"#.utf8), error: nil)
        let done = try await second
        XCTAssertEqual(done.status, 200)
    }

    /// docs/06 "배경 URLSession … 401이면 해당 청크를 재계획": the transport hands the 401 back rather
    /// than throwing, because `DriveApi` answers it by invalidating the token and re-planning the
    /// very same chunk — which arrives here as a second task with the fresh bearer.
    func testA401IsHandedBackSoTheChunkIsReplannedWithAFreshToken() async throws {
        let uploads = FakeChunkUploads()
        let transport = makeTransport(uploads)

        async let answer = transport.__execute(plan: plan(offset: 0, token: "ya29.expired"))
        let started = try await uploads.started(after: 0)
        transport.finished(key: started.key, status: 401, headers: [:], body: Data(), error: nil)

        let result = try await answer
        XCTAssertEqual(result.status, 401)
        guard case .unauthorized = onEnum(of: ResumableUploadPlanner.shared.onResponse(result: result)) else {
            return XCTFail("401 is the planner's Unauthorized")
        }
        // Nothing is left staged for a chunk that has to be built again anyway.
        XCTAssertFalse(FileManager.default.fileExists(atPath: started.file.path))

        async let replanned = transport.__execute(plan: plan(offset: 0, token: "ya29.fresh"))
        let again = try await uploads.started(after: 1)
        XCTAssertEqual(again.request.value(forHTTPHeaderField: "Authorization"), "Bearer ya29.fresh")
        XCTAssertEqual(again.key, started.key, "the same chunk, so a relaunch would recognise it")
        transport.finished(key: again.key, status: 308, headers: ["Range": ["bytes=0-\(chunk - 1)"]], body: Data(), error: nil)
        _ = try await replanned
    }

    /// The relaunch: the process that was awaiting the chunk is gone, the session finished the
    /// transfer anyway, and iOS starts the app again to deliver the event. Nothing waits for it —
    /// the job is resumed by `BGProcessingTask`, which asks Drive for the offset first — so all the
    /// event has to do is not crash and not leave the slice behind.
    func testAnEventDeliveredAfterARelaunchIsAbsorbedAndTheSliceCleanedUp() async throws {
        let uploads = FakeChunkUploads()
        let transport = makeTransport(uploads)
        let key = BackgroundTransport.key(for: plan(offset: 0, token: "ya29.gone"))
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let leftover = staging.appendingPathComponent(key, isDirectory: false)
        try Data("half a chunk".utf8).write(to: leftover)

        transport.finished(key: key, status: 308, headers: ["Range": ["bytes=0-\(chunk - 1)"]], body: Data(), error: nil)

        XCTAssertFalse(FileManager.default.fileExists(atPath: leftover.path))
    }

    /// The other half of the relaunch: a task the session is *still* carrying. Sending its bytes a
    /// second time would race Drive's own idea of the offset, so the transport adopts the running
    /// task and waits for its event instead.
    func testATaskStillRunningAfterARelaunchIsAdoptedRatherThanResent() async throws {
        let uploads = FakeChunkUploads()
        let plan = plan(offset: 0, token: "ya29.first")
        let key = BackgroundTransport.key(for: plan)
        uploads.outstanding = [key]
        let transport = makeTransport(uploads)

        async let answer = transport.__execute(plan: plan)
        // Long enough that a transport which was going to start a task would have started it.
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(uploads.count, 0, "the running task is the upload; a second one is a duplicate")

        transport.finished(key: key, status: 308, headers: ["Range": ["bytes=0-\(chunk - 1)"]], body: Data(), error: nil)
        let result = try await answer
        XCTAssertEqual(result.status, 308)
    }

    /// The window the adoption check opens: asking the session what it is carrying is an `await`,
    /// and the task being adopted can finish inside it. The waiter has to be registered before that
    /// question is asked, or the event is absorbed as an orphan and the call never returns.
    func testACompletionThatLandsWhileTheSessionIsBeingAskedStillAnswersTheCall() async throws {
        let uploads = FakeChunkUploads()
        let plan = plan(offset: 0, token: "ya29.first")
        let key = BackgroundTransport.key(for: plan)
        uploads.outstanding = [key]
        let transport = makeTransport(uploads)
        uploads.onRunning = { [weak transport] in
            transport?.finished(
                key: key,
                status: 308,
                headers: ["Range": ["bytes=0-\(self.chunk - 1)"]],
                body: Data(),
                error: nil
            )
        }

        // No timeout to hide behind: before the fix this call never returned at all.
        let result = try await transport.__execute(plan: plan)

        XCTAssertEqual(result.status, 308)
        XCTAssertEqual(uploads.count, 0, "the task was already running; nothing new is sent")
    }

    /// The relaunch order the app actually produces: the model reconnects the session in its
    /// `init`, and only afterwards is the app delegate asked for the system's completion handler.
    /// If the events drain in between, the finish has nothing to call — and a handler that is never
    /// called leaves the app's background delivery open.
    func testAFinishThatLandsBeforeTheHandlerIsParkedStillCallsItExactlyOnce() {
        let transport = makeTransport(FakeChunkUploads(), isBackground: { true })
        var calls = 0
        let called = expectation(description: "the system's completion handler")

        transport.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)
        transport.adoptBackgroundEvents {
            calls += 1
            called.fulfill()
        }

        wait(for: [called], timeout: 1)
        // The latch is consumed, so a finish behind it answers nobody twice.
        transport.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)
        settle()
        XCTAssertEqual(calls, 1)
    }

    /// The ordinary order, which must not have gained a second call from the latch.
    func testAFinishAfterTheHandlerIsParkedCallsItExactlyOnce() {
        let transport = makeTransport(FakeChunkUploads(), isBackground: { true })
        var calls = 0
        let called = expectation(description: "the system's completion handler")

        transport.adoptBackgroundEvents {
            calls += 1
            called.fulfill()
        }
        transport.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)

        wait(for: [called], timeout: 1)
        settle()
        XCTAssertEqual(calls, 1)
    }

    /// The latch belongs to a relaunch and to nothing else. A session that drains while the user is
    /// looking at the app finishes with no handler to call — and latching *that* would hand the
    /// next relaunch's handler back before its own batch had been delivered.
    func testAFinishWhileTheAppIsOnScreenIsNotLatched() {
        let transport = makeTransport(FakeChunkUploads(), isBackground: { false })
        var calls = 0

        transport.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)
        transport.adoptBackgroundEvents { calls += 1 }
        settle()
        XCTAssertEqual(calls, 0, "there was nothing to consume: the handler waits for its own finish")

        let called = expectation(description: "the system's completion handler")
        transport.adoptBackgroundEvents {
            calls += 1
            called.fulfill()
        }
        transport.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)
        wait(for: [called], timeout: 1)
        XCTAssertEqual(calls, 1)
    }

    /// A latch nobody came for: the relaunch it belonged to ended with the user opening the app.
    func testALatchNoParkConsumedIsDroppedWhenTheAppBecomesActive() {
        let transport = makeTransport(FakeChunkUploads(), isBackground: { true })
        var calls = 0

        transport.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)
        transport.clearEarlyFinish()
        transport.adoptBackgroundEvents { calls += 1 }

        settle()
        XCTAssertEqual(calls, 0, "the stale latch must not answer a later relaunch's handler")
    }

    /// Long enough for a `DispatchQueue.main.async` that should not exist to prove that it does.
    private func settle() {
        RunLoop.current.run(until: Date().addingTimeInterval(0.1))
    }

    /// What a kill leaves behind: slices staged for tasks that are not running any more.
    func testTheSweepKeepsTheRunningTasksSlicesAndDropsTheRest() async throws {
        let uploads = FakeChunkUploads()
        let transport = makeTransport(uploads)
        let running = BackgroundTransport.key(for: plan(offset: 0, token: "ya29.first"))
        let orphan = BackgroundTransport.key(for: plan(offset: chunk, token: "ya29.first"))
        uploads.outstanding = [running]
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        for key in [running, orphan] {
            try Data("chunk".utf8).write(to: staging.appendingPathComponent(key, isDirectory: false))
        }

        await transport.sweep()

        XCTAssertTrue(FileManager.default.fileExists(atPath: staging.appendingPathComponent(running).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.appendingPathComponent(orphan).path))
    }

    /// docs/08 `assemblyai`: `POST /v2/upload` is the whole recording as the request body. It goes
    /// through the session for the same reason a chunk does — iOS suspends the job carrying it —
    /// and the staged bytes are what the provider gets.
    func testARawBytesSubmissionGoesOutAsABackgroundUploadTask() async throws {
        let uploads = FakeChunkUploads()
        let transport = makeTransport(uploads)

        async let answer = transport.__execute(plan: uploadPlan())
        let started = try await uploads.started(after: 0)

        XCTAssertEqual(started.request.httpMethod, "POST")
        XCTAssertEqual(started.request.url?.absoluteString, "https://api.assemblyai.com/v2/upload")
        XCTAssertEqual(started.request.value(forHTTPHeaderField: "authorization"), "assemblyai-key")
        XCTAssertEqual(started.request.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
        XCTAssertEqual(try Data(contentsOf: started.file), try Data(contentsOf: part))

        transport.finished(key: started.key, status: 200, headers: [:], body: Data(uploadUrl.utf8), error: nil)

        let result = try await answer
        XCTAssertEqual(result.status, 200)
        XCTAssertEqual(String(data: result.body.data, encoding: .utf8), uploadUrl)
        XCTAssertFalse(FileManager.default.fileExists(atPath: started.file.path))
    }

    /// The relaunch, and the reason the answer is kept at all: the process that was awaiting the
    /// upload is gone, the session finished it anyway, and asking again must take the answer off
    /// disk rather than send forty megabytes — and the user's transcript budget — a second time.
    func testAKeptAnswerForARawBytesSubmissionIsReplayedInsteadOfUploaded() async throws {
        let uploads = FakeChunkUploads()
        let transport = makeTransport(uploads)
        let plan = uploadPlan()
        let key = FileRangeBody.key(for: plan, range: uploadBody())
        let responses = MultipartResponses(directory: staging.appendingPathComponent("responses", isDirectory: true))
        responses.expect(key: key)
        responses.save(key: key, status: 200, headers: ["Content-Type": ["application/json"]], body: Data(uploadUrl.utf8))

        let result = try await transport.__execute(plan: plan)

        XCTAssertEqual(result.status, 200)
        XCTAssertEqual(String(data: result.body.data, encoding: .utf8), uploadUrl)
        XCTAssertEqual(uploads.count, 0, "the answer was already in hand; uploading again pays twice")
        XCTAssertNil(responses.take(key: key), "and it is consumed, so a later pass re-submits")
    }

    /// `isBackground` is `false` unless a test is about the relaunch latch — and injected in every
    /// case, because the real seam reads `UIApplication.shared`, which an xctest bundle with no host
    /// application has no business touching.
    private func makeTransport(
        _ uploads: FakeChunkUploads,
        isBackground: @escaping () -> Bool = { false }
    ) -> BackgroundTransport {
        BackgroundTransport(staging: staging, isBackground: isBackground, uploads: { _ in uploads })
    }

    /// The plan the core itself would make for this chunk — the point of the round trip is that
    /// nothing here is written by hand.
    private func plan(offset: Int64, token: String) -> HttpPlan {
        ResumableUploadPlanner.shared.chunkRequest(
            sessionUri: session,
            offset: offset,
            length: chunk,
            total: chunk * 2,
            path: part.okioPath,
            token: token
        )
    }

    /// What `AssemblyAiProvider.submit` plans: the whole joined track as the body of one POST.
    private func uploadPlan() -> HttpPlan {
        HttpPlan(
            method: "POST",
            url: "https://api.assemblyai.com/v2/upload",
            headers: ["authorization": "assemblyai-key"],
            body: uploadBody(),
            followRedirects: true,
            timeoutSec: KotlinInt(int: 900)
        )
    }

    private func uploadBody() -> HttpBody.FileRange {
        HttpBody.FileRange(
            path: part.okioPath,
            offset: 0,
            length: chunk * 2,
            contentType: "application/octet-stream"
        )
    }

    private let uploadUrl = #"{"upload_url":"https://cdn.assemblyai.com/upload/u1"}"#
}

/// The background session, minus the background and the session: what was started, and what the
/// system would say is still in flight after a relaunch.
private final class FakeChunkUploads: ChunkUploads, @unchecked Sendable {
    struct Started {
        let key: String
        let request: URLRequest
        let file: URL
    }

    /// Tasks the session is still carrying — set before the transport is built, as a relaunch finds
    /// them.
    var outstanding: [String] = []
    /// Fired from inside [running], which is where the real session's answer suspends.
    var onRunning: (() -> Void)?

    private let lock = NSLock()
    private var starts: [Started] = []

    var count: Int { lock.withLock { starts.count } }

    func start(key: String, request: URLRequest, file: URL) {
        lock.withLock { starts.append(Started(key: key, request: request, file: file)) }
    }

    func running() async -> [String] {
        onRunning?()
        return outstanding
    }

    /// The start after the [after]th, once the transport has got to it. `execute` suspends before
    /// the task is handed over, so a test that fed the event straight away would race it.
    func started(after: Int) async throws -> Started {
        for _ in 0 ..< 200 {
            if let started = (lock.withLock { starts.count > after ? starts[after] : nil }) { return started }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        throw XCTSkip("no upload task was started")
    }
}
#endif
