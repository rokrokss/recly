// docs/13 I4 — the phone's transport. macOS and the watch have no use for it: a Mac is not
// suspended out from under a running upload, and the watch never talks to Drive (ADR-002).
#if os(iOS)
import CryptoKit
import Foundation
import os
import ReclyCore
import UIKit

/// The background `URLSession` as [BackgroundTransport] uses it. [URLSessionUploads] is the real
/// one; a test hands in its own and feeds the completion events back by hand, which is the only way
/// to exercise the planner round trip without a network.
public protocol ChunkUploads: AnyObject {
    /// Starts one chunk PUT from a staged file. [key] comes back on the completion event — it is
    /// the task's `taskDescription`, which is the one field that survives the app being killed.
    func start(key: String, request: URLRequest, file: URL)
    /// The keys the session is still working on. A relaunch adopts those tasks instead of sending
    /// their bytes a second time, and anything staged that is not in here is a leftover.
    func running() async -> [String]
}

/// docs/13 I4 · ADR-015 · docs/06 "배경 URLSession": Drive's resumable chunk PUTs, sent as
/// background `URLSession` upload tasks so they finish while the app is suspended or gone.
///
/// **What is and is not background here.** Only the chunk PUTs are. The core's resumable loop
/// (`DriveApi.uploadResumable`) suspends on [__execute] and is resumed by the delegate's completion
/// event, so as long as the process is merely *suspended* the whole upload walks itself: the system
/// runs the transfer, wakes the app with the event, the planner reads the 308 and the next chunk
/// goes out. Everything else a job does — opening the session, `meta.json`, the webhook, writing
/// the job rows — needs the core running, and the core only runs when the app does. That is what
/// [BackgroundJobs] (`app.recly.jobs`) is for, and why a stop schedules one.
///
/// If the process is *killed* rather than suspended, the coroutine awaiting a chunk dies with it.
/// The session finishes the transfer anyway and iOS relaunches the app into
/// `handleEventsForBackgroundURLSession` ([adoptBackgroundEvents]); the event then finds no waiter,
/// which is not a loss — `DriveApi` re-asks Drive how far it got before sending anything, so the
/// chunk that landed is not sent twice.
///
/// A 401 is returned as a 401 rather than thrown: `DriveApi.send` answers it by invalidating the
/// token and re-planning the same chunk with a fresh one, which arrives here as a new task.
public final class BackgroundTransport: NSObject, ReclyCore.Transport {
    /// docs/13 deliverable 3. One session per identifier for the life of the install — iOS keys the
    /// relaunch to it.
    public static let identifier = "app.recly.upload"

    /// Everything that is not a chunk PUT: the session start, `queryRequest`, the Drive metadata
    /// calls, the webhook. A Kotlin implementation on purpose — SKIE's `async` wrapper is only safe
    /// on those (a Swift one has to be called through its `__` members).
    private let fallback: KtorTransport
    /// Where the chunk slices are staged. Inside the app container, not `NSTemporaryDirectory()`:
    /// the system may empty that one while the upload it belongs to is still in flight.
    private let staging: URL
    /// Whether the app is not on screen — which is what tells a relaunch for background events
    /// apart from a session that simply drained while the user was using the app. Injected so the
    /// tests can answer it without a UIKit application state.
    private let isBackground: () -> Bool
    private let logger = Logger(subsystem: CoreBridge.appName, category: "upload")
    private let state = State()
    /// Built with `self` as its delegate, so it cannot be a `let`.
    private var uploads: (any ChunkUploads)!
    /// Answers to STT submissions that came back after the process that asked for them was gone.
    private let responses: MultipartResponses

    public init(
        staging: URL,
        fallback: KtorTransport = CoreBridge.ktorTransport(),
        isBackground: @escaping () -> Bool = BackgroundTransport.applicationIsBackground,
        uploads: ((any URLSessionDelegate) -> any ChunkUploads)? = nil
    ) {
        self.fallback = fallback
        self.staging = staging
        // Its own directory inside the staging one, so [sweep] — which deletes every staged slice
        // no task is carrying — cannot mistake a kept answer for a leftover chunk.
        self.responses = MultipartResponses(directory: staging.appendingPathComponent("responses", isDirectory: true))
        self.isBackground = isBackground
        super.init()
        self.uploads = uploads?(self) ?? URLSessionUploads(identifier: Self.identifier, delegate: self)
    }

    // MARK: - Transport

