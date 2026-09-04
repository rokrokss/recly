import ReclyCore
import RecKit
import SwiftUI

/// docs/13 I5 "워크플로우 편집", drawn as docs/09 화면 원칙 3 asks: the editor is a node graph —
/// the trigger, then one square node per step, joined by straight connectors with a `+` on each —
/// and everything about the selected node is in the inspector under it.
///
/// The rules are the Mac's, because it is the same `WorkflowsModel` underneath: one write mutex, the
/// stale refusal, secrets in the Keychain and a `whsec_` shown exactly once.
/// So is the inspector itself, which is RecKit's (`StepInspector`, `TriggerInspector`,
/// `SecretFormView`); what this screen owns is the list and the vertical graph around it.
struct WorkflowsView: View {
    @ObservedObject var model: RecordingModel
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        Group {
            if let editor = model.workflowEditor {
                Editing(model: editor)
            } else {
                // The core opens asynchronously at launch; there is nothing to edit before it does.
                // [RecordingModel.status] and not [note]: the note is a key, resolved there.
                Text(model.status)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .dotGridBackground()
    }
}

private struct Editing: View {
    @ObservedObject var model: WorkflowsModel

    var body: some View {
        Group {
            if model.editor != nil {
                EditorScreen(model: model)
            } else {
                ListScreen(model: model)
            }
        }
        .task { await model.reload() }
    }
}

// MARK: - List

private struct ListScreen: View {
    @ObservedObject var model: WorkflowsModel
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: loc("Workflows"), meta: "\(model.items.count)") {
                BlueprintButton(loc("New")) { model.add() }
                    .accessibilityIdentifier("newWorkflow")
            }
            ScrollView {
                VStack(spacing: 0) {
                    notices
                    SectionHeader(loc("Workflows")).padding(.horizontal, Space.m)
                    ForEach(model.items) { item in
                        row(item)
                    }
                    // ADR-016: a phone with no workflow records nothing, so the list saying nothing
                    // at all is the one thing it must not do.
                    if model.items.isEmpty {
                        Text(verbatim: loc("No workflows yet."))
                            .font(blueprint.fonts.bodySmall)
                            .foregroundStyle(blueprint.palette.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(Space.m)
                    }
                    SectionHeader(loc("Secrets on this phone")).padding(.horizontal, Space.m)
                    secrets
                }
                .padding(.bottom, Space.l)
            }
        }
        // ADR-016: a workflow leaves this phone and does not come back — the document is local, so
        // there is no copy anywhere to restore it from. Asked in the same words Android asks it in.
        .blueprintDialog(item: $model.confirmDelete) { item in
            WorkflowDeleteDialog(item: item) { workflow in
                Task { await model.delete(workflow) }
            } cancel: {
                model.confirmDelete = nil
            }
        }
    }

    @ViewBuilder
    private var notices: some View {
        if let message = model.message {
            Banner(message.text)
                .padding(.horizontal, Space.m)
                .padding(.top, Space.s)
        }
    }

    /// ADR-016: name, steps, and the one thing a row decides — whether this phone records with it.
    /// The badge and the button are the same control seen from its two states, so exactly one of
    /// them shows. Two lines rather than one: a badge and three buttons on one row is what the
    /// mockup does not do — every one of them ends up truncated to a syllable.
    private func row(_ item: WorkflowItem) -> some View {
        SectionBlock {
            // docs/09 "접근성": the row itself opens the editor, as Android's does — an Edit button
            // beside the others would be a fourth target for the thing the whole row already is.
            // The hint is what names the action, as Android's `onClickLabel` does.
            Button { model.edit(item.id) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(verbatim: item.name.isEmpty ? loc("Unnamed") : item.name)
                            .font(blueprint.fonts.rowTitle)
                            .foregroundStyle(blueprint.palette.text)
                        if item.isDeviceDefault {
                            StatusBadge(LedgerStatus(code: loc("In use"), tone: .accent))
                        }
                    }
                    Text(verbatim: item.steps)
                        .font(blueprint.fonts.monoSmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                    // docs/05 "새 기기": the definition arrived but the key did not.
                    if !item.missingSecrets.isEmpty {
                        Text(verbatim: loc("No key on this device: %@", item.missingSecrets.joined(separator: ", ")))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.warningInk)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text(verbatim: loc("Edit the workflow")))
            .accessibilityIdentifier("workflow-open")
            HStack(spacing: Space.s) {
                if !item.isDeviceDefault {
                    BlueprintButton(loc("Use"), tone: .quiet) {
                        Task { await model.setDeviceDefault(item) }
                    }
                }
                // ADR-016: deleting it is allowed, and what it costs is said in the confirmation
                // rather than on the row — the row is not where the answer is given.
                BlueprintButton(loc("Delete"), tone: .danger) { model.confirmDelete = item }
                    .accessibilityIdentifier("workflow-delete")
                if !item.missingSecrets.isEmpty {
                    BlueprintButton(loc("Add a secret")) {
                        model.openSecrets(prefill: item.missingSecrets.first)
                    }
                }
            }
        }
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
        } else {
            SectionBlock {
                BlueprintButton(loc("Add a secret")) { model.openSecrets() }
            }
        }
    }
}

// MARK: - Editor

/// docs/09 화면 원칙 3: the graph is the whole of the structure, and the inspector under it is the
/// whole of the detail. The `+` on a connector inserts a step *at that position* — the last one, the
/// run that closes the graph, appends.
private struct EditorScreen: View {
    @ObservedObject var model: WorkflowsModel
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    /// The node the inspector is showing: nil is the trigger, otherwise a step's position. Kept by
    /// the screen rather than by the model — it is where the user is looking, not what is stored.
    @State private var openStep: Int?
    /// The `+` that was tapped, while it asks which kind of step to insert there.
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
                    .accessibilityIdentifier("saveWorkflow")
                }
            }
            notices(editor)

            ScrollView {
                VStack(spacing: Space.xs) {
                    NodeGraph(
                        axis: .vertical,
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
                .padding(.vertical, Space.m)
                .frame(maxWidth: .infinity)
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
            .frame(maxHeight: 360)
        }
        // docs/09 화면 원칙 5: four choices is a list, not four buttons — a `confirmationDialog` is
        // the platform's own sheet with the platform's own shape, and this one is drawn like the
        // rest of the app (the Android editor asks the same question the same way).
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
