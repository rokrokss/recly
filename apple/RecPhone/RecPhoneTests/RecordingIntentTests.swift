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

    /// docs/03: a start the model refuses — a disconnect is running — is reported rather than swallowed, so Siri
    /// says it and Shortcuts shows it.
    func testAStartTheModelRefusesReportsWhyAndRecordsNothing() async throws {
        commands.refusal = "Wait for the disconnect to finish"

        do {
            _ = try await StartRecordingIntent().perform()
            XCTFail("the intent reported success over a start that never happened")
        } catch let refused as RecordingRefused {
            XCTAssertEqual(refused.reason, "Wait for the disconnect to finish")
        }

        XCTAssertEqual(commands.starts, 0)
    }

    /// docs/09 "Highlights": "Add a highlight with Recly" marks the running recording — one call into the model.
    func testTheHighlightIntentMarksTheRecording() async throws {
        _ = try await AddHighlightIntent().perform()

        XCTAssertEqual(commands.highlights, 1)
        XCTAssertEqual(commands.starts, 0)
        XCTAssertEqual(commands.stops, 0)
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
        _ = try await AddHighlightIntent().perform()
    }
}

/// The model, minus the microphone and the core.
@MainActor
final class FakeRecordingCommands: RecordingCommands {
    /// What the model would refuse the next start with, or nil to let it through (docs/03: a disconnect is
    /// running).
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

    private(set) var highlights = 0

    func highlightFromIntent() async {
        highlights += 1
    }
}
