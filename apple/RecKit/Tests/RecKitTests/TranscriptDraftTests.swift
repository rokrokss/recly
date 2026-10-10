import ReclyCore
import XCTest
@testable import RecKit

/// docs/08 "Editing" · docs/09 "Transcript reader" · "Editing and speakers": the reader's paragraphs keep the
/// segments they are made of, and the editor's draft becomes edits the core applies to the transcript the
/// user sees in the draft — checked against `TranscriptEdits.apply` itself, so a change of the core's
/// numbering rule fails here.
@MainActor
final class TranscriptDraftTests: XCTestCase {
    func testGroupsCutAtASpeakerChangeAndKeepTheirSegments() {
        let groups = TranscriptGroup.make(transcript(["S1", "S1", "S2", "S1"], texts: ["Hello", " ", "Hi", "Bye"]))
        XCTAssertEqual(groups.map(\.segments), [[0], [2], [3]])
        XCTAssertEqual(groups.map(\.speaker), ["S1", "S2", "S1"])
        XCTAssertEqual(TranscriptGroup.active(groups, at: 4.5)?.id, 1)
    }

    func testMatchesIgnoreCaseAndAccents() {
        let groups = TranscriptGroup.make(transcript(["S1"], texts: ["Résumé and resume"]))
        XCTAssertEqual(TranscriptMatch.all("RESUME", in: groups).count, 2)
    }

    /// The core counts in UTF-16 code units, and so does the find bar: an emoji is two of them.
    func testMatchesAreInUtf16Units() {
        let groups = TranscriptGroup.make(transcript(["S1"], texts: ["👋 Résumé"]))
        let match = TranscriptMatch.all("resume", in: groups).first
        XCTAssertEqual(match?.offset, 3)
        XCTAssertEqual(match?.length, 6)
    }

    func testNewSpeakersOnAnUnidentifiedTranscriptBecomeTheCoresOwn() throws {
        let original = transcript(["", "", ""], texts: ["One", "Two", "Three"])
        let draft = TranscriptDraft(original)
        draft.setSpeaker(0, nil)        // S1 everywhere, as the core does
        draft.setSpeaker(2, nil)        // S2 for the last line
        draft.rename("S2", "Minsu")
        XCTAssertEqual(draft.speakers, ["S1", "S1", "S2"])

        let edited = try apply(draft.edits, to: original)
        XCTAssertEqual(edited.segments.map(\.speaker), ["S1", "S1", "S2"])
        XCTAssertEqual(edited.speakers.first { $0.id == "S2" }?.name, "Minsu")
    }

    func testASpeakerMadeAndLeftUnusedDoesNotShiftTheNextOne() throws {
        let original = transcript(["S1", "S2", "S1"], texts: ["One", "Two", "Three"])
        let draft = TranscriptDraft(original)
        draft.setSpeaker(0, nil)        // S3…
        draft.setSpeaker(0, "S1")       // …abandoned
        draft.setSpeaker(2, nil)        // S4 in the draft, S3 in the core
        draft.texts[1] = "Two, edited"

        let edited = try apply(draft.edits, to: original)
        XCTAssertEqual(edited.segments.map(\.speaker), ["S1", "S2", "S3"])
        XCTAssertEqual(edited.segments[1].text, "Two, edited")
    }

    func testSkipSilenceJumpsToTheEndOfTheSilenceItIsIn() {
        let silences = [SilentRange(startSec: 2, endSec: 5)]
        XCTAssertEqual(RecordingPlayer.silenceEnd(silences, at: 3), 5)
        XCTAssertNil(RecordingPlayer.silenceEnd(silences, at: 4.95))
        XCTAssertNil(RecordingPlayer.silenceEnd(silences, at: 6))
    }

    /// docs/08 "Me and others": the person who made the recording reads `Me` until they are given a name — in
    /// the reader and in the editor's draft — and a rename keeps them the one who made it.
    func testTheRecordingsMakerReadsMeUntilNamed() throws {
        let plain = transcript(["S1", "S2"], texts: ["Hello", "Hi"])
        let mine = Transcript(
            schema: plain.schema, recordingId: plain.recordingId, track: plain.track, language: plain.language,
            provider: plain.provider, createdAt: plain.createdAt, editedAt: nil, durationSec: plain.durationSec,
            speakers: [TranscriptSpeaker(id: "S1", name: nil, me: true), TranscriptSpeaker(id: "S2", name: nil, me: false)],
            segments: plain.segments, speakerIdentification: nil, timing: nil
        )
        XCTAssertEqual(mine.label(of: "S1"), RecKitStrings.localized("Me"))
        XCTAssertEqual(mine.label(of: "S2"), "S2")
        XCTAssertEqual(plain.label(of: "S1"), "S1")

        let draft = TranscriptDraft(mine)
        XCTAssertEqual(draft.label("S1"), RecKitStrings.localized("Me"))
        draft.rename("S1", "Hyungrok")
        XCTAssertEqual(draft.label("S1"), "Hyungrok")
        let renamed = try apply(draft.edits, to: mine)
        XCTAssertEqual(renamed.label(of: "S1"), "Hyungrok")
        XCTAssertEqual(renamed.speakers.first { $0.id == "S1" }?.me?.boolValue, true)

        AppLanguage.current = .ko
        defer { AppLanguage.current = .system }
        XCTAssertEqual(mine.label(of: "S1"), "나")
    }

    private func apply(_ edits: [any TranscriptEdit], to transcript: Transcript) throws -> Transcript {
        TranscriptEdits.shared.apply(transcript: transcript, edit: TranscriptEditBatch(edits: edits), editedAt: "2026-10-07T00:00:00.000Z")
    }

    private func transcript(_ speakers: [String], texts: [String]) -> Transcript {
        let segments = zip(speakers.indices, zip(speakers, texts)).map { index, pair in
            TranscriptSegment(start: Double(index * 2), end: Double(index * 2 + 2), speaker: pair.0, text: pair.1, words: nil)
        }
        let ids = Array(Set(speakers.filter { !$0.isEmpty })).sorted()
        return Transcript(
            schema: 1, recordingId: "01J9ABCDEF", track: .mono, language: "en",
            provider: TranscriptProvider(name: "test", model: nil, jobRef: nil), createdAt: "2026-10-07T00:00:00.000Z",
            editedAt: nil, durationSec: Double(speakers.count * 2),
            speakers: ids.map { TranscriptSpeaker(id: $0, name: nil, me: nil) }, segments: segments,
            speakerIdentification: nil, timing: nil
        )
    }
}