    /// The `__` name is the raw Kotlin member: SKIE hides it behind the `async` wrapper that
    /// callers use, but a Swift *implementation* of the interface fills in the original.
    public func __execute(plan: HttpPlan) async throws -> HttpResult {
        switch onEnum(of: plan.body) {
        case .fileRange(let range) where plan.method == "PUT":
            let key = Self.key(for: plan)
            return try await withCheckedThrowingContinuation { continuation in
                // The waiter is registered before *anything* that can be answered — before the task
                // is started, and before the session is even asked what it is carrying. Both of
                // those are awaits, and a task that finishes inside one of them would otherwise
                // reach [finished] with no waiter to hand the response to: logged as an orphan, and
                // this call left suspended for good.
                state.wait(key, continuation)
                Task { await self.send(plan, range: range, key: key) }
            }

        // docs/08 `assemblyai`: the same submission as a multipart one, only the recording is the
        // whole request body — `POST /v2/upload` takes raw bytes. Left on the foreground fallback
        // it would restart from zero every time iOS suspended the job carrying it.
        case .fileRange(let range) where plan.method == "POST":
            let key = FileRangeBody.key(for: plan, range: range)
            if let kept = responses.take(key: key) {
                logger.info("upload.bytes.replayed status=\(kept.status, privacy: .public)")
                return HttpResult(
                    status: Int32(kept.status),
                    headers: kept.headers,
                    body: kept.body.kotlinByteArray
                )
            }
            return try await withCheckedThrowingContinuation { continuation in
                state.wait(key, continuation)
                Task { await self.send(plan, upload: range, key: key) }
            }

        // docs/08: an STT submit, which is a whole recording going up. Same reason as a chunk PUT —
        // a foreground upload dies when the app is suspended, and this one can take minutes.
        case .multipart(let body):
            let key = Self.key(for: plan, multipart: body)
            // The relaunch case, and the reason this exists: the session already answered this
            // very submission while nothing was waiting for it. Uploading again would transcribe
            // the recording a second time, at the user's expense (docs/08).
            if let kept = responses.take(key: key) {
                logger.info("upload.multipart.replayed status=\(kept.status, privacy: .public)")
                return HttpResult(
                    status: Int32(kept.status),
                    headers: kept.headers,
                    body: kept.body.kotlinByteArray
                )
            }
            return try await withCheckedThrowingContinuation { continuation in
                state.wait(key, continuation)
                Task { await self.send(plan, multipart: body, key: key) }
            }

        default:
            return try await fallback.execute(plan: plan)
        }
    }

    /// Everything [__execute] cannot do before its waiter exists. It answers only by failing —
    /// a chunk that reaches the session is answered by the delegate.
    private func send(_ plan: HttpPlan, range: HttpBody.FileRange, key: String) async {
        // A task the session is still carrying from before the app was relaunched. Sending the same
        // range again would upload the bytes twice and race Drive's own idea of the offset.
        let adopted = await uploads.running().contains(key)
        logger.info(
            """
            upload.chunk range=\(plan.headers["Content-Range"] ?? "-", privacy: .public) \
            adopted=\(adopted, privacy: .public)
            """
        )
        guard !adopted else { return }
        let file = stagedFile(for: key)
        do {
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            try Self.stage(range, to: file)
        } catch {
            // Nothing was handed to the session, so nothing else will ever answer this call.
            // Through `take`, so a completion that raced this still resumes the waiter once.
            try? FileManager.default.removeItem(at: file)
            state.take(key)?.resume(throwing: error)
            return
        }
        // The answer may already have arrived while [running] was awaited — an event for this very
        // chunk delivered from an earlier launch. Starting a task nobody is waiting for would only
        // produce a second orphan.
        guard state.isWaiting(key) else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        uploads.start(key: key, request: Self.request(for: plan, range: range), file: file)
    }

