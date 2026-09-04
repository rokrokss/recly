import ReclyCore
import RecKit
import SwiftUI

/// docs/12 M7 "워크플로우 편집 창". A `Window` rather than a `Settings` scene: this is the main
/// surface for editing on a desktop, and it has to be openable from the menu bar while the app has
/// no other window.
///
/// What the window owns is the chrome — the list, the horizontal graph and the dialogs around them.
/// Everything inside the inspector is RecKit's (`StepInspector`, `TriggerInspector`,
/// `SecretFormView`), because a `transcribe` step is the same step here and on the phone.
struct WorkflowWindow: View {
    static let id = "workflows"

    @ObservedObject var menu: MenuModel
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        Group {
            if let model = menu.workflowEditor {
                WorkflowsView(model: model)
            } else {
                // The core opens asynchronously at launch; there is nothing to edit before it does.
                Text(verbatim: menu.status)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 720, minHeight: 480)
        .dotGridBackground()
    }
}

private struct WorkflowsView: View {
    @ObservedObject var model: WorkflowsModel

    var body: some View {
        Group {
            if model.editor != nil {
                EditorPane(model: model)
            } else {
                ListPane(model: model)
            }
        }
        .task { await model.reload() }
    }
}

// MARK: - List

private struct ListPane: View {
    @ObservedObject var model: WorkflowsModel
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: loc("Workflows"), meta: "\(model.items.count)") {
                BlueprintButton(loc("New workflow")) { model.add() }
            }
            HairLine()
            ScrollView {
                VStack(spacing: 0) {
                    if let message = model.message {
                        Banner(message.text)
                            .padding(.horizontal, Space.m)
                            .padding(.top, Space.s)
                    }
                    ForEach(model.items) { item in
                        row(item)
                    }
                    // The section's one action lives on its heading: the list under it is the
                    // secrets themselves, and an "add" row is not one of them.
                    SectionHeader(loc("Secrets on this Mac")) {
                        BlueprintButton(loc("Add a secret")) { model.openSecrets() }
                    }
                    .padding(.horizontal, Space.m)
                    secrets
                }
                .padding(.bottom, Space.l)
            }
        }
        // ADR-016: a workflow leaves this Mac and does not come back — the document is local, so
        // there is no copy anywhere to restore it from. Asked in the same words Android asks it in.
        .blueprintDialog(item: $model.confirmDelete) { item in
            WorkflowDeleteDialog(item: item) { workflow in
                Task { await model.delete(workflow) }
            } cancel: {
                model.confirmDelete = nil
            }
        }
    }

    /// ADR-016: name, steps, and the one thing a row decides — whether this Mac records with it.
    /// The badge and the button are the same control seen from its two states, so exactly one of
    /// them shows. docs/09 "목록 = 원장": one line per workflow on the table's own surface, with the
    /// same insets and the same rule under it every other table row has — the pieces rather than
    /// `SectionRow` itself, whose title is a string and cannot carry the badge beside the name.
    ///
    /// Opening the editor is the row, not a button on it. Only the left column is the button —
    /// a button with buttons inside it is one target that swallows the others, which is why the
    /// ledger in `RecordingsWindow` keeps its actions outside the row too — and it fills the width,
    /// so everything up to the trailing controls opens the workflow.
    private func row(_ item: WorkflowItem) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { model.edit(item.id) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(verbatim: item.name.isEmpty ? loc("Unnamed") : item.name)
                                .font(blueprint.fonts.rowTitle)
                                .foregroundStyle(blueprint.palette.text)
                                // A long name ends rather than pushing the badge off the row.
                                .lineLimit(1)
                            if item.isDeviceDefault {
                                StatusBadge(LedgerStatus(code: loc("In use"), tone: .accent))
                            }
                        }
                        Text(verbatim: item.steps)
                            .font(blueprint.fonts.monoSmall)
                            .foregroundStyle(blueprint.palette.textMuted)
                            .lineLimit(1)
                        // docs/05 "새 기기": the definition arrived but the key did not.
                        if !item.missingSecrets.isEmpty {
                            Text(verbatim: loc(
                                "No key on this device: %@",
                                item.missingSecrets.joined(separator: ", ")
                            ))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.warningInk)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                // docs/09 "접근성": the row is announced by what is written on it, and "button"
                // alone says nothing about which of the things on it a click would do — the hint
                // says it, as Android's `onClickLabel` does on the same row.
                .accessibilityHint(Text(verbatim: loc("Edit the workflow")))
                .accessibilityIdentifier("open-workflow")
                if !item.isDeviceDefault {
                    BlueprintButton(loc("Use"), tone: .quiet) {
                        Task { await model.setDeviceDefault(item) }
                    }
                }
                // ADR-016: deleting it is allowed, and what it costs is said in the confirmation
                // rather than on the row — the row is not where the answer is given.
                BlueprintButton(loc("Delete"), tone: .danger) { model.confirmDelete = item }
                if !item.missingSecrets.isEmpty {
                    BlueprintButton(loc("Add a secret")) {
                        model.openSecrets(prefill: item.missingSecrets.first)
                    }
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, 12)
            .frame(minHeight: minTouch)
            HairLine()
        }
        .background(blueprint.palette.surface)
    }

    @ViewBuilder
    private var secrets: some View {
        ForEach(model.secrets, id: \.self) { name in
            SectionRow(title: name) {
                BlueprintButton(loc("Delete"), tone: .danger) {
                    Task { await model.deleteSecret(name) }
                }
            }
        }
        // A form a step opened belongs to that step, and is shown there rather than here.
        if let form = model.secretForm, form.stepId == nil {
            SecretFormView(model: model, form: form)
        }
    }
}

