import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/08 "결과 파일": what the detail plays back, chosen out of `meta.json` alone — the track a
/// person means, its parts in order, and only the files this device still has.
final class RecordingPlaylistTests: XCTestCase {
    private let dir = URL(fileURLWithPath: "/recordings/01J9REC", isDirectory: true)

    /// A meeting: `mic` and `sys` are the mix's own ingredients, and playing either on its own is
    /// half of what was said.
    func testTheMixIsPlayedWhenTheRecordingHasOne() {
        let selection = RecordingPlaylist.select(
            tracks: [Track.mic, Track.sys, Track.mix],
            parts: [
                part(1, .mic, "p001_mic.m4a", 10),
                part(1, .sys, "p001_sys.m4a", 10),
                part(1, .mix, "p001_mix.m4a", 10),
            ],
            dir: dir,
            exists: { _ in true }
        )

        XCTAssertEqual(selection.urls, [dir.appendingPathComponent("p001_mix.m4a")])
        XCTAssertEqual(selection.totalSec, 10)
    }

    /// A memo has one track and it is the whole recording.
    func testMonoIsPlayedWhenThereIsNoMix() {
        let selection = RecordingPlaylist.select(
            tracks: [Track.mono],
            parts: [part(1, .mono, "p001_mono.m4a", 42)],
            dir: dir,
            exists: { _ in true }
        )

        XCTAssertEqual(selection.urls, [dir.appendingPathComponent("p001_mono.m4a")])
        XCTAssertEqual(selection.totalSec, 42)
    }

    /// The parts are one recording end to end, so they are played in `part` order however
    /// `meta.json` happens to list them.
    func testThePartsArePlayedInPartOrder() {
        let selection = RecordingPlaylist.select(
            tracks: [Track.mono],
            parts: [
                part(3, .mono, "p003_mono.m4a", 5),
                part(1, .mono, "p001_mono.m4a", 300),
                part(2, .mono, "p002_mono.m4a", 300),
            ],
            dir: dir,
            exists: { _ in true }
        )

        XCTAssertEqual(
            selection.urls.map(\.lastPathComponent),
            ["p001_mono.m4a", "p002_mono.m4a", "p003_mono.m4a"]
        )
        XCTAssertEqual(selection.durations, [300, 300, 5])
        XCTAssertEqual(selection.totalSec, 605)
    }

    /// docs/03: an upload may have purged a part already. What is gone is dropped rather than
    /// played as silence — and its seconds go with it, so the clock counts what is there.
    func testAPartWhoseFileIsGoneIsDropped() {
        let selection = RecordingPlaylist.select(
            tracks: [Track.mono],
            parts: [
                part(1, .mono, "p001_mono.m4a", 300),
                part(2, .mono, "p002_mono.m4a", 120),
            ],
            dir: dir,
            exists: { $0.lastPathComponent == "p002_mono.m4a" }
        )

        XCTAssertEqual(selection.urls.map(\.lastPathComponent), ["p002_mono.m4a"])
        XCTAssertEqual(selection.totalSec, 120)
    }

    /// Every part purged is a recording with nothing to play, which is what the detail says in
    /// words instead of offering a button that would do nothing.
    func testNothingLeftOnThisDeviceIsAnEmptySelection() {
        let selection = RecordingPlaylist.select(
            tracks: [Track.mono],
            parts: [part(1, .mono, "p001_mono.m4a", 300)],
            dir: dir,
            exists: { _ in false }
        )

        XCTAssertTrue(selection.isEmpty)
        XCTAssertEqual(selection.totalSec, 0)
        XCTAssertEqual(selection, .empty)
    }

    // MARK: - Falling back to Drive (docs/03 ADR-017)

    /// Every part is here, so there is nothing to go and get — the seven-day window has not taken
    /// anything yet, and a trip to Drive would be a network round trip for a file already on disk.
    func testAWholePlaylistIsNotFetched() {
        XCTAssertFalse(RecordingPlaylist.fetchesFromDrive(local: 3, track: 3, uploaded: true))
    }

    /// The sweep took a part and Drive has it: that is the whole case this fallback exists for.
    func testAGapInAnUploadedRecordingIsFetched() {
        XCTAssertTrue(RecordingPlaylist.fetchesFromDrive(local: 1, track: 3, uploaded: true))
        XCTAssertTrue(
            RecordingPlaylist.fetchesFromDrive(local: 0, track: 1, uploaded: true),
            "nothing local at all is still a gap Drive can fill"
        )
    }

    /// A recording Drive never got has nothing there to ask for — the files are simply gone, and
    /// the page says so rather than spending a round trip to find out.
    func testAGapInARecordingDriveNeverGotIsNotFetched() {
        XCTAssertFalse(RecordingPlaylist.fetchesFromDrive(local: 1, track: 3, uploaded: false))
        XCTAssertFalse(RecordingPlaylist.fetchesFromDrive(local: 0, track: 2, uploaded: false))
    }

    /// What comes back from Drive is the same parts under the same names, so the clock still counts
    /// in `meta.json`'s seconds.
    func testAFetchedPlaylistKeepsTheDurationsMetaRecorded() {
        let selection = RecordingPlaylist.fetched(
            parts: [
                part(1, .mono, "p001_mono.m4a", 300),
                part(2, .mono, "p002_mono.m4a", 120),
                part(3, .mono, "p003_mono.m4a", 5),
            ],
            files: ["p001_mono.m4a", "p002_mono.m4a", "p003_mono.m4a"],
            dir: dir
        )

        XCTAssertEqual(
            selection.urls.map(\.lastPathComponent),
            ["p001_mono.m4a", "p002_mono.m4a", "p003_mono.m4a"]
        )
        XCTAssertEqual(selection.durations, [300, 120, 5])
        XCTAssertEqual(selection.totalSec, 425)
    }

