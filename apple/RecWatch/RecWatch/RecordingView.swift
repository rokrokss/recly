import RecKit
import SwiftUI

/// docs/09 screen principle 7 · docs/13 "Apple Watch": a monospace timer, a square start/stop and one line of
/// status, which is also where it says the phone still owes an ack for a recording.
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
            statusLine
                .font(blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
                .fixedSize(horizontal: false, vertical: true)
                .multilineTextAlignment(.center)

            if model.isRecording {
                Text(verbatim: model.elapsed)
                    .font(blueprint.fonts.monoTitle)
                    .foregroundStyle(blueprint.palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }

            button

            // docs/09 §1: below the stop square while recording; Double Tap is this button's now.
            if model.isRecording {
                highlightButton
            }

            if model.microphoneDenied {
                Text("Turn the microphone on in Settings > Privacy")
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.danger)
            }
        }
        .padding(.horizontal, Space.xs)
        .frame(maxWidth: .infinity)
        }
        .background(blueprint.palette.background)
    }

    /// docs/09 screen principle 7: what the recorder is doing or last had to say, else the recordings
    /// still on this watch, else nothing — blank, the line keeps its height so the button does not
    /// move when something comes back to say.
    @ViewBuilder
    private var statusLine: some View {
        if let at = model.highlightedAtSec {
            Text(verbatim: RecKitStrings.localized("Highlighted at %@", LedgerFormat.clock(Int(at))))
                .foregroundStyle(blueprint.palette.text)
        } else if !model.status.isEmpty {
            Text(verbatim: model.status)
                .foregroundStyle(blueprint.palette.text)
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
        .disabled(!model.isReady)
        .accessibilityLabel(model.canStop ? Text("Stop") : Text("Record"))
    }

    /// The flag in the accent's outline: a mark at this moment of the recording.
    private var highlightButton: some View {
        Button { model.highlight() } label: {
            Label {
                Text(verbatim: RecKitStrings.localized("Highlight"))
            } icon: {
                Image(systemName: "flag")
            }
            .font(blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
            .foregroundStyle(blueprint.palette.accent)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity, minHeight: 40)
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.accent, lineWidth: 1.5)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("highlight")
        .modifier(DoubleTapHighlight(armed: model.isRecording))
    }
}

/// docs/09 §1 (2026-10-07): Double Tap marks a highlight — it used to stop, and Stop stays on the square.
/// `handGestureShortcut` is watchOS 11 API and RecKit's floor is 10, so on watchOS 10 the button is
/// only a button — and the gesture is armed only while recording, so a double tap on the idle screen
/// does nothing.
private struct DoubleTapHighlight: ViewModifier {
    let armed: Bool

    func body(content: Content) -> some View {
        if #available(watchOS 11.0, *), armed {
            content.handGestureShortcut(.primaryAction)
        } else {
            content
        }
    }
}
