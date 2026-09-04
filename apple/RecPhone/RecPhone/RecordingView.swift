import ReclyCore
import RecKit
import SwiftUI

/// docs/09 화면 원칙 1: the recording screen is a dashboard — three state nodes at the top, a
/// monospace timer under them, and one square node that starts and stops the recording
/// (docs/13 I2).
///
/// The accessibility identifiers are not decoration: `RecPhoneUITests` drives this screen to make
/// the recording the lane's simulator smoke asks for.
struct RecordingView: View {
    @ObservedObject var model: RecordingModel
    @Environment(\.blueprint) private var blueprint
    @State private var title = ""
    /// docs/03: nil is "unknown", which writes nothing at all rather than guessing.
    @State private var participants: Int?
    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale
    /// docs/09 "접근성": at the accessibility sizes the dashboard is taller than the phone, and the
    /// record node is the part that falls off the bottom of it.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// docs/09 화면 원칙 1: the workflow picker is revealed by the node it names, and is a row of
    /// chips rather than a menu — the names, and the one in use with a ✓ on it.
    @State private var picking = false
    /// How wide the picker's wrap is, so no single chip can be wider than it. Measured rather than
    /// assumed: the dashboard's own margin is the only thing that decides it.
    @State private var pickerWidth: CGFloat = 0
    /// What the record node draws, which outlives the recorder's own state by [holdBusy]'s window.
    @State private var busy = false
    /// When the recorder went to work, so the window can be measured from it. Nil while it is not.
    @State private var busyStartedAt: Date?

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(
                title: "Recly",
                // The header is one line: the source and enough of the device id to tell two
                // phones apart. The whole id is in Settings → About, where it is the point.
                meta: "\(Source.phone.name.lowercased()) · \(model.deviceId.prefix(8))"
            )
            // Only there: a `ScrollView` at every size would cost the dashboard the one thing it is
            // — a screen whose parts are spaced across the phone rather than stacked at the top of
            // it. Inside one, the spacer between the readouts and the record node proposes nothing
            // and collapses, so the order is kept and the node is the end of the scroll.
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    ScrollView { dashboard }
                } else {
                    dashboard
                }
            }
            // docs/12 M8 · ADR-011: once, before the first recording. The wording is the Mac's, word
            // for word in both languages (`ConsentTextTests` holds the two together).
            //
            // Drawn in the screen rather than presented as a sheet: this screen's one sheet is the
            // naming prompt below, which is a different question asked at the other end of a
            // recording, and a second `.sheet` on the same view simply never opens.
            .blueprintDialogOverlay(isPresented: $model.consentPrompt) {
                ConsentDialog { confirmed, suppress in
                    model.consentAnswered(confirmed: confirmed, suppress: suppress)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dotGridBackground()
        // docs/03: the title is asked for after the recording has ended, and the job is queued once
        // the answer — or the refusal — is in. A sheet rather than an alert, because the same
        // prompt also asks how many people were in the room and an alert holds no picker.
        .sheet(isPresented: naming) {
            NamingSheet(
                title: $title,
                participants: $participants,
                onSave: { finishNaming(with: title, participants: participants) },
                onSkip: { finishNaming(with: nil, participants: nil) }
            )
        }
    }

    /// Everything under the header: the three nodes, the timer, and the record control with the
    /// status line and the permission recovery under it.
    private var dashboard: some View {
        VStack(spacing: 0) {
            nodes.padding(.horizontal, Space.m)

            VStack(spacing: 12) {
                MonoTimer(model.isRecording ? model.elapsed : "00:00")
                    .accessibilityIdentifier("elapsed")
                // docs/09 화면 원칙 6: the same strip the menu bar draws, under the timer — what is
                // being written, while it is being written. Nothing stands in for it when idle:
                // a placeholder waveform is a picture of audio that does not exist.
                if model.isRecording {
                    LiveWaveformView(peaks: model.livePeaks)
                        .padding(.horizontal, Space.m)
                        .accessibilityIdentifier("live-waveform")
                }
            }
            .frame(maxHeight: .infinity)

            VStack(spacing: 10) {
                recordNode
                Text(model.status)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .accessibilityIdentifier("status")
                if model.microphoneDenied {
                    VStack(spacing: Space.s) {
                        Text("The microphone permission is required.")
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.danger)
                            .multilineTextAlignment(.center)
                        BlueprintButton(loc("Open Settings")) { model.openSettings() }
                            .accessibilityIdentifier("openSettings")
                    }
                    .padding(.horizontal, Space.m)
                }
            }
            .padding(.bottom, Space.l)
        }
    }

    // MARK: - The three nodes

    /// The workflow node is the picker: it is the only node on this screen that is a choice, so the
    /// row takes a tap while there is something to choose and is a plain readout while there is not.
    @ViewBuilder
    private var nodes: some View {
        if model.workflows.isEmpty || model.isRecording {
            StateNodeRow(specs)
        } else {
            VStack(spacing: Space.s) {
                Button {
                    picking.toggle()
                } label: {
                    StateNodeRow(specs)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("workflow")
                if picking { workflowPicker }
            }
        }
    }

    /// docs/09 화면 원칙 1: "피커는 워크플로우 이름들만 나열하고 선택된 하나가 채워진 칩(✓)이다" — the
    /// names and nothing else, so there is no "Workflow" label row inside it and no "no pick" entry
    /// to make (ADR-016: the pick *is* this phone's pointer).
    ///
    /// The same chips the Mac's popover draws (`MenuPopover.workflowPicker`) and Android's menu
    /// marks the same way.
    ///
    /// It is the dashboard it opens inside that decides the two limits. docs/05 lets a document hold
    /// fifty workflows with forty-character names, and a plain wrapping row of those would push the
    /// readouts and the record node off the bottom of a small phone: so the wrap scrolls after
    /// [pickerRows] rows, and no chip is wider than the row it is on — a long name is one line with
    /// its tail cut, as everywhere else in this design.
    private var workflowPicker: some View {
        ScrollView(.vertical) {
            FlowLayout {
                ForEach(model.workflows, id: \.id) { workflow in
                    BlueprintChip(workflow.name, selected: model.workflowId == workflow.id) {
                        picking = false
                        // ADR-016: a pick here is this phone's own pointer, so it is what every
                        // recording runs until another one is made — not a choice that lasts one.
                        Task { await model.selectWorkflow(workflow.id) }
                    }
                    // Nil rather than zero until the width is measured: a chip is its own size,
                    // and a `maxWidth` of 0 would flatten every one of them on the first pass.
                    .frame(maxWidth: pickerWidth > 0 ? pickerWidth : nil)
                    .truncationMode(.tail)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                GeometryReader { proxy in
                    Color.clear.onChange(of: proxy.size.width, initial: true) { _, width in
                        pickerWidth = width
                    }
                }
            }
        }
        .scrollIndicators(.never)
        .frame(maxHeight: pickerHeight)
        .accessibilityIdentifier("workflow-picker")
    }

    /// How much of the wrap stands before it scrolls: three chips deep, which is as much as the
    /// dashboard can give up and still be one. A chip is at least [minTouch] tall and the rows are
    /// [Space.s] apart, so this is that many of them — at the accessibility type sizes the chips are
    /// taller than the floor and fewer of them stand, which is the same trade the rest of the screen
    /// makes there.
    private var pickerHeight: CGFloat {
        CGFloat(Self.pickerRows) * minTouch + CGFloat(Self.pickerRows - 1) * Space.s
    }

    private static let pickerRows = 3

    private var specs: [NodeSpec] {
        [
            NodeSpec(label: loc("Device"), value: Source.phone.name.lowercased()),
            NodeSpec(label: loc("Workflow"), value: workflowName),
            stateNode,
        ]
    }

    /// The recorder's own state comes first; `REC` is never displaced. While it is idle and a job in
    /// the ledger is running, the node says `UPLOADING` with a turning loader instead of `IDLE` —
    /// otherwise nothing above the list says the app is doing anything at all.
    private var stateNode: NodeSpec {
        if model.state == .idle, Recents.uploading(model.recents) {
            return NodeSpec(
                label: loc("State"),
                value: "UPLOADING",
                valueColor: blueprint.palette.accent,
                active: true,
                busy: true
            )
        }
        return NodeSpec(
            label: loc("State"),
            value: stateCode,
            valueColor: model.isRecording ? blueprint.palette.danger : blueprint.palette.textMuted,
            active: model.isRecording
        )
    }

    /// The node names the workflow this phone records with — the one the picker has selected.
    ///
    /// ADR-016: a phone that has picked none, or points at a workflow another device deleted, has
    /// something to fix rather than a workflow to name, and the node is where the user is when it
    /// matters.
    private var workflowName: String {
        model.workflows.first { $0.id == model.workflowId }?.name
            ?? loc("Choose a workflow")
    }

    /// docs/09: state is a code, in monospace, and never colour alone.
    private var stateCode: String {
        switch model.state {
        case .idle: return "IDLE"
        case .starting: return "STARTING"
        case .recording: return "REC"
        case .stopping: return "STOPPING"
        }
    }

    // MARK: - The record node

    /// docs/09 "형태": the round record button is replaced by a square node with a thick border —
    /// 72pt, outlined while idle and filled while recording, with the stop square in the page
    /// colour.
    private var recordNode: some View {
        Button {
            if model.canStop { model.stop() } else { model.start() }
        } label: {
            ZStack {
                RoundedRectangle(cornerRadius: Radius.node)
                    .fill(model.isRecording ? blueprint.palette.danger : blueprint.palette.surface)
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(blueprint.palette.danger, lineWidth: 3)
                if busy {
                    Text(verbatim: "…")
                        .font(blueprint.fonts.monoTitle)
                        .foregroundStyle(blueprint.palette.danger)
                } else {
                    RoundedRectangle(cornerRadius: Radius.badge)
                        .fill(model.isRecording ? blueprint.palette.background : blueprint.palette.danger)
                        .frame(width: 22, height: 22)
                }
            }
            .frame(width: 72, height: 72)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Only `.stopping` takes the node out while the recorder is working: `canStop` deliberately
        // allows a stop while it is still `.starting` so a slow — or stuck — start can be parked.
        // The tail of the hold does take it out, though; the node reads "…" there and a tap the
        // user cannot see the effect of would start a second recording.
        .disabled(!model.isReady || model.state == .stopping || (busy && !working))
        .accessibilityIdentifier(model.canStop ? "stop" : "start")
        .accessibilityLabel(model.canStop ? Text("Stop") : Text("Start recording"))
        .task(id: working) { await holdBusy() }
    }

    /// Whether the recorder itself is between two states.
    private var working: Bool { model.state == .starting || model.state == .stopping }

    /// docs/09 트렌드 2 · "모션": start and stop are two of the rare high-risk actions, so what the
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

    // MARK: - Naming

    /// The sheet is driven by the model's `naming`, which is also what carries the recording it is
    /// about — a `Bool` of its own would be a second answer to the same question.
    private var naming: Binding<Bool> {
        Binding(
            get: { model.naming != nil },
            set: { shown in if !shown { finishNaming(with: nil, participants: nil) } }
        )
    }

    private func finishNaming(with typed: String?, participants count: Int?) {
        guard model.naming != nil else { return }
        let answer = typed
        title = ""
        participants = nil
        Task { await model.finishNaming(with: answer, participants: count) }
    }
}

/// docs/12 M8 · ADR-011: a local capture shows the other people in the room nothing at all, so the
/// responsibility for telling them is the user's and the app's job is to remind them — once, before
/// the first recording, and never again once they have said not to. There is no covert mode, and
/// this is not a permission screen.
///
/// Cancel means something: it is a question, and answering "no" leaves the recording unstarted.
private struct ConsentDialog: View {
    /// `(confirmed, suppress)`. The "do not ask again" box applies whichever button was pressed,
    /// as it does on the Mac, where it is `NSAlert`'s own suppression button.
    let answer: (Bool, Bool) -> Void

    @State private var suppress = false
    @Environment(\.openURL) private var openURL
    @Environment(\.locale) private var locale

    var body: some View {
        BlueprintDialog(title: loc("Did you tell the participants about the recording?")) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { answer(false, suppress) }
            BlueprintButton(loc("I told them · Start recording"), tone: .primary) {
                answer(true, suppress)
            }
            .accessibilityIdentifier("consent-confirm")
        } content: {
            // docs/research/02 §동의·법. Not legal advice and not a jurisdiction the app tries to
            // guess: the three lines are what the user needs to know that the question is not
            // rhetorical.
            BlueprintDialogText(loc("consent.body"))
                .accessibilityIdentifier("consent-body")
            // A link and not a third button, for the same reason as on the Mac: the question the
            // dialog is asking is still open.
            BlueprintDialogLink(loc("Recording-consent rules by jurisdiction")) {
                openURL(ConsentDialog.guidance)
            }
            BlueprintCheckRow(loc("Do not ask again"), isOn: $suppress)
                .accessibilityIdentifier("consent-suppress")
        }
    }

    /// Wikipedia's summary of recording-consent law until Recly has a page of its own to point at —
    /// the same URL the Mac opens (`MenuModel.consentGuidanceLink`).
    static let guidance = URL(string: "https://en.wikipedia.org/wiki/Telephone_call_recording_laws")!
}

/// docs/03: the name, and how many people were in the room — the hint `transcribe` trusts over the
/// workflow's own `speakers` (docs/08). "Unknown" is where it starts and it writes nothing.
///
/// A sheet rather than the alert the title alone used to be: an alert holds no picker.
private struct NamingSheet: View {
    @Binding var title: String
    @Binding var participants: Int?
    let onSave: () -> Void
    let onSkip: () -> Void

    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    /// docs/03: 2 · 3 · 4 · 5 · 6+ · unknown. docs/08 caps the hint at 10 speakers, so "6+" asks
    /// for six and lets the provider find more.
    private let choices: [Int?] = [nil, 2, 3, 4, 5, 6]

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: loc("Recording title"))
            HairLine()
            VStack(alignment: .leading, spacing: Space.m) {
                VStack(alignment: .leading, spacing: Space.xs) {
                    BlueprintField(loc("Title"), text: $title)
                        .accessibilityIdentifier("titleField")
                    Text(verbatim: loc("Leave it empty to keep the timestamp name"))
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(verbatim: loc("People in the room"))
                        .font(blueprint.fonts.label)
                        .tracking(0.6)
                        .foregroundStyle(blueprint.palette.textMuted)
                    // docs/09 유동 타이포: six chips across a phone is a row at the design's own type
                    // size and several rows at the user's.
                    FlowLayout {
                        ForEach(choices, id: \.self) { choice in
                            BlueprintChip(label(choice), selected: participants == choice) {
                                participants = choice
                            }
                        }
                    }
                    .accessibilityIdentifier("participants")
                }
                HStack(spacing: Space.s) {
                    BlueprintButton(loc("Save"), tone: .primary) { onSave() }
                        .accessibilityIdentifier("saveTitle")
                    BlueprintButton(loc("Skip"), tone: .quiet) { onSkip() }
                }
            }
            .padding(Space.m)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .dotGridBackground()
        .presentationDetents([.medium])
    }

    /// docs/07 rule 4: a count is a number, not a sentence — only "unknown" and "6+" are words.
    private func label(_ choice: Int?) -> String {
        switch choice {
        case .none: return loc("Unknown")
        case .some(6): return loc("6+")
        case .some(let count): return String(count)
        }
    }
}
