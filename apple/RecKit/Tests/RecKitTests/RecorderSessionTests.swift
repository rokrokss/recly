import ReclyCore
import XCTest
@testable import RecKit

/// The four rules that decide which click counts, checked without a microphone because the capture
/// is a fake: one recording at a time, a stop that arrives too early is parked rather than dropped,
/// a stop happens exactly once, and a recovery pass never walks a directory a recorder is writing
/// into. (The Android `RecorderSessionTest` asks the same four questions of the same contract.)
final class RecorderSessionTests: XCTestCase {
    /// Two clicks on "녹음 시작" while the microphone is still opening. The second is refused — not
    /// as an error, there is nothing to tell the user — and only one recording is ever created.
    func testASecondStartWhileTheFirstIsStillOpeningIsRefused() async throws {
        let capture = FakeCapture()
        let states = States()
        let session = RecorderSession(capture: capture, recover: { 0 }, onState: { states.record($0) })

        let held = expectation(description: "the first start is in flight")
        await capture.holdNextStart { held.fulfill() }
        let first = Task { try await session.start(workflowId: "w1") }
        await fulfillment(of: [held], timeout: 5)

        let second = try await session.start(workflowId: "w2")
        XCTAssertNil(second, "one recording at a time")

        await capture.releaseStart()
        let started = try await first.value
        let recordingId = try XCTUnwrap(started)
        let starts = await capture.starts
        XCTAssertEqual(starts, 1, "one session, whatever the menu did")
        let current = await session.current
        XCTAssertEqual(current, .recording(recordingId: recordingId, workflowId: "w1"))
    }

    /// The stop the user asked for while the microphone was still opening. There is nothing to
    /// finalize at that moment and it must not be lost either, so it waits — and is served the
    /// instant there is a recording to serve it with.
    func testAStopWhileTheMicrophoneIsOpeningIsParkedAndThenServed() async throws {
        let capture = FakeCapture()
        let states = States()
        let session = RecorderSession(capture: capture, recover: { 0 }, onState: { states.record($0) })

        let held = expectation(description: "the start is in flight")
        await capture.holdNextStart { held.fulfill() }
        let starting = Task { try await session.start(workflowId: nil) }
        await fulfillment(of: [held], timeout: 5)

        let stopping = Task { await session.stop(title: nil) }
        // Long enough to be sure the stop has reached the session and is waiting there rather than
        // simply not having arrived yet.
        try await Task.sleep(nanoseconds: 200_000_000)
        let earlyStops = await capture.stops
        XCTAssertEqual(earlyStops, 0, "there is nothing to finalize while the microphone is opening")

        await capture.releaseStart()
        let result = await stopping.value
        _ = try await starting.value

        guard case .finalized = result else { return XCTFail("the parked stop was dropped: \(result)") }
        let stops = await capture.stops
        XCTAssertEqual(stops, 1, "parked, then served — once")
        let current = await session.current
        XCTAssertEqual(current, .idle)
    }

    /// The menu item, a second click on it and a fatal capture error can all land on one recording.
    /// Finalize is not idempotent from the user's side — it names the recording and hands it to the
    /// queue — so it happens once, and the shell is told what is going on in order.
    func testTwoStopsFinalizeOnce() async throws {
        let capture = FakeCapture()
        let states = States()
        let session = RecorderSession(capture: capture, recover: { 0 }, onState: { states.record($0) })
        let started = try await session.start(workflowId: nil)
        let recordingId = try XCTUnwrap(started)

        let held = expectation(description: "the first stop is in flight")
        await capture.holdNextStop { held.fulfill() }
        let first = Task { await session.stop(title: "회의") }
        await fulfillment(of: [held], timeout: 5)

        let second = await session.stop(title: "회의")
        guard case .notRecording = second else { return XCTFail("the second stop was served: \(second)") }

        await capture.releaseStop()
        let result = await first.value
        guard case .finalized = result else { return XCTFail("expected a finalized stop, got \(result)") }
        let stops = await capture.stops
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(
            states.all,
            [.starting, .recording(recordingId: recordingId, workflowId: nil), .stopping, .idle],
            "the shell draws its menu from this, so the order is part of the contract"
        )
    }

    /// A recovery pass walks the very directories a recorder is writing into — it registers files
    /// nobody filed, deletes the empty ones and quarantines the unreadable ones. It runs at launch
    /// and from inside a start, before the recording exists, and at no other time.
    func testRecoveryRunsOnlyWhileNothingIsRecording() async throws {
        let capture = FakeCapture()
        let passes = Counter()
        let session = RecorderSession(capture: capture, recover: { passes.next() }, onState: { _ in })

        await session.recoverIfIdle()
        XCTAssertEqual(passes.count, 1, "at launch")

        _ = try await session.start(workflowId: nil)
        XCTAssertEqual(passes.count, 2, "and again before a new recording — docs/03")

        await session.recoverIfIdle()
        XCTAssertEqual(passes.count, 2, "but never underneath one that is running")

        _ = await session.stop(title: nil)
        await session.recoverIfIdle()
        XCTAssertEqual(passes.count, 3)
    }
}

/// A capture that does nothing but count — and that can be held open at the exact moment a test
/// needs something else to arrive.
actor FakeCapture: Capture {
    private(set) var starts = 0
    private(set) var stops = 0
    private var startGate: CheckedContinuation<Void, Never>?
    private var stopGate: CheckedContinuation<Void, Never>?
    private var holdStart: (() -> Void)?
    private var holdStop: (() -> Void)?

    /// The next `start` waits inside itself until [releaseStart], calling [onHold] once it does.
    func holdNextStart(_ onHold: @escaping () -> Void) {
        holdStart = onHold
    }

    func holdNextStop(_ onHold: @escaping () -> Void) {
        holdStop = onHold
    }

    func releaseStart() {
        startGate?.resume()
        startGate = nil
    }

    func releaseStop() {
        stopGate?.resume()
        stopGate = nil
    }

    func start(workflowId: String?, title: String?, mode: RecordingMode, context: Context?) async throws -> String {
        starts += 1
        if let onHold = holdStart {
            holdStart = nil
            await withCheckedContinuation { continuation in
                startGate = continuation
                onHold()
            }
        }
        return "recording-\(starts)"
    }

    func stop(title: String?) async -> StopResult {
        stops += 1
        if let onHold = holdStop {
            holdStop = nil
            await withCheckedContinuation { continuation in
                stopGate = continuation
                onHold()
            }
        }
        return .finalized(RecordingOutcome(recordingId: "recording-\(starts)", durationSec: 1, parts: 1))
    }
}

/// What the session published to the shell, in order.
final class States {
    private let lock = NSLock()
    private var states: [RecorderState] = []

    func record(_ state: RecorderState) {
        lock.withLock { states.append(state) }
    }

    var all: [RecorderState] { lock.withLock { states } }
}

final class Counter {
    private let lock = NSLock()
    private var value = 0

    @discardableResult
    func next() -> Int {
        lock.withLock {
            value += 1
            return value
        }
    }

    var count: Int { lock.withLock { value } }
}
