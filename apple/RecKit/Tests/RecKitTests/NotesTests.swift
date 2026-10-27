import Foundation
import RecKitTestSupport
import ReclyCore
import SQLite3
import XCTest
@testable import RecKit

/// docs/08 "Organizing" · the P3 shell decisions (2026-10-27): what the notes block shows of a recording's notes —
/// the groups in their order, a group with nothing in it gone header and all, the whole block gone when there is
/// nothing — and how a row reads.
final class NoteGroupsTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheGroupsAreDecisionsToDosAndQuestionsInThatOrder() {
        let notes = Self.notes(
            decisions: [Self.item("d1", .decision)],
            todos: [Self.item("t1", .todo), Self.item("t2", .todo)],
            questions: [Self.item("q1", .question)]
        )
        let groups = NoteGroups.of(notes)
        XCTAssertEqual(groups.map(\.titleKey), ["Decisions", "To-dos", "Questions"])
        XCTAssertEqual(groups[1].items.map(\.id), ["t1", "t2"], "the core's order, newest first, is kept")
        XCTAssertEqual(groups.map { RecKitStrings.localized($0.titleKey) }, ["Decisions", "To-dos", "Questions"])
    }

    func testAGroupWithNoItemsIsNotThere() {
        let notes = Self.notes(questions: [Self.item("q1", .question)])
        XCTAssertEqual(NoteGroups.of(notes).map(\.titleKey), ["Questions"])
        XCTAssertFalse(NoteGroups.isEmpty(notes, showsOverview: false))
    }

    /// No empty state: with no item and no overview the block is absent.
    func testNothingToShowIsNoBlockAtAll() {
        XCTAssertTrue(NoteGroups.isEmpty(nil, showsOverview: true))
        XCTAssertTrue(NoteGroups.isEmpty(Self.notes(), showsOverview: true))
        XCTAssertTrue(NoteGroups.isEmpty(Self.notes(overview: "  \n "), showsOverview: true), "a blank overview is none")
        XCTAssertTrue(NoteGroups.of(nil).isEmpty)
    }

    /// The detail shows the overview, the recording screen does not: an overview alone is a block in one and nothing
    /// in the other.
    func testTheOverviewCountsOnlyWhereItIsShown() {
        let notes = Self.notes(overview: "A review that moved the launch to Friday.")
        XCTAssertFalse(NoteGroups.isEmpty(notes, showsOverview: true))
        XCTAssertEqual(NoteGroups.overview(notes, shown: true), "A review that moved the launch to Friday.")
        XCTAssertTrue(NoteGroups.isEmpty(notes, showsOverview: false))
        XCTAssertNil(NoteGroups.overview(notes, shown: false))
    }

    func testAnOwnerFollowsTheTextAfterADashAndTheDueIsItsOwn() {
        let owned = Self.item("t1", .todo, text: "Send the quote", owner: "Minji", due: "Friday")
        XCTAssertEqual(NoteGroups.text(owned), "Send the quote — Minji")
        XCTAssertEqual(NoteGroups.due(owned), "Friday")

        let bare = Self.item("t2", .todo, text: "Book the room", owner: " ", due: nil)
        XCTAssertEqual(NoteGroups.text(bare), "Book the room")
        XCTAssertNil(NoteGroups.due(bare))
    }

    /// The line the item came from: the one holding its quote first, then the one being said at its time, then the
    /// nearest.
    func testTheEmphasizedLineIsTheQuotedOneThenTheOneAtItsTime() {
        let lines = [
            Self.line(at: 100, end: 110, "We could try Thursday."),
            Self.line(at: 110, end: 118, "Let's move the launch to Friday then."),
            Self.line(at: 118, end: 125, "Fine by me."),
        ]
        let quoted = Self.item("d1", .decision, atSec: 101, quote: "move the launch to friday")
        XCTAssertEqual(NoteGroups.emphasized(lines, for: quoted), 1)

        let unquoted = Self.item("d2", .decision, atSec: 120, quote: "words nobody said")
        XCTAssertEqual(NoteGroups.emphasized(lines, for: unquoted), 2)

        let between = Self.item("d3", .decision, atSec: 140, quote: "")
        XCTAssertEqual(NoteGroups.emphasized(lines, for: between), 2)
        XCTAssertNil(NoteGroups.emphasized([], for: between))
    }

    /// A speaker the user named by that name, the recording's maker as `Me`, and a bare label as nothing.
    func testALineNamesItsSpeakerOnlyByAName() {
        XCTAssertEqual(NoteGroups.speaker(Self.line(at: 0, end: 1, "Hi", id: "S1", name: "Minji")), "Minji")
        XCTAssertEqual(NoteGroups.speaker(Self.line(at: 0, end: 1, "Hi", id: "S2", me: true)), "Me")
        XCTAssertNil(NoteGroups.speaker(Self.line(at: 0, end: 1, "Hi", id: "S3")))
        XCTAssertNil(NoteGroups.speaker(Self.line(at: 0, end: 1, "Hi")))
    }

    // MARK: - Pieces

    static func notes(
        overview: String? = nil,
        decisions: [NoteItem] = [],
        todos: [NoteItem] = [],
        questions: [NoteItem] = []
    ) -> RecordingNotes {
        RecordingNotes(
            recordingId: "01J9REC0000000000000000000",
            stage: .provisional,
            overview: overview,
            decisions: decisions,
            todos: todos,
            questions: questions,
            organizing: false
        )
    }

    static func item(
        _ id: String,
        _ kind: NoteKind,
        text: String = "Something",
        owner: String? = nil,
        due: String? = nil,
        atSec: Double = 0,
        quote: String = ""
    ) -> NoteItem {
        NoteItem(id: id, kind: kind, text: text, owner: owner, due: due, atSec: atSec, quote: quote, provisional: true)
    }

    static func line(at start: Double, end: Double, _ text: String, id: String? = nil, name: String? = nil, me: Bool = false) -> NoteLine {
        NoteLine(atSec: start, endSec: end, speakerId: id, speakerName: name, me: me, text: text, provisional: false)
    }
}

