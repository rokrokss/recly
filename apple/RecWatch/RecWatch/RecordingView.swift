import RecKit
import SwiftUI

/// docs/09 screen principle 7 · docs/13 "Apple Watch": a monospace timer, one line of status — which is also where
/// it says the phone still owes an ack for a recording, or refused one — and a square start/stop, in Wear's order.
/// Nothing else fits, and nothing else is needed.
struct RecordingView: View {
    @ObservedObject var model: WatchRecordingModel
    @Environment(\.blueprint) private var blueprint

    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        ScrollView {
        VStack(spacing: Space.s) {
            // Wear's order: the timer, the status line, then the square. 2026-10-08 §4: the recording's length
            // stays while it is saved; `00:00` while nothing is running, as on Wear and the phones.
            Text(verbatim: model.elapsed.isEmpty || model.state == .idle ? LedgerFormat.elapsed(0) : model.elapsed)
                // docs/09 "Typography": the Apple Watch timer, 20, mono at regular weight with 1 of tracking.
                .font(blueprint.fonts.monoTitle)
                .tracking(timerTracking)
                .foregroundStyle(blueprint.palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityIdentifier("elapsed")

            statusLine
                // docs/09 "Typography" wants status words in mono on the watch too (2026-10-15), but in SF Mono at 14 the
                // German `Mikrofonberechtigung` is 173 pt — wider than a 40 or 41 mm face's 154 or 168 — and would
                // break between two letters; in the sans it is 147 pt. So the line keeps the sans.
                .font(blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
                .fixedSize(horizontal: false, vertical: true)
                .multilineTextAlignment(.center)
                // docs/09 "Disabled, focus and structure": the line is announced when it changes.
                .announcesChanges(of: model.status)

            button

            if model.microphoneDenied {
                // 2026-10-10 (2.10): something to put right in Settings, not a failure — the warning tone.
                Text("Turn the microphone on in Settings > Privacy")
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(BadgeTone.warning.ink(blueprint.palette))
            }
        }
        .padding(.horizontal, Space.xs)
        .frame(maxWidth: .infinity)
        }
        .background(blueprint.palette.background)
    }

    /// docs/09 screen principle 7: what the recorder is doing or last had to say, else a transfer that failed,
    /// else the recordings still on this watch, else nothing — blank, the line keeps its height so the button does not
    /// move when something comes back to say.
    @ViewBuilder
    private var statusLine: some View {
        if !model.status.isEmpty {
            Text(verbatim: model.status)
                .foregroundStyle(blueprint.palette.text)
        } else if model.failed > 0 {
            // A refusal is worse news than a wait and outranks it, as on Wear: the audio is still on this watch
            // and nothing will send it again.
            Text("Transfer failed")
                .foregroundStyle(blueprint.palette.danger)
        } else if model.waiting > 0 {
            // docs/03: a part is deleted only after `ack-meta ok`, so this is the answer to
            // "can I take the watch off yet". Always "waiting", never "sending": `WCSession` cannot
            // say whether a queued file is moving (docs/09 screen principle 7).
            Text("Waiting to send")
                .foregroundStyle(blueprint.palette.textMuted)
        } else {
            Text(verbatim: " ")
        }
    }

    /// docs/09 "Shape": a square node with a thick border, filled while recording — the watch's
    /// version of the phone's 72pt record node.
    private var button: some View {
        Button {
            if model.canStop { model.stop() } else { model.start() }
        } label: {
            ZStack {
                RoundedRectangle(cornerRadius: Radius.node)
                    .fill(model.isRecording ? blueprint.palette.danger : blueprint.palette.surface)
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(blueprint.palette.danger, lineWidth: 3)
                RoundedRectangle(cornerRadius: Radius.badge)
                    .fill(model.isRecording ? blueprint.palette.background : blueprint.palette.danger)
                    .frame(width: 18, height: 18)
            }
            .frame(width: 56, height: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Off while the recorder is Starting or Saving, as on every shell — Record or Stop, never in between.
        .disabled(!model.buttonEnabled)
        .accessibilityLabel(model.canStop ? Text("Stop") : Text("Record"))
    }
}
