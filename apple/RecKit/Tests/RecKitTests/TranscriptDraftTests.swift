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
            speakers: ids.map { TranscriptSpeaker(id: $0, name: nil) }, segments: segments,
            speakerIdentification: nil, timing: nil
        )
    }
}