    /// A part that stayed missing ends the playlist there rather than being skipped over: what
    /// plays is the start of the recording, on the clock the transcript below is indexed against.
    /// Part 3 after part 1 would put 5:00 of the recording at 0:00 of everything after the gap.
    func testAFetchThatMissedAPartStopsAtTheGap() {
        let selection = RecordingPlaylist.fetched(
            parts: [
                part(1, .mono, "p001_mono.m4a", 300),
                part(2, .mono, "p002_mono.m4a", 120),
                part(3, .mono, "p003_mono.m4a", 5),
            ],
            files: ["p001_mono.m4a", "p003_mono.m4a"],
            dir: dir
        )

        XCTAssertEqual(selection.urls.map(\.lastPathComponent), ["p001_mono.m4a"])
        XCTAssertEqual(selection.durations, [300])
        XCTAssertEqual(selection.totalSec, 300, "the clock counts what is actually playable")
    }

    /// The gap at the very front is a recording with nothing playable in it at all, however much of
    /// the rest came back.
    func testAFetchThatMissedTheFirstPartPlaysNothing() {
        let selection = RecordingPlaylist.fetched(
            parts: [
                part(1, .mono, "p001_mono.m4a", 300),
                part(2, .mono, "p002_mono.m4a", 120),
            ],
            files: ["p002_mono.m4a"],
            dir: dir
        )

        XCTAssertEqual(selection, .empty)
    }

    /// `core.audio` hands the files back in part order, but the parts of `meta.json` are in
    /// whatever order it listed them — the playlist is the recording's order either way.
    func testAFetchedPlaylistIsInPartOrder() {
        let selection = RecordingPlaylist.fetched(
            parts: [
                part(2, .mono, "p002_mono.m4a", 120),
                part(1, .mono, "p001_mono.m4a", 300),
            ],
            files: ["p001_mono.m4a", "p002_mono.m4a"],
            dir: dir
        )

        XCTAssertEqual(
            selection.urls.map(\.lastPathComponent),
            ["p001_mono.m4a", "p002_mono.m4a"]
        )
        XCTAssertEqual(selection.durations, [300, 120])
    }

    /// A fetch that came back with nothing is the empty selection the bar already has words for.
    func testAFetchThatBroughtNothingBackIsEmpty() {
        let selection = RecordingPlaylist.fetched(
            parts: [part(1, .mono, "p001_mono.m4a", 300)],
            files: [],
            dir: dir
        )

        XCTAssertEqual(selection, .empty)
    }

    // MARK: - The clock

    /// The position is the recording's, not the part's: two finished parts and eleven seconds into
    /// the third is 10:11, not 0:11.
    func testTheClockCountsTheFinishedPartsAsWellAsTheCurrentOne() {
        XCTAssertEqual(
            RecordingPlayer.position(durations: [300, 300, 120], finished: 2, itemSec: 11),
            611
        )
    }

    /// Before anything has finished there is only the current item, and a queue that has not been
    /// asked for a time yet reports one that is not a number.
    func testTheClockStartsAtTheCurrentItemAndSurvivesANaNTime() {
        XCTAssertEqual(RecordingPlayer.position(durations: [300], finished: 0, itemSec: 7), 7)
        XCTAssertEqual(RecordingPlayer.position(durations: [300], finished: 0, itemSec: .nan), 0)
    }

    // MARK: - Scrubbing (docs/09 화면 원칙 2)

    /// The inverse of the clock: a drag on the waveform gives a second of the *recording*, and what
    /// the player needs is a part and an offset into it.
    func testASecondInsideTheFirstPartIsThatPart() {
        let target = RecordingPlayer.target(durations: [300, 300, 120], sec: 11)

        XCTAssertEqual(target.index, 0)
        XCTAssertEqual(target.offsetSec, 11)
    }

    /// And one after two whole parts is 11 seconds into the third, not 611 into anything.
    func testASecondPastTwoPartsIsTheThirdPart() {
        let target = RecordingPlayer.target(durations: [300, 300, 120], sec: 611)

        XCTAssertEqual(target.index, 2)
        XCTAssertEqual(target.offsetSec, 11)
    }

    /// A boundary belongs to the part that is starting, not to the one that just ended: dropped on
    /// the far end of a part, the playhead plays on rather than stopping where it landed.
    func testAPartsLastInstantIsTheNextPartsFirst() {
        let target = RecordingPlayer.target(durations: [300, 120], sec: 300)

        XCTAssertEqual(target.index, 1)
        XCTAssertEqual(target.offsetSec, 0)
    }

    /// Dragged off the end of the row — which the seek's own clamp already stops at `totalSec` —
    /// the target is the end of the last part rather than an index there is no part for.
    func testPastTheEndIsTheEndOfTheLastPart() {
        let target = RecordingPlayer.target(durations: [300, 120], sec: 900)

        XCTAssertEqual(target.index, 1)
        XCTAssertEqual(target.offsetSec, 120)
    }

    /// A recording with nothing playable in it has no part to scrub to, and asking for one is the
    /// start of nothing rather than a read off the end of an empty array.
    func testAnEmptyRecordingHasNoPartToSeekInto() {
        let target = RecordingPlayer.target(durations: [], sec: 42)

        XCTAssertEqual(target.index, 0)
        XCTAssertEqual(target.offsetSec, 0)
    }

    // MARK: - Pieces

    private func part(_ index: Int32, _ track: Track, _ file: String, _ durationSec: Double) -> Part_ {
        Part_(
            part: index,
            track: track,
            file: file,
            bytes: 1024,
            sha256: String(repeating: "0", count: 64),
            startOffsetSec: 0,
            durationSec: durationSec
        )
    }
}
