#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "Ask": the composer at the foot of the detail — one field for a question about the recording or a change to its
/// transcript and summary, and the presets as chips over it while it is empty and has the cursor (the page gives none while
/// an answer is over it). While Ask cannot run, the field says why and takes nothing.
struct AskComposer: View {
    @Binding var question: String
    let presets: [AskPreset]
    /// Why nothing can be asked now, or nil.
    let reason: String?
    let running: Bool
    let ask: (AskPreset?, String?) -> Void
    /// The page's focus on the field: the phone puts the player away while it has the cursor.
    var focused: FocusState<Bool>.Binding
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            if focused.wrappedValue, typed.isEmpty, !running, reason == nil, !presets.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s) {
                        ForEach(presets, id: \.self) { preset in
                            // An action, not a choice: at the status badge's size, as a time button is (2026-11-04).
                            BlueprintButton(ChatGptText.presetLabel(preset), tone: .quiet, compact: true, tight: true) { ask(preset, nil) }
                                .accessibilityIdentifier("ask-preset-\(preset.name.lowercased())")
                        }
                    }
                }
                .scrollClipDisabled()
            }
            HStack(alignment: .bottom, spacing: Space.s) {
                field
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    // 2026-10-10 (2.13): the user's words in their own direction.
                    .contentDirection(question)
                    .frame(minHeight: minTouch)
                    .fieldBox(focused: focused.wrappedValue, inset: EdgeInsets())
                    .disabled(running || reason != nil)
                    .accessibilityLabel(Text(verbatim: reason ?? loc("Ask, or say what to change")))
                    .accessibilityIdentifier("ask-question")
                BlueprintButton(loc("Send"), tone: .primary, action: send)
                    .disabled(running || reason != nil || typed.isEmpty)
                    .accessibilityIdentifier("ask-send")
            }
        }
        .onChange(of: question) { old, typed in
            let max = Int(AskRequest.shared.MAX)
            if typed.utf16.count > max { question = String(typed.utf16.prefix(max)) ?? String(typed.prefix(max)) }
            #if os(iOS)
            // Return sends, as the keyboard's key says; a paste keeps its lines — another transcript is pasted whole.
            if typed.count == old.count + 1, typed.filter({ $0 == "\n" }).count == old.filter({ $0 == "\n" }).count + 1 {
                question = old
                send()
            }
            #endif
        }
    }

    /// The request, from one line up to six, scrolling past them. On the Mac Return sends and Shift+Return starts a new
    /// line (2026-10-10, 3.3); on the phone Return sends too, and a paste keeps its lines.
    @ViewBuilder
    private var field: some View {
        let placeholder = reason ?? loc("Ask, or say what to change")
        #if os(macOS)
        GrowingTextEditor(text: $question, placeholder: placeholder, lines: 1...6, focused: focused, onReturn: send)
            .padding(FieldInset.textView)
        #else
        TextField("", text: $question, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette), axis: .vertical)
            .lineLimit(1...6)
            .textFieldStyle(.plain)
            .focused(focused)
            .submitLabel(.send)
            .padding(FieldInset.field)
        #endif
    }

    private var typed: String { question.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func send() {
        guard !running, reason == nil, !typed.isEmpty else { return }
        let asked = typed
        question = ""
        ask(nil, asked)
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// docs/09 "Ask": what came of the last request, over the composer — `Working…` while it runs; the answer with its
/// citations playable as the summary's are, its model and Copy; what it changed, with `Undo`; that it changed nothing;
/// or why it failed. Its close icon puts it away. Nothing here is saved: the core keeps it in this process (docs/08 "Ask").
struct AskResult: View {
    let state: AskState
    /// The plan's name for an answer's model (`ChatGPT · <model>`).
    let modelLabel: (String) -> String
    let undoing: Bool
    let undo: () -> Void
    let retry: () -> Void
    let copy: () -> Void
    let close: () -> Void
    /// How far the recording here can be played, and the seek a citation makes (see [CitedText]).
    let seekableSec: Double
    let onSeek: ((Double) -> Void)?
    /// How tall the answer is before it scrolls in its place: see [answerHeight(page:)].
    let answerHeight: CGFloat
    @State private var copied = false
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        HStack(alignment: .top, spacing: Space.s) {
            content
                .frame(maxWidth: .infinity, alignment: .leading)
            if !(state is AskState.Running) {
                BlueprintIconButton("xmark", label: loc("Close"), action: close)
                    .accessibilityIdentifier("ask-close")
            }
        }
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(3))
            copied = false
        }
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: state) {
        case .none:
            EmptyView()
        case .running:
            LoadingText(text: loc("Working…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                .frame(minHeight: minTouch)
                .accessibilityIdentifier("ask-running")
        case .ready(let ready):
            VStack(alignment: .leading, spacing: Space.s) {
                if !ready.answer.text.isEmpty {
                    ScrollView {
                        CitedText(text: ready.answer.text, seekableSec: seekableSec, onSeek: onSeek)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("ask-answer")
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .frame(maxHeight: answerHeight)
                    .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: Space.s) {
                        // The plan's name for the model when the answer was written, as a summary's footer has it.
                        Text(verbatim: RecKitStrings.localized("ChatGPT · %@", ready.answer.modelName ?? modelLabel(ready.answer.model)))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        CopyAllButton(copied: copied) {
                            copy()
                            copied = true
                        }
                        .accessibilityIdentifier("ask-copy")
                    }
                }
                if let changes = ready.answer.changes {
                    changed(changes)
                } else if ready.answer.text.isEmpty {
                    Text(verbatim: loc("Nothing changed"))
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(minHeight: minTouch, alignment: .leading)
                        .accessibilityIdentifier("ask-nothing")
                }
            }
        case .failed(let failed):
            ChatGptFailureNotice(reason: failed.reason, failed: loc("Could not answer"), retrying: false, retry: retry)
                .accessibilityIdentifier("ask-failed")
        }
    }

    /// `Changed: Transcript 2 · Summary 1`, and the way back.
    private func changed(_ changes: AskChanges) -> some View {
        let parts = [
            changes.transcriptCount > 0 ? RecKitStrings.localized("Transcript %@", String(changes.transcriptCount)) : nil,
            changes.summaryCount > 0 ? RecKitStrings.localized("Summary %@", String(changes.summaryCount)) : nil,
        ].compactMap { $0 }
        return HStack(spacing: Space.s) {
            Text(verbatim: RecKitStrings.localized("Changed: %@", parts.joined(separator: " · ")))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("ask-changed")
            if undoing {
                LoadingText(text: loc("Undoing…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
            } else {
                BlueprintButton(loc("Undo"), tone: .quiet, action: undo)
                    .accessibilityIdentifier("ask-undo")
            }
        }
    }

    /// An answer taller than a third of the page scrolls in its place, so the page above keeps most of its height —
    /// and never taller than 240 on the phone and 320 on the Mac, however tall the page.
    static func answerHeight(page: CGFloat) -> CGFloat {
        #if os(iOS)
        let most: CGFloat = 240
        #else
        let most: CGFloat = 320
        #endif
        return page > 0 ? min(most, page / 3) : most
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

#if os(macOS)
/// A panel's card on the Mac — Version history (docs/09 "Transcript reader") — drawn over the detail the way its other
/// questions are, sized like them, under a title row with its close icon. The phone's is a bottom sheet of its own.
struct PanelFrame<Content: View>: View {
    let title: String
    let close: () -> Void
    @ViewBuilder let content: () -> Content
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    /// The body's own height, which the card is as tall as until the window stops it.
    @State private var bodyHeight: CGFloat = 0

    var body: some View {
        // 2026-10-10 (3.3): the title row and its close icon stay put; the body under them scrolls once the card
        // is as tall as the window lets it be.
        VStack(alignment: .leading, spacing: Space.sm) {
            HStack(spacing: Space.s) {
                Text(verbatim: title)
                    .font(blueprint.fonts.title)
                    .foregroundStyle(blueprint.palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                BlueprintIconButton("xmark", label: RecKitStrings.localized("Close"), action: close)
                    .accessibilityIdentifier("panel-close")
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
            // docs/09 "Component measurements" — Dialog: the card's edge is `inputBorder`; the grid is decoration.
            RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
        }
        .onExitCommand(perform: close)
    }
}
#endif

/// docs/15 "iPhone providers": the permission a summary, a question or a fill of the title and names waits on — drawn
/// where the user is: the detail, or the title sheet after Stop.
struct SummaryConsentDialog: View {
    let targets: [TransferTarget]
    @Binding var trainingOff: Bool
    let answer: (Bool) -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        BlueprintDialog(title: RecKitStrings.localized("Transfer permission needed")) {
            BlueprintButton(RecKitStrings.localized("Cancel"), tone: .quiet, minWidth: minTouch) { answer(false) }
            BlueprintButton(RecKitStrings.localized("Allow transfers and continue"), tone: .primary) { answer(true) }
                .disabled(TrainingOptOut.required(targets) && !trainingOff)
                .accessibilityIdentifier("summary-allow-transfer")
        } content: {
            TransferDisclosureList(targets: targets, trainingOff: $trainingOff)
        }
    }
}
#endif