/// docs/08 "Organizing": the consent at a start, and what its two answers do — apart from the sheet and the panel
/// that ask it.
final class OrganizeConsentTests: XCTestCase {

    /// Only a start from the app's own screen asks, and only while the core says the question is unanswered.
    func testOnlyAScreenStartAsksWhileTheConsentIsMissing() {
        XCTAssertTrue(OrganizeConsent.asksAtStart(fromScreen: true, needsConsent: true))
        XCTAssertFalse(OrganizeConsent.asksAtStart(fromScreen: true, needsConsent: false))
        XCTAssertFalse(OrganizeConsent.asksAtStart(fromScreen: false, needsConsent: true), "Siri, a widget or the Control never ask")
        XCTAssertFalse(OrganizeConsent.asksAtStart(fromScreen: false, needsConsent: false))
    }

    /// Continue agrees and, on the iPhone, allows the plan as a destination in the same action.
    func testContinueAgreesAndAllowsTheDestinationWhereThereIsOne() async {
        var calls: [String] = []
        let agreed = await OrganizeConsent.apply(
            .agree,
            consent: { calls.append("consent") },
            allowDestination: { calls.append("grant") },
            signOut: { calls.append("signOut") }
        )
        XCTAssertTrue(agreed)
        XCTAssertEqual(calls, ["consent", "grant"])

        calls = []
        await OrganizeConsent.apply(.agree, consent: { calls.append("consent") }, allowDestination: nil, signOut: { calls.append("signOut") })
        XCTAssertEqual(calls, ["consent"], "the Mac has no destination to allow")
    }

    func testSignOutOnlySignsOut() async {
        var calls: [String] = []
        let answered = await OrganizeConsent.apply(
            .signOut,
            consent: { calls.append("consent") },
            allowDestination: { calls.append("grant") },
            signOut: { calls.append("signOut") }
        )
        XCTAssertTrue(answered)
        XCTAssertEqual(calls, ["signOut"])
    }

