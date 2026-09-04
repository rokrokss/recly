import ReclyCore
import XCTest
@testable import RecKit

/// The write gate every document mutation goes through (docs/11 A6). The gate itself is the core's
/// `recly.core.workflow.WorkflowMutator`, shared with Android and Windows and unit-tested on the
/// JVM; what these exercise is the Apple half of it — a Swift `WorkflowDocuments`, a Swift change
/// handed over as a Kotlin `suspend` block — because a rule that holds in Kotlin and loses the
/// document on the way across the bridge is still a lost document.
@MainActor
final class WorkflowMutatorTests: XCTestCase {
    private let now = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_821_200_000)
    private let opened = "2026-08-01T00:00:00.000Z"

    private func workflow(
        _ id: String,
        updatedAt: String? = nil,
        name: String? = nil
    ) -> Workflow {
        Workflow(
            id: id,
            name: name ?? id,
            updatedAt: updatedAt ?? opened,
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
            revision: 3,
            updatedAt: opened,
            updatedBy: "device-a",
            workflows: workflows
        )
    }

    /// Renames on the document it is handed — never on one captured before.
    private func renamed(_ document: WorkflowsDocument, _ id: String) -> WorkflowsDocument? {
        guard let workflow = document.workflows.first(where: { $0.id == id }) else { return nil }
        var edit = workflow.toEdit()
        edit.name = "\(workflow.name) 2"
        return document.with(edit, now: now)
    }

    /// docs/08's `transcribe` step, for the round trip and the order constraint below.
    private func transcribing(_ id: String) -> Workflow {
        let workflow = self.workflow(id)
        return Workflow(
            id: workflow.id,
            name: workflow.name,
            updatedAt: workflow.updatedAt,
            minDurationSec: workflow.minDurationSec,
            steps: [
                workflow.steps[0],
                Step.Transcribe(
                    id: "stt",
                    onError: .abort,
                    retry: StepDefaults.retry,
                    provider: "clova",
                    secretRef: "clova_key",
                    invokeUrl: "https://clovaspeech-gw.example.com/external/v1/1234/abcd",
                    language: .koEn,
                    diarize: true,
                    speakers: Speakers(min: 2, max: 6),
                    model: nil
                ),
            ]
        )
    }

    /// M7-L3 deliverable 5. The editor holds every number and every optional string as text, so the
    /// risk is not that a save fails — it is that opening a `transcribe` step and saving it back
    /// quietly changes it. Nothing about it may move.
    func testATranscribeWorkflowSurvivesTheEditorsRoundTrip() async throws {
        let stored = transcribing("01J9ABCDEF0123456789ABCDEF")
        let documents = FakeDocuments(document([stored]))
        let mutator = WorkflowMutator(documents: documents)

        let result = try await mutator.mutate(
            expect: OpenedOn(id: stored.id, updatedAt: opened),
            block: DocumentMutation { doc in doc.with(stored.toEdit(), now: self.now) }
        )

        guard case .saved = onEnum(of: result) else { return XCTFail("expected saved, was \(result)") }
        XCTAssertEqual(documents.stored.workflows.first?.steps, stored.steps, "every step came back as it went in")
        let parsed = WorkflowParser.shared.parse(json: WorkflowParser.shared.serialize(doc: documents.stored))
        XCTAssertTrue(parsed is ParseResultOk, "the document the editor wrote is one the parser accepts")
    }

    /// docs/08's order constraint, which the editor reports before it offers to save.
    func testMovingATranscribeStepInFrontOfItsUploadIsReportedOnThatStep() {
        var edit = transcribing("01J9ABCDEF0123456789ABCDEF").toEdit()
        edit.steps = [edit.steps[1], edit.steps[0]]

        let order = WorkflowsModel.orderErrors(edit)

        XCTAssertEqual(order["stt"], WorkflowParser.shared.TRANSCRIBE_NEEDS_UPLOAD)
        XCTAssertNil(order["upload"], "the upload step has no order constraint of its own")
    }

    /// docs/08: a provider that never reads an invoke URL is one whose form hides the field.
    func testSwitchingATranscribeStepToAProviderWithNoInvokeUrlDropsIt() {
        var edit = StepEdit.TranscribeEdit(id: "stt")
        edit.provider = "rtzr"
        edit.secretRef = "rtzr_key"
        edit.invokeUrl = "https://clovaspeech-gw.example.com/external/v1/1/a"

        let step = StepEdit.transcribe(edit).toStep()

        guard case .transcribe(let transcribe) = onEnum(of: step) else {
            return XCTFail("expected transcribe, was \(step)")
        }
        XCTAssertNil(transcribe.invokeUrl, "a URL no form shows is a validation error nobody can fix")
    }

    /// docs/08: a provider that *may* be addressed by one still shows the field, so it is kept.
    func testAProviderThatOnlyAcceptsAnInvokeUrlKeepsTheOneThatWasTyped() {
        var edit = StepEdit.TranscribeEdit(id: "stt")
        edit.provider = "openai"
        edit.secretRef = "openai_key"
        edit.invokeUrl = "https://llm.example.com/v1"

        let step = StepEdit.transcribe(edit).toStep()

        guard case .transcribe(let transcribe) = onEnum(of: step) else {
            return XCTFail("expected transcribe, was \(step)")
        }
        XCTAssertEqual(transcribe.invokeUrl, "https://llm.example.com/v1")
    }

    /// An empty model means the provider's default, not an empty name (docs/08).
    func testAnEmptyModelMeansTheDefault() {
        var edit = StepEdit.TranscribeEdit(id: "stt")
        edit.secretRef = "rtzr_key"
        edit.provider = "rtzr"

        let step = StepEdit.transcribe(edit).toStep()

        guard case .transcribe(let transcribe) = onEnum(of: step) else {
            return XCTFail("expected transcribe, was \(step)")
        }
        XCTAssertNil(transcribe.model)
    }

    func testASaveIsRefusedWhenSomethingReplacedTheWorkflowUnderTheEditor() async throws {
        let documents = FakeDocuments(document([workflow("a")]))
        let mutator = WorkflowMutator(documents: documents)
        // The editor opened on this version; then an import replaced the workflow under it.
        documents.stored = document([workflow("a", updatedAt: "2026-08-27T08:00:00.000Z", name: "imported")])

        let result = try await mutator.mutate(
            expect: OpenedOn(id: "a", updatedAt: opened),
            block: DocumentMutation { _ in
                XCTFail("a stale editor must not build a document")
                return nil
            }
        )

        guard case .stale = onEnum(of: result) else { return XCTFail("expected stale, was \(result)") }
        XCTAssertTrue(documents.saves.isEmpty, "nothing was written")
        XCTAssertEqual(documents.stored.workflows.first?.name, "imported", "the import stands")
    }

    func testASaveIsRefusedWhenTheWorkflowIsNoLongerInTheDocument() async throws {
        let documents = FakeDocuments(document([workflow("a")]))
        let mutator = WorkflowMutator(documents: documents)
        documents.stored = document([])

        let result = try await mutator.mutate(
            expect: OpenedOn(id: "a", updatedAt: opened),
            block: DocumentMutation { _ in
                XCTFail("nothing to save onto")
                return nil
            }
        )

        guard case .stale = onEnum(of: result) else { return XCTFail("expected stale, was \(result)") }
        XCTAssertTrue(documents.saves.isEmpty, "nothing was written")
    }

    func testAWorkflowThatIsNotStoredYetHasNoVersionToBeStaleAgainst() async throws {
        let documents = FakeDocuments(document([workflow("a")]))
        let mutator = WorkflowMutator(documents: documents)

        let result = try await mutator.mutate(
            expect: nil,
            block: DocumentMutation { [self] in $0.with(workflow("new").toEdit(), now: now) }
        )

        guard case .saved = onEnum(of: result) else { return XCTFail("a new workflow saves: \(result)") }
        XCTAssertEqual(documents.stored.workflows.map(\.id), ["a", "new"])
    }

    /// Both start before either finishes: the fake suspends between the read and the write, which
    /// is exactly where an unserialized second mutation would read the pre-rename "a".
    func testTwoRenamesAtOnceKeepBothChanges() async throws {
        let documents = FakeDocuments(document([workflow("a"), workflow("b")]))
        let mutator = WorkflowMutator(documents: documents)

        async let first = mutator.mutate(
            expect: nil,
            block: DocumentMutation { [self] in renamed($0, "a") }
        )
        async let second = mutator.mutate(
            expect: nil,
            block: DocumentMutation { [self] in renamed($0, "b") }
        )
        _ = try await (first, second)

        XCTAssertEqual(documents.saves.count, 2, "each rename wrote once")
        XCTAssertEqual(
            documents.stored.workflows.map { "\($0.id)=\($0.name)" },
            ["a=a 2", "b=b 2"],
            "the last document written carries both renames, not just the last one"
        )
    }

    /// ADR-016 superseded the isDefault-undeletable rule: nothing in the document says which device
    /// runs a workflow, so no workflow is undeletable. The row warns; the write does not refuse.
    func testAnyWorkflowIsDeletedIncludingTheOneThisDeviceDefaultsTo() async throws {
        let documents = FakeDocuments(document([workflow("a"), workflow("b")]))
        let mutator = WorkflowMutator(documents: documents)

        let result = try await mutator.mutate(expect: nil, block: DocumentMutation { $0.without("a") })

        guard case .saved = onEnum(of: result) else { return XCTFail("expected saved, was \(result)") }
        XCTAssertEqual(documents.stored.workflows.map(\.id), ["b"])
    }

    func testAMutationThatFindsNothingToChangeWritesNothing() async throws {
        let documents = FakeDocuments(document([workflow("a")]))
        let mutator = WorkflowMutator(documents: documents)

        let result = try await mutator.mutate(
            expect: nil,
            block: DocumentMutation { [self] in renamed($0, "gone") }
        )

        guard case .skipped = onEnum(of: result) else { return XCTFail("expected skipped, was \(result)") }
        XCTAssertTrue(documents.saves.isEmpty)
    }

    /// A save takes as long as a push does, and the user does not wait for it: by the time it
    /// lands, the editor it was started from may be gone and another one open in its place.
    func testOnlyTheEditorASaveWasStartedFromIsStillItsOwn() {
        let sessions = EditorSessions()

        let editorA = sessions.open()
        let editorB = sessions.open()

        XCTAssertFalse(
            sessions.isCurrent(session: KotlinLong(longLong: editorA)),
            "A's save may not close the editor that replaced it"
        )
        XCTAssertTrue(sessions.isCurrent(session: KotlinLong(longLong: editorB)))
        XCTAssertFalse(sessions.isCurrent(session: nil), "a list write has no editor of its own")
        sessions.close()
        XCTAssertFalse(
            sessions.isCurrent(session: KotlinLong(longLong: editorB)),
            "nothing is current once the editor is gone"
        )
    }
}

/// `core.workflows` as the core's `WorkflowMutator` uses it, minus the parser — and, being a Swift
/// conformer to a Kotlin interface, the bridge the real `CoreWorkflowDocuments` crosses on every
/// write.
private final class FakeDocuments: ReclyCore.WorkflowDocuments {
    var stored: WorkflowsDocument

    private(set) var saves: [WorkflowsDocument] = []

    init(_ stored: WorkflowsDocument) {
        self.stored = stored
    }

    func __current() async throws -> WorkflowsDocument {
        await Task.yield()
        return stored
    }

    func __save(document: WorkflowsDocument) async throws -> any SaveResult {
        await Task.yield()
        saves.append(document)
        stored = document
        return SaveResultSaved(document: document)
    }
}
