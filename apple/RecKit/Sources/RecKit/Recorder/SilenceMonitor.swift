import Foundation
import ReclyCore

/// The `silenced` ranges of docs/03 on Apple: the stretches where something else held the
/// microphone — an iOS interruption, a call or Siri — and the recording carried on afterwards.
/// (The Android `SilenceMonitor` is the same contract over `isClientSilenced`, down to the reason
/// string; the two are meant to stay readable side by side.)
///
/// Two clocks, because an interruption stops the engine: the *position* is the recording's own —
/// frames written, which do not advance while the microphone is gone — and the *length* is the wall
/// clock the interruption lasted. That is the same shape a restart's `gaps` entry is written with
/// (`SegmentedRecorder.restart`), and it says the one thing a reader wants: at this point in the
/// file, this much was taken away.
struct SilenceMonitor {
    /// docs/03's example reason for a lost microphone, and the one Android writes.
    static let reason = "mic_taken"

    private(set) var ranges: [ReclyCore.Range] = []
    /// Where the open range starts, in both clocks. `nil` when the microphone is ours.
    private var openedAt: (positionSec: Double, uptimeSec: Double)?
    /// The recording's position when the last range was closed. It is what says whether any audio
    /// was written since — the range's own `endSec` cannot, because it carries the wall clock the
    /// interruption lasted on top of that position.
    private var closedAtPositionSec: Double?

    /// Only transitions count: a run of "still silenced" is one range, and a second `began` for an
    /// interruption already open has nothing to add.
    ///
    /// A range that resumes where the previous one ended — a flap reported twice — is merged into
    /// it rather than appended, so a call that Siri interrupted is not two holes.
    mutating func set(_ silenced: Bool, positionSec: Double, uptimeSec: Double) {
        guard silenced != (openedAt != nil) else { return }
        guard silenced else { return close(positionSec: positionSec, uptimeSec: uptimeSec) }
        openedAt = (positionSec, uptimeSec)
    }

    /// Closes a range that is still open — a stop, or a recording that ended during the call that
    /// silenced it. Nothing to do when the microphone was ours all along.
    mutating func close(positionSec: Double, uptimeSec: Double) {
        guard let opened = openedAt else { return }
        openedAt = nil
        let lastedSec = max(0, uptimeSec - opened.uptimeSec)
        guard lastedSec > 0 else { return }
        // No frames were written since the last one closed — a call that Siri interrupted, one
        // hole reported twice — so they are one range as long as both put together, not one that
        // ends where the second did. A position that has moved means audio in between: two ranges.
        if let previous = ranges.last, let closedAt = closedAtPositionSec, opened.positionSec <= closedAt {
            ranges[ranges.count - 1] = ReclyCore.Range(
                startSec: previous.startSec,
                endSec: previous.endSec + lastedSec,
                reason: previous.reason
            )
            closedAtPositionSec = opened.positionSec
            return
        }
        ranges.append(
            ReclyCore.Range(
                startSec: opened.positionSec,
                endSec: opened.positionSec + lastedSec,
                reason: Self.reason
            )
        )
        closedAtPositionSec = opened.positionSec
    }
}