    /// The same flow for a raw-bytes submission (docs/08 `assemblyai`): stage the range beside the
    /// chunks and hand the session the file. A copy rather than the recording itself, so what the
    /// session is reading lives in the staging directory, whose lifetime [sweep] owns — the joined
    /// track it was cut from is a temporary the step deletes when it ends.
    private func send(_ plan: HttpPlan, upload range: HttpBody.FileRange, key: String) async {
        let adopted = await uploads.running().contains(key)
        logger.info("upload.bytes length=\(range.length, privacy: .public) adopted=\(adopted, privacy: .public)")
        guard !adopted else { return }
        // The answer can land between `execute`'s look at the kept responses and the waiter's
        // registration: then it is on disk by now and starting another upload would pay twice.
        if let kept = responses.take(key: key) {
            logger.info("upload.bytes.replayed status=\(kept.status, privacy: .public)")
            state.take(key)?.resume(
                returning: HttpResult(status: Int32(kept.status), headers: kept.headers, body: kept.body.kotlinByteArray)
            )
            return
        }
        let file = stagedFile(for: key)
        do {
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            try Self.stage(range, to: file)
        } catch {
            try? FileManager.default.removeItem(at: file)
            state.take(key)?.resume(throwing: error)
            return
        }
        guard state.isWaiting(key) else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        // A fresh send of this key: anything remembered for it belongs to an attempt that is over,
        // and handing that back later would answer this upload with an older one's result.
        responses.discard(key: key)
        responses.expect(key: key)
        uploads.start(key: key, request: FileRangeBody.request(for: plan, range: range), file: file)
    }

    /// The same flow for a form upload: assemble the body next to the chunks and hand the session
    /// the file. The one difference is the boundary, which only exists once the body is written.
    private func send(_ plan: HttpPlan, multipart body: HttpBody.Multipart, key: String) async {
        let adopted = await uploads.running().contains(key)
        logger.info("upload.multipart parts=\(body.parts.count, privacy: .public) adopted=\(adopted, privacy: .public)")
        guard !adopted else { return }
        // The answer can land between `execute`'s look at the kept responses and the waiter's
        // registration: then it is on disk by now and starting another upload would pay twice.
        if let kept = responses.take(key: key) {
            logger.info("upload.multipart.replayed status=\(kept.status, privacy: .public)")
            state.take(key)?.resume(
                returning: HttpResult(status: Int32(kept.status), headers: kept.headers, body: kept.body.kotlinByteArray)
            )
            return
        }
        let file = stagedFile(for: key)
        let boundary: String
        do {
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
            boundary = try MultipartBody.write(body, to: file)
        } catch {
            try? FileManager.default.removeItem(at: file)
            state.take(key)?.resume(throwing: error)
            return
        }
        guard state.isWaiting(key) else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        // A fresh send of this key: anything remembered for it belongs to an attempt that is over,
        // and handing that back later would answer this upload with an older one's result.
        responses.discard(key: key)
        responses.expect(key: key)
        uploads.start(key: key, request: MultipartBody.request(for: plan, boundary: boundary), file: file)
    }

    // MARK: - Events

