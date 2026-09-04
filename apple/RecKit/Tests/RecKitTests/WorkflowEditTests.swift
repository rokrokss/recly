import ReclyCore
import XCTest
@testable import RecKit

/// docs/12 M7 deliverable 6: the editor state ↔ `WorkflowsDocument` mapping, the phone's
/// `WorkflowEditTest` in Swift.
final class WorkflowEditTests: XCTestCase {
    private let now = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_821_200_000)
    private let earlier = "2026-08-01T00:00:00.000Z"

    private var meeting: Workflow {
        Workflow(
            id: "01J9ABCDEF0123456789ABCDEF",
            name: "회의",
            updatedAt: earlier,
            minDurationSec: 30,
            steps: [
                Step.DriveUpload(
                    id: "upload",
                    onError: .abort,
                    retry: StepDefaults.retry,
                    folder: "recly/{{yyyy}}",
                    includeMeta: true
                ),
                Step.Webhook(
                    id: "hook",
                    onError: .continue,
                    retry: Retry(maxAttempts: 3, initialDelaySec: 10, maxDelaySec: 60),
                    url: "https://example.com/rec",
                    secretRef: "hook_main"
                ),
            ]
        )
    }

    private var memo: Workflow {
        Workflow(
            id: "01J9ABCDEF0123456789ABCDEG",
            name: "메모",
            updatedAt: earlier,
            minDurationSec: 0,
            steps: [
                Step.DriveUpload(
                    id: "upload",
                    onError: .abort,
                    retry: StepDefaults.retry,
                    folder: StepDefaults.folder,
                    includeMeta: true
                ),
            ]
        )
    }

    private func document(_ workflows: [Workflow]) -> WorkflowsDocument {
        WorkflowsDocument(
            schema: 3,
            revision: 4,
            updatedAt: earlier,
            updatedBy: "device-a",
            workflows: workflows
        )
    }

    func testAnUntouchedWorkflowSurvivesTheRoundTrip() {
        let workflow = meeting
        XCTAssertEqual(workflow, workflow.toEdit().toWorkflow(updatedAt: workflow.updatedAt))
    }

    func testSavingKeepsTheIdAndStampsOnlyTheEditedWorkflow() {
        let doc = document([meeting, memo])
        var edit = meeting.toEdit()
        edit.name = "회의 2"

        let saved = doc.with(edit, now: now)

        let edited = saved.workflows.first { $0.id == meeting.id }
        XCTAssertEqual(edited?.id, meeting.id, "the id is identity, not a name")
        XCTAssertEqual(edited?.name, "회의 2")
        XCTAssertEqual(edited?.updatedAt, "2026-08-27T09:00:00.000Z")
        // docs/05 merges per workflow: restamping one this edit never touched would let this device
        // win a last-write-wins race it never entered.
        XCTAssertEqual(saved.workflows.first { $0.id == memo.id }?.updatedAt, earlier)
        XCTAssertEqual(saved.revision, doc.revision, "the push stamps the document, not the editor")
        XCTAssertEqual(saved.updatedAt, doc.updatedAt)
    }

    func testAWorkflowTheDocumentDoesNotHaveIsAppended() {
        let fresh = WorkflowEdit(
            id: "01J9ABCDEF0123456789ABCDEH",
            name: "새로",
            minDurationSec: "0",
            steps: [.drive(StepEdit.DriveEdit(id: "upload"))]
        )

        let saved = document([meeting]).with(fresh, now: now)

        XCTAssertEqual(saved.workflows.map(\.id), [meeting.id, fresh.id])
        XCTAssertEqual(saved.workflows.last?.updatedAt, "2026-08-27T09:00:00.000Z")
    }

    /// The editor's only judge of what is valid is `save()`, which is the parser (docs/02).
    func testTheMappedDocumentIsWhatTheParserAccepts() throws {
        var edit = meeting.toEdit()
        edit.name = " 여백 "
        let saved = document([meeting]).with(edit, now: now)

        let parsed = WorkflowParser.shared.parse(json: WorkflowParser.shared.serialize(doc: saved))

        guard case .ok(let ok) = onEnum(of: parsed) else { return XCTFail("expected Ok, was \(parsed)") }
        XCTAssertEqual(ok.document.workflows.first?.name, "여백", "the name is trimmed on the way in")
    }

    func testTextThatIsNotANumberBecomesAValueTheParserRejects() {
        var edit = meeting.toEdit()
        edit.minDurationSec = "삼십"
        var hook = StepEdit.HookEdit(id: "hook")
        hook.url = "https://x.test"
        hook.retry.maxAttempts = ""
        edit.steps = [.hook(hook)]

        let saved = document([meeting]).with(edit, now: now)
        let parsed = WorkflowParser.shared.parse(json: WorkflowParser.shared.serialize(doc: saved))

        guard case .invalid(let invalid) = onEnum(of: parsed) else {
            return XCTFail("expected Invalid, was \(parsed)")
        }
        XCTAssertTrue(
            invalid.errors.contains { $0.contains("minDurationSec") }
                && invalid.errors.contains { $0.contains("retry.maxAttempts") },
            "both bad fields are reported: \(invalid.errors)"
        )
    }

    func testABlankMinimumLengthIsZeroNotAnError() {
        var edit = meeting.toEdit()
        edit.minDurationSec = ""

        let saved = document([meeting]).with(edit, now: now)

        XCTAssertEqual(saved.workflows.first?.minDurationSec, 0)
    }

    /// ADR-016 left the head of the graph one thing to say, and a workflow with no minimum says
    /// nothing at all rather than `>= 0s`.
    func testTheMinimumIsACodeAndAZeroMinimumIsNoCodeAtAll() {
        var edit = meeting.toEdit()

        XCTAssertEqual(edit.minimumCode, ">= 30s")
        edit.minDurationSec = "0"
        XCTAssertEqual(edit.minimumCode, "")
        edit.minDurationSec = ""
        XCTAssertEqual(edit.minimumCode, "")
    }

    func testDeletingRemovesOnlyThatWorkflow() {
        let left = document([meeting, memo]).without(meeting.id)

        XCTAssertEqual(left.workflows.map(\.id), [memo.id])
    }

    func testABlankSecretRefIsNoSecretAtAll() {
        var hook = StepEdit.HookEdit(id: "hook")
        hook.url = "https://x.test"
        hook.secretRef = "  "
        var edit = meeting.toEdit()
        edit.steps = [.hook(hook)]

        let step = edit.toWorkflow(updatedAt: earlier).steps.first
        guard case .webhook(let webhook) = onEnum(of: step!) else { return XCTFail("expected a webhook") }

        XCTAssertNil(webhook.secretRef, "an empty pick is 'no signature', not a name of ''")
    }

    /// The editor holds every optional string as text, so the risk is not that a save fails — it is
    /// that opening a step and saving it back quietly changes it. Whitespace is not an empty field:
    /// the parser only refuses an empty `model`.
    func testAnUntouchedModelSurvivesOpenAndSave() {
        let steps: [Step] = [
            Step.Transcribe(
                id: "stt",
                onError: .abort,
                retry: StepDefaults.retry,
                provider: "rtzr",
                secretRef: "rtzr_key",
                invokeUrl: nil,
                language: .ko,
                diarize: true,
                speakers: Speakers(min: 1, max: 10),
                model: " x "
            ),
        ]

        let saved = steps.map { $0.toEdit().toStep() }

        XCTAssertEqual(saved, steps, "every step came back as it went in")
    }

    func testANewStepIdNeverCollidesWithOneAlreadyInTheWorkflow() {
        XCTAssertEqual(nextStepId(base: "hook", taken: []), "hook")
        XCTAssertEqual(nextStepId(base: "hook", taken: ["hook"]), "hook2")
        XCTAssertEqual(nextStepId(base: "hook", taken: ["hook", "hook2"]), "hook3")
    }

    /// docs/05 "시크릿": the name is the only part that ever leaves this device, so it obeys the
    /// same rule the parser enforces on `secretRef`.
    func testSecretNamesFollowTheSpecRule() {
        XCTAssertNil(SecretName.problem("hook_main"))
        XCTAssertNotNil(SecretName.problem(""))
        XCTAssertNotNil(SecretName.problem("Hook"))
        XCTAssertNotNil(SecretName.problem("9hook"))
        XCTAssertNotNil(SecretName.problem("hook-main"))
        XCTAssertNotNil(SecretName.problem("hook", existing: ["hook"]))
    }

    /// One Drive upload per workflow: the dialog offers a second one greyed out, a webhook always.
    func testSecondDriveUploadIsNotOffered() {
        let uploaded = [StepKind.drive.newStep(taken: [])]

        XCTAssertTrue(StepKind.drive.canAdd(to: []))
        XCTAssertFalse(StepKind.drive.canAdd(to: uploaded))
        XCTAssertTrue(StepKind.hook.canAdd(to: uploaded))
        XCTAssertTrue(StepKind.transcribe.canAdd(to: uploaded))
        XCTAssertTrue(StepKind.hook.canAdd(to: uploaded + [StepKind.hook.newStep(taken: ["upload"])]))
    }
}
