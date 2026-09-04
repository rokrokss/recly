import AppIntents
import XCTest

/// docs/13 I7: an intent is one call into the model the screen is drawn from — Siri, the action
/// button, the Control and the Live Activity's stop button all arrive here — and what is worth
/// checking about it is exactly that: which call, with what, and that a workflow the user named is
/// carried through rather than dropped.
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

    func testTheStartIntentStartsTheWorkflowItWasGiven() async throws {
        _ = try await StartRecordingIntent(workflow: WorkflowEntity(id: "w2", name: "회의")).perform()

        XCTAssertEqual(commands.started, ["w2"])
        XCTAssertEqual(commands.stops, 0)
    }

    /// ADR-016: no workflow named is the source's default, which is `nil` all the way down to
    /// `enqueue` — not an error and not a picker the user has to answer before recording.
    func testTheStartIntentWithNoWorkflowStartsTheDefault() async throws {
        _ = try await StartRecordingIntent().perform()

        XCTAssertEqual(commands.started, [nil])
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

        XCTAssertTrue(commands.started.isEmpty)
    }

    func testTheStopIntentStopsTheRecording() async throws {
        _ = try await StopRecordingIntent().perform()

        XCTAssertEqual(commands.stops, 1)
        XCTAssertTrue(commands.started.isEmpty)
    }

    /// The app has not opened the core yet — the process was launched a moment ago to serve this —
    /// so there is nothing registered. An intent must come back rather than crash the launch.
    func testAnIntentWithNoModelYetDoesNothing() async throws {
        RecordingIntentTarget.commands = nil

        _ = try await StopRecordingIntent().perform()
        _ = try await StartRecordingIntent().perform()
    }

    /// The workflow picker Siri and the Shortcuts editor show is the app's own list — the phone's
    /// workflows, by the ids the model would start.
    func testTheWorkflowQueryOffersWhatTheModelCanRun() async throws {
        commands.workflows = [
            WorkflowChoice(id: "w1", name: "기본"),
            WorkflowChoice(id: "w2", name: "회의"),
        ]

        let suggested = try await WorkflowChoiceQuery().suggestedEntities()
        XCTAssertEqual(suggested.map(\.id), ["w1", "w2"])
        XCTAssertEqual(suggested.map(\.name), ["기본", "회의"])

        let named = try await WorkflowChoiceQuery().entities(for: ["w2"])
        XCTAssertEqual(named.map(\.id), ["w2"])
    }
}

/// The model, minus the microphone and the core.
@MainActor
final class FakeRecordingCommands: RecordingCommands {
    var workflows: [WorkflowChoice] = []
    /// What the model would refuse the next background start with, or nil to let it through
    /// (docs/12 M8: the consent reminder is still owed).
    var refusal: String?
    private(set) var started: [String?] = []
    private(set) var stops = 0

    func recordableWorkflows() async -> [WorkflowChoice] {
        workflows
    }

    func startFromIntent(workflowId: String?) async -> String? {
        if let refusal { return refusal }
        started.append(workflowId)
        return nil
    }

    func stopFromIntent() async {
        stops += 1
    }
}
