import RecKit
import SwiftUI

/// docs/09 화면 원칙 7 · docs/13 "Apple Watch": a monospace timer, a square start/stop, one line of
/// status and the number of recordings the phone still owes an ack for. Nothing else fits, and
/// nothing else is needed.
struct RecordingView: View {
    @ObservedObject var model: WatchRecordingModel
    @Environment(\.blueprint) private var blueprint

    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: Space.s) {
            Text(model.status)
                .font(blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
                .foregroundStyle(blueprint.palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            if model.isRecording {
                Text(verbatim: model.elapsed)
                    .font(blueprint.fonts.monoTitle)
                    .foregroundStyle(blueprint.palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            } else if !model.workflows.isEmpty {
                picker
            }

            button

            if model.waiting > 0 {
                // docs/03: a part is deleted only after `ack-meta ok`, so this is the answer to
                // "can I take the watch off yet".
                Text("Sending \(model.waiting)")
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            if model.microphoneDenied {
                Text("Turn the microphone on in Settings > Privacy")
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.danger)
            }
        }
        .padding(.horizontal, Space.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(blueprint.palette.background)
    }

    /// ADR-016: a tap here is the workflow this watch records with, stored on the watch and sent
    /// with the recording as its pick. "Phone's workflow" is always offered and is the only option
    /// when the phone has published nothing — a recording with no pick of its own runs whichever
    /// workflow the *phone* is using when the phone enqueues it.
    private var picker: some View {
        Picker(
            selection: Binding(
                get: { model.workflowId },
                set: { model.selectWorkflow($0) }
            )
        ) {
            Text("Phone's workflow").tag(String?.none)
            ForEach(model.workflows, id: \.id) { workflow in
                Text(workflow.name).tag(String?.some(workflow.id))
            }
        } label: {
            Text("Workflow")
        }
        .labelsHidden()
        .frame(height: minTouch)
        .tint(blueprint.palette.accent)
    }

    /// docs/09 "형태": a square node with a thick border, filled while recording — the watch's
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
        .modifier(DoubleTapStop(armed: model.canStop))
    }
}

/// docs/13 진입점: Double Tap stops. `handGestureShortcut` is watchOS 11 API and RecKit's floor is
/// 10, so on watchOS 10 the button is only a button — and the gesture is armed only while there is
/// something to stop, so a double tap on the idle screen does not start a recording by surprise.
private struct DoubleTapStop: ViewModifier {
    let armed: Bool

    func body(content: Content) -> some View {
        if #available(watchOS 11.0, *), armed {
            content.handGestureShortcut(.primaryAction)
        } else {
            content
        }
    }
}
