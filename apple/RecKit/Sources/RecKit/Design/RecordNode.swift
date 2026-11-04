import Foundation
import SwiftUI

/// docs/09 "Shape": the round record button is replaced by a square node with a thick border —
/// 72pt, outlined while idle and filled while recording, with the stop square in the page
/// colour. The iPhone's Record screen and the Mac's popover draw this one, so a start and a stop
/// look alike on every device (the desktops' text button gave way to it, 2026-11-04).
///
/// It names no action of its own: the caller gives it its accessibility label and identifier,
/// and adds `.disabled` for whatever else keeps a start out (a core that is not open yet).
public struct RecordNode: View {
    @Environment(\.blueprint) private var blueprint
    private let recording: Bool
    private let working: Bool
    private let action: () -> Void
    /// What the node draws, which outlives the recorder's own state by [holdBusy]'s window.
    @State private var busy = false
    /// When the recorder went to work, so the window can be measured from it. Nil while it is not.
    @State private var busyStartedAt: Date?

    /// [working]: the recorder is between two states — Starting or Saving.
    public init(recording: Bool, working: Bool, action: @escaping () -> Void) {
        self.recording = recording
        self.working = working
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: Radius.node)
                    .fill(recording ? blueprint.palette.danger : blueprint.palette.surface)
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(blueprint.palette.danger, lineWidth: 3)
                if busy {
                    Text(verbatim: "…")
                        .font(blueprint.fonts.monoTitle)
                        .foregroundStyle(blueprint.palette.danger)
                } else {
                    RoundedRectangle(cornerRadius: Radius.badge)
                        .fill(recording ? blueprint.palette.background : blueprint.palette.danger)
                        .frame(width: 22, height: 22)
                }
            }
            .frame(width: 72, height: 72)
            .contentShape(Rectangle())
            .blueprintFocusRing()
        }
        .buttonStyle(.plain)
        .pointerCursor()
        .focusEffectDisabled()
        // Off while the recorder is between two states — Starting and Saving, as on every shell — and
        // through the tail of the hold too: the node reads "…" there, and a tap the user cannot see the
        // effect of would start a second recording.
        .disabled(working || busy)
        .task(id: working) { await holdBusy() }
    }

    /// docs/09 trend 2 · "Motion": start and stop are two of the rare high-risk actions, so what the
    /// node shows while the recorder is working stays up for [Processing.hold] after the recorder
    /// has already moved on — the same window `ProcessingButton` holds for a button, and the one
    /// Android holds here (`RecordingScreen.rememberBusyHold`). A start the recorder answered in
    /// 40 ms would otherwise flash a state nobody can read, and the tap would look like it did
    /// nothing at all.
    ///
    /// Reduce motion does not shorten it: the hold is the text state, and the text state is what
    /// docs/09 keeps when it takes the animations away.
    private func holdBusy() async {
        if working {
            busyStartedAt = Date()
            busy = true
            return
        }
        guard let started = busyStartedAt else { return }
        busyStartedAt = nil
        let hold = Processing.hold(workSec: Date().timeIntervalSince(started))
        if hold > 0 {
            // A cancellation here is the recorder going back to work — the next pass owns the
            // state, and clearing it on the way out would blink the node in between.
            do { try await Task.sleep(nanoseconds: UInt64(hold * 1_000_000_000)) } catch { return }
        }
        busy = false
    }
}