// MARK: - Editor

/// docs/09 화면 원칙 3, in a window: the graph runs *across* — the trigger, then one square node per
/// step, joined by straight connectors with a `+` on each — and the inspector for the selected node
/// stands under it.
private struct EditorPane: View {
    @ObservedObject var model: WorkflowsModel
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    /// The node the inspector is showing: nil is the trigger, otherwise a step's position. Kept by
    /// the screen rather than by the model — it is where the user is looking, not what is stored.
    @State private var openStep: Int?
    /// The `+` that was clicked, while it asks which kind of step to insert there.
    @State private var insertAt: Int?
    @State private var save: ProcessingState = .idle

    /// The editor is only ever shown when there is one; the binding keeps the rest honest.
    @ViewBuilder
    var body: some View {
        if let editor = model.editor {
            content(editor)
        }
    }

    private func content(_ editor: EditorState) -> some View {
        VStack(spacing: 0) {
            ScreenHeader(
                title: editor.edit.name.isEmpty
                    ? loc(editor.isNew ? "New workflow" : "Edit workflow")
                    : editor.edit.name
            ) {
                HStack(spacing: Space.s) {
                    BlueprintButton(loc("Cancel"), tone: .quiet) { model.cancel() }
                    ProcessingButton(loc("Save"), state: save, tone: .primary) {
                        save = .processing
                        Task {
                            await model.save()
                            // A save that worked closes the editor; one that was refused leaves it
                            // open with the reason on it, which is the honest outcome to report.
                            save = model.editor == nil ? .done : .failed
                        }
                    }
                    .disabled(editor.stale)
                }
            }
            notices(editor)

            ScrollView(.horizontal) {
                HStack(spacing: Space.s) {
                    NodeGraph(
                        axis: .horizontal,
                        count: 1 + editor.edit.steps.count,
                        insertLabel: loc("Add a step here"),
                        insert: { insertAt = $0 }
                    ) { index in
                        node(editor, at: index)
                    }
                    Text("End")
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
                .padding(.horizontal, Space.m)
                .padding(.vertical, Space.m)
            }

            HairLine()
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let position = openStep, let step = editor.edit.steps[safe: position] {
                        StepInspector(
                            model: model,
                            editor: editor,
                            position: position,
                            step: step,
                            openStep: $openStep
                        )
                    } else {
                        TriggerInspector(model: model, editor: editor)
                    }
                }
                .padding(.horizontal, Space.m)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        // docs/09 화면 원칙 5: four choices is a list, not four buttons — a `confirmationDialog` is
        // the platform's own sheet with the platform's own shape, and this one is drawn like the
        // rest of the app (the phone's editor asks the same question the same way).
        .blueprintDialog(
            isPresented: Binding(get: { insertAt != nil }, set: { if !$0 { insertAt = nil } })
        ) {
            BlueprintDialog(title: loc("Add a step here")) {
                BlueprintButton(loc("Cancel"), tone: .quiet) { insertAt = nil }
            } content: {
                // docs/02·docs/08: one row per step type, in the order a workflow runs them.
                ForEach(StepKind.allCases, id: \.self) { kind in
                    BlueprintButton(kind.label, tone: .accent) { insert(kind, into: editor) }
                        .disabled(!kind.canAdd(to: editor.edit.steps))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("add-\(kind.label)")
                }
            }
        }
    }

    @ViewBuilder
    private func node(_ editor: EditorState, at index: Int) -> some View {
        if index == 0 {
            GraphNode(
                kicker: loc("Trigger"),
                title: loc("Recording finished"),
                // The one thing left to say about the head of the graph, as a code rather than a
                // sentence — a minimum of 0 has nothing to say at all.
                detail: editor.edit.minimumCode,
                selected: openStep == nil,
                action: { openStep = nil }
            )
        } else if let step = editor.edit.steps[safe: index - 1] {
            GraphNode(
                kicker: "\(index) · \(step.label)",
                title: step.label,
                detail: summary(step),
                selected: openStep == index - 1,
                action: { openStep = index - 1 }
            )
        }
    }

    /// The two things that can be wrong with an open editor, in the order they matter.
    @ViewBuilder
    private func notices(_ editor: EditorState) -> some View {
        // Something replaced this workflow while it was open — a second window, an import. There is
        // no three-way merge, so the only honest offer is to start again from what is stored now.
        if editor.stale {
            HStack(spacing: Space.s) {
                Text(verbatim: WorkflowsModel.staleNotice.text)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                BlueprintButton(loc("Reopen")) { Task { await model.reopen() } }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
        }
        ForEach(editor.errors, id: \.self) { error in
            Text(verbatim: error)
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.danger)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Space.m)
                .padding(.vertical, 2)
        }
    }

    /// `addStep` appends, so an insert is an append and a move — the two writes the model already
    /// offers, in the order they are safe in.
    private func insert(_ kind: StepKind, into editor: EditorState) {
        guard let at = insertAt else { return }
        insertAt = nil
        model.addStep(kind)
        model.moveStep(from: IndexSet(integer: editor.edit.steps.count), to: at)
        openStep = at
    }

    /// The one line the node carries under its name: whatever the step is mostly about.
    private func summary(_ step: StepEdit) -> String {
        switch step {
        case .drive(let drive): return drive.folder
        case .hook(let hook): return hook.url
        case .transcribe(let transcribe): return "\(transcribe.provider) · \(transcribe.language.tag)"
        }
    }
}
