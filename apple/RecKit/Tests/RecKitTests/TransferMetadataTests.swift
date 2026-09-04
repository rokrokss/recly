import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// The wire between the watch and the phone (docs/11 A8's paths, as `WCSession` dictionaries): the
/// whitelist a metadata dictionary has to pass, and the acks that come back.
///
/// The Android `TransferPathTest` and `AckJsonTest` are the same two halves of the same contract;
/// what these pin down is that the builder and the parser here agree with each other, because
/// nothing else in the system can tell them they do not.
final class TransferMetadataTests: XCTestCase {
    private let recordingId = "01J9ABCDEFGHJKMNPQRSTVWXYZ"
    private let file = "20260826T010000Z_watch_01J9ABCD_p007_mono.m4a"
    private let sha = String(repeating: "a", count: 64)

    // MARK: - The metadata

    func testAPartRoundTrips() throws {
        let part = PartMetadata(
            recordingId: recordingId, part: 7, track: Track.mono, sha256: sha, file: file
        )

        XCTAssertEqual(TransferMetadata.parse(part.dictionary), .part(part))
        XCTAssertEqual(TransferMetadata.part(part).key, "\(recordingId)/7/mono")
    }

    /// The meta carries the id and nothing else, and that absence is what tells the two apart.
    func testTheMetaIsTheIdAlone() {
        let meta = TransferMetadata.meta(recordingId: recordingId)

        XCTAssertEqual(TransferMetadata.parse(meta.dictionary), meta)
        XCTAssertEqual(meta.key, "\(recordingId)/meta")
    }

    /// The id becomes a directory name under `dataDir/recordings`; anything that could escape it is
    /// not a recording id.
    func testAnIdThatCouldEscapeTheRecordingsDirectoryIsRefused() {
        for id in ["../etc", "a/b", "", String(repeating: "x", count: 65)] {
            XCTAssertNil(
                TransferMetadata.parse([TransferMetadata.Key.recordingId: id]),
                "\(id) must not name a directory"
            )
        }
    }

    /// A name that disagrees with the part number or the track it travelled with describes two
    /// different files, and this side has no way to tell which one it is holding.
    func testAFileNameThatDisagreesWithTheRestIsRefused() {
        var wrongPart = base
        wrongPart[TransferMetadata.Key.part] = 8
        XCTAssertNil(TransferMetadata.parse(wrongPart))

        var wrongTrack = base
        wrongTrack[TransferMetadata.Key.track] = "mic"
        XCTAssertNil(TransferMetadata.parse(wrongTrack))
    }

    func testAFileNameThatIsNotAPartFileIsRefused() {
        for name in ["../secret.m4a", "20260826T010000Z_watch_01J9ABCD.meta.json", "p007_mono.m4a"] {
            var metadata = base
            metadata[TransferMetadata.Key.file] = name
            XCTAssertNil(TransferMetadata.parse(metadata), "\(name) is not a part file")
        }
    }

    func testAShaThatIsNotOneIsRefused() {
        for value in [String(repeating: "a", count: 63), String(repeating: "z", count: 64)] {
            var metadata = base
            metadata[TransferMetadata.Key.sha256] = value
            XCTAssertNil(TransferMetadata.parse(metadata))
        }
    }

    func testAPartNumberOfZeroIsRefused() {
        var metadata = base
        metadata[TransferMetadata.Key.part] = 0
        XCTAssertNil(TransferMetadata.parse(metadata))
    }

    // MARK: - The acks

    func testAPartAckRoundTrips() {
        let ack = TransferAck.part(
            recordingId: recordingId,
            ref: PartRef(part: 7, track: Track.mono),
            ok: false,
            reason: TransferReason.shaMismatch
        )

        XCTAssertEqual(TransferAck.parse(ack.userInfo), ack)
    }

    /// The `missing` list is the one field of the whole protocol that carries a decision rather than
    /// a fact: it is what the watch resends from.
    func testAMetaAckRoundTripsWithItsMissingList() {
        let ack = TransferAck.meta(
            recordingId: recordingId,
            ok: false,
            reason: nil,
            missing: [PartRef(part: 1, track: Track.mono), PartRef(part: 2, track: Track.mic)]
        )

        XCTAssertEqual(TransferAck.parse(ack.userInfo), ack)
    }

    func testAnOkMetaAckCarriesNothingElse() {
        let ack = TransferAck.meta(recordingId: recordingId, ok: true, reason: nil, missing: [])

        XCTAssertEqual(TransferAck.parse(ack.userInfo), ack)
    }

    /// Anything that is not an ack at all — another kind of `transferUserInfo`, or a truncated one —
    /// leaves the queue as it was rather than being acted on half-understood.
    func testSomethingThatIsNotAnAckIsRefused() {
        XCTAssertNil(TransferAck.parse([:]))
        XCTAssertNil(TransferAck.parse(["ack": ["recordingId": recordingId]]), "no ok")
        XCTAssertNil(
            TransferAck.parse(["ack": ["recordingId": recordingId, "ok": true, "part": 1]]),
            "a part ack with no track"
        )
    }

    private var base: [String: Any] {
        PartMetadata(
            recordingId: recordingId, part: 7, track: Track.mono, sha256: sha, file: file
        ).dictionary
    }
}