    /// An agreement that could not be written allows nothing either: the core asks again at the next start.
    func testAnAgreementThatFailsAllowsNothing() async {
        struct Refused: Error {}
        var calls: [String] = []
        let agreed = await OrganizeConsent.apply(
            .agree,
            consent: { throw Refused() },
            allowDestination: { calls.append("grant") },
            signOut: { calls.append("signOut") }
        )
        XCTAssertFalse(agreed)
        XCTAssertEqual(calls, [])
    }

    /// Signed out — the test core holds no ChatGPT sign-in — there is nothing to consent to.
    func testSignedOutNothingIsAsked() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("OrganizeConsentTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let bridge = try await CoreBridge.make(
            deviceName: "RecKitTests",
            dataDirectory: directory,
            databaseName: "organize-consent-tests.db",
            secureStore: InMemorySecureStore()
        )
        let needs = try await bridge.core.notes.needsConsent()
        XCTAssertFalse(needs.boolValue)
        let model = await ChatGptSettingsModel(core: bridge.core)
        let shown = await model.needsOrganizeConsent
        let asked = await model.organizeConsent
        XCTAssertFalse(shown)
        XCTAssertFalse(asked)
    }
}

/// docs/08 "Organizing" end to end through the core: a recording's notes as the detail reads them, the transcript
/// under a row, a delete that sticks — and a job with only its notes left, which the ledger reads as done and the
/// detail as nothing in the way.
final class RecordingNotesTests: XCTestCase {
    private var dataDirectory: URL!
    private let databaseName = "recording-notes-tests.db"

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecordingNotesTests-\(UUID().uuidString)", isDirectory: true)
        AppLanguage.current = .en
    }

    override func tearDownWithError() throws {
        AppLanguage.current = .system
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    @MainActor
    func testTheDetailFollowsTheNotesOpensTheLinesAndDeletesForGood() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge)
        try await writeTranscript(bridge, id)
        try await writeNotes(bridge, id)
        let model = RecordingDetailModel(core: bridge.core, recordingId: id, title: "Meeting")
        XCTAssertFalse(model.hasNotes)
        let follow = Task { await model.followNotes() }
        addTeardownBlock { follow.cancel(); await follow.value }
        try await waitFor { model.notes?.stage == .final }

        XCTAssertTrue(model.hasNotes)
        XCTAssertTrue(model.hasSummaryView, "the Summary chip stands for the notes alone")
        XCTAssertEqual(NoteGroups.overview(model.notes, shown: true), "The launch moved to Friday.")
        XCTAssertEqual(NoteGroups.of(model.notes).map(\.titleKey), ["Decisions", "To-dos"])
        let todo = try XCTUnwrap(model.notes?.todos.first)
        XCTAssertEqual(NoteGroups.text(todo), "Send the release notes — Minji", "the owner comes resolved to the name given")
        XCTAssertEqual(NoteGroups.due(todo), "Thursday")

        let lines = await model.noteLines(atSec: todo.atSec)
        XCTAssertEqual(lines.map(\.text), ["Let's move the launch to Friday then.", "I'll send the release notes by Thursday."])
        XCTAssertEqual(NoteGroups.emphasized(lines, for: todo), 1)
        XCTAssertEqual(lines.compactMap(NoteGroups.speaker), ["Me", "Minji"])

        model.deleteNote(todo)
        try await waitFor { model.notes?.todos.isEmpty == true }
        XCTAssertEqual(NoteGroups.of(model.notes).map(\.titleKey), ["Decisions"], "the emptied group goes with its header")
    }

    /// docs/08 "Organizing": a job whose only unfinished step is its notes — waiting out a usage limit here — reads as
    /// done, with nothing to explain or retry; the same job with its upload left reads as the wait it is.
    func testAJobWithOnlyItsNotesLeftReadsAsDone() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge)
        try insertJob(recordingId: id, upload: "SUCCEEDED", notes: "PENDING", notesError: "CHATGPT_USAGE_LIMIT")

        let rows = try await Recents.load(core: bridge.core)
        let row = try XCTUnwrap(rows.first { $0.id == id })
        XCTAssertEqual(row.state, "Done")
        XCTAssertEqual(row.badge.code, "DONE")
        XCTAssertNil(row.lastError)
        XCTAssertNil(row.alert)
        XCTAssertNil(row.nextRunAt)
        XCTAssertFalse(row.canRetry)
        XCTAssertFalse(Recents.uploading(rows), "the State node does not read the notes as an upload")
        XCTAssertEqual(Recents.summary(rows), "1")
    }

    func testAJobWithMoreThanItsNotesLeftIsStillWaiting() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge)
        try insertJob(recordingId: id, upload: "PENDING", notes: "PENDING", notesError: nil)

        let rows = try await Recents.load(core: bridge.core)
        let row = try XCTUnwrap(rows.first { $0.id == id })
        XCTAssertEqual(row.state, "Retry pending")
    }

    /// The detail's More menu: a job with only its notes left is no transcription in the way of an edit or a fill.
    @MainActor
    func testTheDetailIsNotBusyWithAJobThatHasOnlyItsNotesLeft() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge)
        try insertJob(recordingId: id, upload: "SUCCEEDED", notes: "PENDING", notesError: "CHATGPT_USAGE_LIMIT")
        let other = try await seed(bridge)
        try insertJob(recordingId: other, upload: "PENDING", notes: "PENDING", notesError: nil, jobId: "01J9JOB0000000000000000002")

        let notesOnly = RecordingDetailModel(core: bridge.core, recordingId: id, title: "Meeting")
        let busy = RecordingDetailModel(core: bridge.core, recordingId: other, title: "Other")
        let followNotesOnly = Task { await notesOnly.followJobs() }
        let followBusy = Task { await busy.followJobs() }
        addTeardownBlock {
            followNotesOnly.cancel(); followBusy.cancel()
            await followNotesOnly.value; await followBusy.value
        }
        try await waitFor { busy.transcriptionBusy }
        XCTAssertFalse(notesOnly.transcriptionBusy)
    }

    // MARK: - Pieces

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: databaseName,
            secureStore: InMemorySecureStore()
        )
    }

    private func waitFor(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<150 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        let met = await condition()
        XCTAssertTrue(met, "timed out")
    }

    /// A finalized recording and its directory.
    private func seed(_ bridge: CoreBridge) async throws -> String {
        let startedAt = bridge.deps.clock.now()
        let recordingId = Ulid.shared.generate(clock: FixedKotlinClock(startedAt))
        let meta = RecordingMeta(
            schema: 1,
            recordingId: recordingId,
            source: Source.phone,
            platform: Platform.ios,
            deviceId: bridge.deps.device.deviceId,
            deviceName: bridge.deps.device.name,
            workflowId: nil,
            title: nil,
            startedAt: startedAt.isoUtc,
            endedAt: startedAt.isoUtc,
            durationSec: 600,
            timezone: "Asia/Seoul",
            audio: AudioSettings(
                codec: Codec.aacLc,
                container: Container.m4A,
                sampleRateHz: 48_000,
                channels: 1,
                bitrateKbps: 96,
                segmentSec: 900
            ),
            tracks: [Track.mono],
            parts: [],
            gaps: [],
            silenced: [],
            context: nil,
            drive: nil,
            status: RecordingStatus.finalized,
            highlights: []
        )
        let directory = dataDirectory
            .appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(MetaWriter.shared.baseName(meta: meta), isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try await bridge.core.recordings.create(meta: meta, dir: directory.okioPath)
        return recordingId
    }

    private func base(_ bridge: CoreBridge, _ id: String) async throws -> (URL, String) {
        let found = try await bridge.core.recordings.get(id: id)
        let record = try XCTUnwrap(found)
        return (record.dir.url, MetaWriter.shared.baseName(meta: record.meta))
    }

    /// The recording's transcript: the user is `me`, the other speaker is named Minji.
    private func writeTranscript(_ bridge: CoreBridge, _ id: String) async throws {
        let (dir, base) = try await base(bridge, id)
        let json = """
        {"schema":1,"recordingId":"\(id)","track":"mono","language":"en",
         "provider":{"name":"assemblyai"},"createdAt":"2026-10-27T00:00:00.000Z","durationSec":600.0,
         "speakers":[{"id":"S1","me":true},{"id":"S2","name":"Minji"}],
         "segments":[
          {"start":300.0,"end":312.0,"speaker":"S1","text":"Let's move the launch to Friday then."},
          {"start":312.0,"end":320.0,"speaker":"S2","text":"I'll send the release notes by Thursday."},
          {"start":500.0,"end":510.0,"speaker":"S1","text":"Much later."}]}
        """
        try Data(json.utf8).write(to: dir.appendingPathComponent("\(base).transcript.json"))
    }

    /// The final notes as the core writes them (`spec/notes.schema.json`).
    private func writeNotes(_ bridge: CoreBridge, _ id: String) async throws {
        let (dir, base) = try await base(bridge, id)
        let json = """
        {"schema":1,"recordingId":"\(id)","stage":"final","overview":"The launch moved to Friday.",
         "items":[
          {"id":"01J9NOTE000000000000000001","kind":"decision","text":"The launch moves to Friday.",
           "evidence":{"atSec":300.0,"quote":"move the launch to Friday"},"provisional":false,"conversation":0,
           "createdAt":"2026-10-27T00:00:00.000Z"},
          {"id":"01J9NOTE000000000000000002","kind":"todo","text":"Send the release notes","owner":"S2","due":"Thursday",
           "evidence":{"atSec":313.0,"quote":"send the release notes by Thursday"},"provisional":false,"conversation":0,
           "createdAt":"2026-10-27T00:00:00.000Z"}],
         "tombstones":[],"conversations":[{"startSec":0.0,"endSec":600.0,"summary":"The launch moved to Friday."}],
         "parts":[],"model":"gpt-test","createdAt":"2026-10-27T00:00:00.000Z","updatedAt":"2026-10-27T00:00:00.000Z",
         "finalizedAt":"2026-10-27T00:00:00.000Z"}
        """
        try Data(json.utf8).write(to: dir.appendingPathComponent("\(base).notes.v1.json"))
    }

    /// A job of the fixed plan's shape, cut to the two steps the rule is about, written straight into the core's
    /// tables as the queue would leave it.
    private func insertJob(recordingId: String, upload: String, notes: String, notesError: String?, jobId: String = "01J9JOB0000000000000000001") throws {
        let workflow = #"{"id":"recly.processing","name":"Recly","updatedAt":"2026-10-27T00:00:00Z","steps":[{"type":"drive.upload","id":"upload"},{"type":"notes.organize","id":"notes"}]}"#
        let error = notesError.map { "'\($0)'" } ?? "NULL"
        try sql("""
            INSERT INTO job (id, recording_id, workflow_id, workflow_json, status, created_at, updated_at, next_run_at)
            VALUES ('\(jobId)', '\(recordingId)', 'recly.processing', '\(workflow)', 'WAITING',
                    '2026-10-27T00:00:00Z', '2026-10-27T00:00:00Z', '2026-10-27T05:00:00Z');
            INSERT INTO step_run (id, job_id, step_id, ordinal, status, attempts)
            VALUES ('\(jobId)-u', '\(jobId)', 'upload', 0, '\(upload)', 1);
            INSERT INTO step_run (id, job_id, step_id, ordinal, status, attempts, last_error)
            VALUES ('\(jobId)-n', '\(jobId)', 'notes', 1, '\(notes)', 1, \(error));
            """)
    }

    private func sql(_ statements: String) throws {
        var handle: OpaquePointer?
        let path = dataDirectory.appendingPathComponent(databaseName).path
        XCTAssertEqual(sqlite3_open(path, &handle), SQLITE_OK, "opening \(path)")
        defer { sqlite3_close(handle) }
        sqlite3_busy_timeout(handle, 5_000)
        XCTAssertEqual(sqlite3_exec(handle, statements, nil, nil, nil), SQLITE_OK, String(cString: sqlite3_errmsg(handle)))
    }
}
