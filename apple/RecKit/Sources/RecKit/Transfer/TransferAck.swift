import Foundation
import ReclyCore

/// One part of one recording — what an ack and a `missing` entry both name (the Android `PartRef`).
public struct PartRef: Equatable {
    public let part: Int
    public let track: Track

    public init(part: Int, track: Track) {
        self.part = part
        self.track = track
    }
}

/// What the phone sends back through `transferUserInfo` (docs/03 "워치 → 폰 전송 계약"), the Apple
/// half of Android's `WearJson.partAck`/`metaAck` and `AckJson`. `transferUserInfo` rather than
/// `sendMessage` for the reason the contract turns on: it is queued and delivered even with the app
/// asleep or the watch out of range, and the ack is the watch's licence to delete its only copy of
/// the audio.
///
/// The watch acts on nothing else, so every field it decides on is read from here and none of it is
/// inferred. Nothing here throws: an ack the watch cannot read is an ack it did not get, and the
/// item simply stays unacked until the next resend — where guessing at a half-understood `ok` would
/// cost the user the recording.
public enum TransferAck: Equatable {
    case part(recordingId: String, ref: PartRef, ok: Bool, reason: String?)

    /// [missing] is the phone asking for exactly those parts again. Empty with `ok` false and a
    /// [reason] is fatal for this recording; `ok` true is the end of the transfer.
    case meta(recordingId: String, ok: Bool, reason: String?, missing: [PartRef])

    /// The one key `transferUserInfo` carries an ack under, so anything else the two sides ever send
    /// each other through it is told apart at a glance.
    public static let userInfoKey = "ack"

    public var recordingId: String {
        switch self {
        case .part(let recordingId, _, _, _): return recordingId
        case .meta(let recordingId, _, _, _): return recordingId
        }
    }

    /// `nil` for a dictionary that is not one of the two shapes — as on the metadata side, the
    /// presence of a part number is what tells them apart.
    public static func parse(_ userInfo: [String: Any]) -> TransferAck? {
        guard let body = userInfo[userInfoKey] as? [String: Any],
              let recordingId = body[TransferMetadata.Key.recordingId] as? String,
              TransferMetadata.isId(recordingId),
              let ok = (body[Field.ok] as? NSNumber)?.boolValue
        else { return nil }
        let reason = body[Field.reason] as? String
        guard body[TransferMetadata.Key.part] != nil else {
            // A `missing` entry this build cannot read is dropped, not the whole ack: the remaining
            // entries still name parts the phone wants, and dropping the ack would strand the
            // recording instead of resending what was understood.
            let missing = (body[Field.missing] as? [[String: Any]] ?? []).compactMap(ref(in:))
            return .meta(recordingId: recordingId, ok: ok, reason: reason, missing: missing)
        }
        guard let ref = ref(in: body) else { return nil }
        return .part(recordingId: recordingId, ref: ref, ok: ok, reason: reason)
    }

    public var userInfo: [String: Any] {
        var body: [String: Any] = [TransferMetadata.Key.recordingId: recordingId]
        switch self {
        case .part(_, let ref, let ok, let reason):
            body[TransferMetadata.Key.part] = ref.part
            body[TransferMetadata.Key.track] = ref.track.wire
            body[Field.ok] = ok
            body[Field.reason] = reason

        case .meta(_, let ok, let reason, let missing):
            body[Field.ok] = ok
            body[Field.reason] = reason
            if !missing.isEmpty {
                body[Field.missing] = missing.map {
                    [TransferMetadata.Key.part: $0.part, TransferMetadata.Key.track: $0.track.wire]
                }
            }
        }
        return [Self.userInfoKey: body]
    }

    private static func ref(in body: [String: Any]) -> PartRef? {
        guard let part = (body[TransferMetadata.Key.part] as? NSNumber)?.intValue,
              let wire = body[TransferMetadata.Key.track] as? String,
              let track = Track.named(wire)
        else { return nil }
        return PartRef(part: part, track: track)
    }

    private enum Field {
        static let ok = "ok"
        static let reason = "reason"
        static let missing = "missing"
    }
}

/// The nack reasons both halves of the transfer name (docs/03 "워치 → 폰 전송 계약"), kept together
/// because every one of them is a decision about whether audio may be deleted. The strings are the
/// Android ones — `recly.core.transfer.TransferReceiver.SHA_MISMATCH` and the constants of
/// `TransferSender`/`MetaAcceptor` — so a phone and a watch of either platform read the same wire.
public enum TransferReason {
    /// The bytes arrived corrupted. The phone deleted what it staged, so a resend cannot duplicate
    /// anything and is the likeliest fix — once (see `WatchTransferQueue`).
    public static let shaMismatch = "SHA256_MISMATCH"
    /// The meta body names a recording the metadata did not. Fatal, and no core call was made.
    public static let recordingIdMismatch = "RECORDING_ID_MISMATCH"
    /// A part the phone asked for again is not on disk — only possible by a delete from outside.
    public static let partMissingLocally = "PART_MISSING_LOCALLY"
    /// Every part is acked and there is no `meta.json` left to end the transfer with.
    public static let metaMissing = "META_MISSING"
    /// A phone that answers a resend with the same `missing` list is not going to stop.
    public static let resendLoop = "RESEND_LOOP"
    public static let unknown = "UNKNOWN"
}