    /// One finished chunk PUT. The delegate calls it; a test calls it in the delegate's place.
    ///
    /// The staged file goes either way — a task that failed will be re-planned with its own fresh
    /// slice, and one whose waiter is gone was never going to be read again.
    public func finished(key: String, status: Int?, headers: [String: [String]], body: Data, error: Error?) {
        let continuation = state.take(key)
        try? FileManager.default.removeItem(at: stagedFile(for: key))
        // Whoever was waiting is about to be told; nothing needs it written down.
        if continuation != nil { responses.discard(key: key) }
        guard let continuation else {
            // The relaunch case: the process that was awaiting this is gone. For a chunk PUT that
            // costs nothing — the job is resumed by `BGProcessingTask` and `DriveApi` asks Drive
            // for the offset first. For an STT submission the answer *is* the work, so it is kept
            // for whoever asks for the same submission next ([MultipartResponses] ignores keys it
            // was not told to expect, which is every chunk).
            if let status {
                responses.save(key: key, status: status, headers: headers, body: body)
            } else {
                responses.discard(key: key)
            }
            logger.info("upload.orphan status=\(status.map(String.init) ?? "-", privacy: .public)")
            return
        }
        if let status {
            continuation.resume(returning: HttpResult(status: Int32(status), headers: headers, body: body.kotlinByteArray))
        } else {
            // No response at all — a dropped connection. The core treats an unknown throw as a
            // retryable step failure, which is what this is.
            continuation.resume(throwing: error ?? URLError(.badServerResponse))
        }
    }

    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`: iOS relaunched the
    /// app because the session has events to deliver. Merely existing reconnects the session — this
    /// is only where the system's completion handler is parked until they have all been delivered.
    ///
    /// The session is reconnected by the model's `init`, which runs *before* the app delegate is
    /// asked to hand this handler over, so the events can be drained in between. [State] latches
    /// that: a `didFinishEvents` with nothing parked is remembered, and the next handler to arrive
    /// is called straight away rather than waiting for a second finish that will never come — an
    /// unanswered handler leaves the app's background delivery open.
    ///
    /// The latch belongs to one relaunch cycle and nothing else, which is why only a finish that
    /// arrives while the app is *not* on screen sets it and why [clearEarlyFinish] drops it when
    /// the app is opened. A finish latched from an ordinary in-app upload would otherwise be
    /// consumed by the next real relaunch, answering the system before its batch had drained.
    public func adoptBackgroundEvents(completion: @escaping () -> Void) {
        guard state.park(completion) else { return }
        DispatchQueue.main.async(execute: completion)
    }

    /// The app is on screen: whatever relaunch a latched finish belonged to, nobody came for it.
    /// Called from the shell's "became active" observer.
    public func clearEarlyFinish() {
        state.clearEarlyFinish()
    }

    /// The default seam. `applicationState` is main-thread-only and the delegate answers on the
    /// session's queue, so the read hops — and it happens *before* [State]'s lock is taken, so a
    /// main thread waiting on that lock cannot be waiting on this hop.
    public static func applicationIsBackground() -> Bool {
        guard !Thread.isMainThread else { return UIApplication.shared.applicationState != .active }
        return DispatchQueue.main.sync { UIApplication.shared.applicationState != .active }
    }

    /// Staged slices nothing is uploading any more: what a kill left behind. Called once at
    /// startup, after the session has had a chance to report what it is still carrying.
    public func sweep() async {
        let running = Set(await uploads.running())
        let staged = (try? FileManager.default.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil)) ?? []
        var removed = 0
        // The file is named by its key, so this is the whole of the join. The kept answers live
        // in a directory of their own and age out on their own clock, not on the session's.
        for file in staged where !running.contains(file.lastPathComponent) && file != responses.directory {
            try? FileManager.default.removeItem(at: file)
            removed += 1
        }
        responses.sweep()
        logger.info("upload.sweep removed=\(removed, privacy: .public) running=\(running.count, privacy: .public)")
    }

    // MARK: - Plumbing

    /// Stable across launches and unique per chunk, because both are what a relaunch needs of it:
    /// the session URI names the file being uploaded and the `Content-Range` names the slice.
    static func key(for plan: HttpPlan) -> String {
        let range = plan.headers["Content-Range"] ?? ""
        return Self.digest("\(range)|\(plan.url)")
    }

    /// The same idea for a form upload, where there is no `Content-Range` to name the slice: two
    /// recordings submitted to the same provider URL must not collide, and the part that tells
    /// them apart is the file being sent.
    static func key(for plan: HttpPlan, multipart body: HttpBody.Multipart) -> String {
        Self.digest("\(plan.method)|\(plan.url)|\(MultipartBody.identity(body))")
    }

    private func stagedFile(for key: String) -> URL {
        staging.appendingPathComponent(key, isDirectory: false)
    }

    private static func digest(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The slice as a file of its own: `uploadTask(with:fromFile:)` is the only upload a background
    /// session accepts, and the file has to outlive this call.
    private static func stage(_ range: HttpBody.FileRange, to file: URL) throws {
        // `OkioPath.url` marks its result a directory; a chunk's source is a part file.
        let source = try FileHandle(forReadingFrom: URL(fileURLWithPath: range.path.description(), isDirectory: false))
        defer { try? source.close() }
        try source.seek(toOffset: UInt64(range.offset))
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let sink = try FileHandle(forWritingTo: file)
        defer { try? sink.close() }
        var remaining = Int(range.length)
        while remaining > 0 {
            guard let block = try source.read(upToCount: min(remaining, copyBuffer)), !block.isEmpty else { break }
            try sink.write(contentsOf: block)
            remaining -= block.count
        }
    }

    private static let copyBuffer = 64 * 1024

    private static func request(for plan: HttpPlan, range: HttpBody.FileRange) -> URLRequest {
        var request = URLRequest(url: URL(string: plan.url)!)
        request.httpMethod = plan.method
        for (name, value) in plan.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        // Ktor writes this one itself, so the plan does not carry it.
        request.setValue(range.contentType, forHTTPHeaderField: "Content-Type")
        return request
    }

    /// The waiting chunks and the parked relaunch handler, behind a lock: [__execute] runs on the
    /// core's IO dispatcher and the delegate answers on the session's own queue.
    private final class State: @unchecked Sendable {
        private let lock = NSLock()
        private var waiting: [String: CheckedContinuation<HttpResult, Error>] = [:]
        private var bodies: [String: Data] = [:]
        private var parked: (() -> Void)?
        /// A `didFinishEvents` that arrived, with the app in the background, before any handler was
        /// parked. Consumed exactly once — by the next [park], or by [clearEarlyFinish].
        private var finishedEarly = false

        func wait(_ key: String, _ continuation: CheckedContinuation<HttpResult, Error>) {
            lock.withLock { waiting[key] = continuation }
        }

        func take(_ key: String) -> CheckedContinuation<HttpResult, Error>? {
            lock.withLock {
                bodies[key] = nil
                return waiting.removeValue(forKey: key)
            }
        }

        /// Whether this chunk is still unanswered — asked after an `await`, where the answer may
        /// have arrived in the meantime.
        func isWaiting(_ key: String) -> Bool {
            lock.withLock { waiting[key] != nil }
        }

        func append(_ key: String, _ data: Data) {
            lock.withLock { bodies[key, default: Data()].append(data) }
        }

        func body(_ key: String) -> Data {
            lock.withLock { bodies[key] ?? Data() }
        }

        /// True when the events are already done and the caller should run [completion] itself.
        func park(_ completion: @escaping () -> Void) -> Bool {
            lock.withLock {
                guard finishedEarly else {
                    parked = completion
                    return false
                }
                finishedEarly = false
                return true
            }
        }

        /// The parked handler, or nil — and nil latches when [canLatch], so the handler that
        /// arrives next is not left waiting for a finish that has already happened.
        func unpark(canLatch: Bool) -> (() -> Void)? {
            lock.withLock {
                guard let parked else {
                    if canLatch { finishedEarly = true }
                    return nil
                }
                self.parked = nil
                return parked
            }
        }

        func clearEarlyFinish() {
            lock.withLock { finishedEarly = false }
        }
    }
}

// MARK: - URLSessionDelegate

/// A background session delivers the response line and the body separately, and an upload task is a
/// data task for the purpose of the body.
extension BackgroundTransport: URLSessionDataDelegate {
    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let key = dataTask.taskDescription else { return }
        state.append(key, data)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let key = task.taskDescription else { return }
        let response = task.response as? HTTPURLResponse
        finished(
            key: key,
            status: response.map { Int($0.statusCode) },
            headers: Self.headers(response),
            body: state.body(key),
            error: error
        )
    }

    /// Every event this relaunch was for has been delivered; the system's handler is what lets the
    /// app be suspended again. Nothing parked yet means the app delegate has not been asked for the
    /// handler — [State] latches it for whoever arrives with one.
    public func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        // Read before the lock, and only when it can matter: this is the one question whose answer
        // separates a relaunch for background events from a session that drained in the user's hands.
        guard let completion = state.unpark(canLatch: isBackground()) else { return }
        DispatchQueue.main.async(execute: completion)
    }

    private static func headers(_ response: HTTPURLResponse?) -> [String: [String]] {
        guard let response else { return [:] }
        return response.allHeaderFields.reduce(into: [:]) { headers, entry in
            guard let name = entry.key as? String else { return }
            headers[name, default: []].append(String(describing: entry.value))
        }
    }
}

/// [ChunkUploads] over the real background session.
public final class URLSessionUploads: ChunkUploads {
    private let session: URLSession
    /// docs/11 A5: read again for every chunk rather than captured, so the switch takes on the next
    /// one — the session itself is left permissive, because its `allowsCellularAccess` would be a
    /// cap no later task could ask its way out of ([UploadNetwork]).
    private let wifiOnly: () -> Bool

    public init(
        identifier: String,
        delegate: any URLSessionDelegate,
        wifiOnly: @escaping () -> Bool = { UploadNetwork.wifiOnly }
    ) {
        let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
        // The chunks are the user's recording, not a prefetch: they go when the job says so rather
        // than when the system finds it convenient.
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        self.wifiOnly = wifiOnly
        session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    public func start(key: String, request: URLRequest, file: URL) {
        var request = request
        UploadNetwork.apply(wifiOnly: wifiOnly(), to: &request)
        let task = session.uploadTask(with: request, fromFile: file)
        task.taskDescription = key
        task.resume()
    }

    public func running() async -> [String] {
        await session.allTasks.compactMap(\.taskDescription)
    }
}
#endif
