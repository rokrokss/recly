#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "Ask": one question about this recording — a preset, or the user's own — and ChatGPT's answer under
/// it, with its citations playable as the summary's are. Nothing here is saved: the core keeps the answer in
/// this process until the panel closes (docs/08 "Ask").
///
/// What it draws comes in as values rather than as the page's model, so a state can be drawn without asking
/// anything. The Mac draws it as a card over the detail; the phone in a sheet of its own ([AskSheet]).
struct AskPanel: View {
    let state: AskState
    let presets: [AskPreset]
    /// The plan's name for an answer's model (`ChatGPT · <model>`).
    let modelLabel: (String) -> String
    @Binding var question: String
    let ask: (AskPreset?, String?) -> Void
    let retry: () -> Void
    let copy: () -> Void
    /// How far the recording here can be played, and the seek a citation makes (see [CitedText]).
    let seekableSec: Double
    let onSeek: ((Double) -> Void)?
    @State private var copied = false
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            if !presets.isEmpty {
                // docs/09 §9 principle 4: filled on the phone, left-aligned in a desktop window.
                ChoiceRow {
                    ForEach(presets, id: \.self) { preset in
                        BlueprintChip(ChatGptText.presetLabel(preset), selected: asked?.preset == preset && asked?.question == nil, fill: true) {
                            ask(preset, nil)
                        }
                        .accessibilityIdentifier("ask-preset-\(preset.name.lowercased())")
                    }
                }
                .disabled(running)
            }
            HStack(alignment: .bottom, spacing: Space.s) {
                questionField
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    // 2026-10-10 (2.13): the user's words in their own direction.
                    .contentDirection(question)
                    .frame(minHeight: minTouch)
                    .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
                    .overlay {
                        RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
                    }
                    .disabled(running)
                    .accessibilityLabel(Text(verbatim: loc("Ask your own question")))
                    .accessibilityIdentifier("ask-question")
                BlueprintButton(loc("Ask"), tone: .primary, action: askOwn)
                    .disabled(running || typed.isEmpty)
                    .accessibilityIdentifier("ask-send")
            }
            answer
        }
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(3))
            copied = false
        }
    }

    @ViewBuilder
    private var answer: some View {
        switch onEnum(of: state) {
        case .none:
            EmptyView()
        case .running:
            LoadingText(text: loc("Asking…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                .accessibilityIdentifier("ask-running")
        case .ready(let ready):
            VStack(alignment: .leading, spacing: Space.m) {
                CitedText(text: ready.answer.text, seekableSec: seekableSec, onSeek: onSeek)
                    .accessibilityIdentifier("ask-answer")
                HStack(spacing: Space.s) {
                    // The plan's name for the model when the answer was written, as a summary's footer has it.
                    Text(verbatim: RecKitStrings.localized("ChatGPT · %@", ready.answer.modelName ?? modelLabel(ready.answer.model)))
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    BlueprintButton(
                        copied ? loc("Copied") : loc("Copy all"),
                        tone: .quiet,
                        leading: copied ? BlueprintChip.selectionMark : nil
                    ) {
                        copy()
                        copied = true
                    }
                    .disabled(copied)
                    .accessibilityIdentifier("ask-copy")
                }
            }
        case .failed(let failed):
            ChatGptFailureNotice(reason: failed.reason, failed: loc("Could not answer"), retrying: false, retry: retry)
                .accessibilityIdentifier("ask-failed")
        }
    }

    /// The question, up to four lines. 2026-10-10 (3.3): on the Mac Return asks and Shift+Return starts a new line —
    /// a text view, because a `TextField` there keeps the new line for ⌥Return; on the phone Return is the
    /// keyboard's new line and `Ask` asks.
    @ViewBuilder
    private var questionField: some View {
        #if os(macOS)
        GrowingTextEditor(text: $question, placeholder: loc("Ask your own question"), lines: 1...4, onReturn: askOwn)
            .padding(.horizontal, 10 - GrowingTextEditor.inset)
            .padding(.vertical, 9)
        #else
        TextField("", text: $question, prompt: FieldPlaceholder.prompt(loc("Ask your own question"), blueprint.palette), axis: .vertical)
            .lineLimit(1...4)
            .textFieldStyle(.plain)
            .onSubmit(askOwn)
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
        #endif
    }

    private var running: Bool { state is AskState.Running }

    /// What the answer on screen — or the one being written — was asked with.
    private var asked: (preset: AskPreset?, question: String?)? {
        switch onEnum(of: state) {
        case .none: return nil
        case .running(let running): return (running.preset, running.question)
        case .ready(let ready): return (ready.answer.preset, ready.answer.question)
        case .failed(let failed): return (failed.preset, failed.question)
        }
    }

    private var typed: String { question.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func askOwn() {
        guard !running, !typed.isEmpty else { return }
        ask(nil, typed)
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// The panel's frame on each platform: the Mac's card, drawn over the detail the way its other questions are,
/// sized like them; the phone's full-height sheet, under a header with its close icon.
struct AskFrame<Content: View>: View {
    let close: () -> Void
    @ViewBuilder let content: () -> Content
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    /// The body's own height, which the Mac card is as tall as until the window stops it.
    @State private var bodyHeight: CGFloat = 0

    var body: some View {
        #if os(iOS)
        VStack(spacing: 0) {
            ScreenHeader(title: RecKitStrings.localized("Ask about this recording"), trailingAlignment: .trailing, oneRow: true) {
                closeButton
            }
            HairLine()
            ScrollView {
                content()
                    .padding(Space.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .dotGridBackground()
        #else
        // 2026-10-10 (3.3): the title row and its close icon stay put; the body under them scrolls once the card
        // is as tall as the window lets it be.
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: Space.s) {
                Text(verbatim: RecKitStrings.localized("Ask about this recording"))
                    .font(blueprint.fonts.title)
                    .foregroundStyle(blueprint.palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                closeButton
            }
            ScrollView {
                content()
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { bodyHeight = $0 }
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: max(bodyHeight, 1))
        }
        .padding(Space.m)
        .frame(maxWidth: BlueprintDialog<EmptyView, EmptyView>.maxWidth)
        .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.grid, lineWidth: blueprint.line)
        }
        .onExitCommand(perform: close)
        #endif
    }

    private var closeButton: some View {
        Button(action: close) { HeaderIcon(systemName: "xmark") }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close")))
            .accessibilityIdentifier("ask-close")
    }
}

/// docs/15 "iPhone providers": the permission a summary or a question waits on — drawn where the user is, the
/// detail or the Ask sheet over it.
struct SummaryConsentDialog: View {
    @ObservedObject var model: RecordingDetailModel
    @Binding var trainingOff: Bool
    let answer: (Bool) -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        BlueprintDialog(title: RecKitStrings.localized("Transfer permission needed")) {
            BlueprintButton(RecKitStrings.localized("Cancel"), tone: .quiet, minWidth: minTouch) { answer(false) }
            BlueprintButton(RecKitStrings.localized("Allow transfers and continue"), tone: .primary) { answer(true) }
                .disabled(TrainingOptOut.required(model.summaryConsent) && !trainingOff)
                .accessibilityIdentifier("summary-allow-transfer")
        } content: {
            TransferDisclosureList(targets: model.summaryConsent, trainingOff: $trainingOff)
        }
    }
}
#endif
