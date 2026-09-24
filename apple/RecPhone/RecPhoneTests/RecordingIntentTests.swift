import AppIntents
import XCTest

/// docs/13 I7: an intent is one call into the model the screen is drawn from — Siri, the action
/// button, the Control and the Live Activity's stop button all arrive here — and what is worth
/// checking about it is exactly that: which call, and that a refusal is reported.
@MainActor
final class RecordingIntentTests: XCTestCase {
    private var commands: FakeRecordingCommands!

    override func setUp() async throws {
        commands = FakeRecordingCommands()
        RecordingIntentTarget.commands = commands
    }

    override func tearDown() async throws {
        RecordingIntentTarget.commands = nil
    }

    /// docs/05: the intent starts with the same fixed settings as the recording screen.
    func testTheStartIntentStartsARecording() async throws {
        _ = try await StartRecordingIntent().perform()

        XCTAssertEqual(commands.starts, 1)
        XCTAssertEqual(commands.stops, 0)
    }

    /// docs/12 M8 · ADR-011: the consent reminder is a question, and an intent is served with the
    /// phone locked and nobody to answer it. The regression was that the intent recorded anyway —
    /// so a first meeting could be recorded by saying "Hey Siri" without the reminder the screen's
    /// own start cannot get past. The refusal is reported rather than swallowed, so Siri says it.
    func testAStartTheModelRefusesReportsWhyAndRecordsNothing() async throws {
        commands.refusal = "Open Recly once to answer the recording reminder"

        do {
            _ = try await StartRecordingIntent().perform()
            XCTFail("the intent reported success over a start that never happened")
        } catch let refused as RecordingRefused {
            XCTAssertEqual(refused.reason, "Open Recly once to answer the recording reminder")
        }

        XCTAssertEqual(commands.starts, 0)
    }

    func testTheStopIntentStopsTheRecording() async throws {
        _ = try await StopRecordingIntent().perform()

        XCTAssertEqual(commands.stops, 1)
        XCTAssertEqual(commands.starts, 0)
    }

    /// The app has not opened the core yet — the process was launched a moment ago to serve this —
    /// so there is nothing registered. An intent must come back rather than crash the launch.
    func testAnIntentWithNoModelYetDoesNothing() async throws {
        RecordingIntentTarget.commands = nil

        _ = try await StopRecordingIntent().perform()
        _ = try await StartRecordingIntent().perform()
    }
}

/// The model, minus the microphone and the core.
@MainActor
final class FakeRecordingCommands: RecordingCommands {
    /// What the model would refuse the next background start with, or nil to let it through
    /// (docs/12 M8: the consent reminder is still owed).
    var refusal: String?
    private(set) var starts = 0
    private(set) var stops = 0

    func startFromIntent() async -> String? {
        if let refusal { return refusal }
        starts += 1
        return nil
    }

    func stopFromIntent() async {
        stops += 1
    }
}
