// The workflow editor exists on the Mac (docs/12 "워크플로우 편집 창") and on the phone (docs/13 I5);
// the watch never edits one and never touches Drive (ADR-002).
#if os(macOS) || os(iOS)
import ReclyCore
import SwiftUI

/// docs/09 화면 원칙 3: everything about the selected node, under the graph. The two shells own the
/// chrome around it — a window with a horizontal graph, a tab with a vertical one — and share every
/// line of what the inspector itself draws, because a `transcribe` step is the same step on both and
/// a field that drifted between them would be a rule with two answers.
///
/// What is genuinely different is the platform and not the design, so it is `#if` here rather than a
/// parameter: a Mac has no software keyboard to configure and gives a numeric field a width instead,
/// a phone has no room for a row that ends in a `Spacer`.
///
/// The sentences are RecKit's own ([RecKitStrings]), as [WorkflowTransferSection]'s are: this is one piece of
/// screen and there is no reason for two catalogs to carry it.
public struct StepInspector: View {
    @ObservedObject private var model: WorkflowsModel
    private let editor: EditorState
    private let position: Int
    private let step: StepEdit
    @Binding private var openStep: Int?
    /// The phone's provider dialog, which the Mac's pop-up menu has no need of.
    @State private var pickingProvider = false
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: reading the locale is what declares the dependency that redraws this body in
    /// a language picked while the editor is open.
    @Environment(\.locale) private var locale

    public init(
        model: WorkflowsModel,
        editor: EditorState,
        position: Int,
        step: StepEdit,
        openStep: Binding<Int?>
    ) {
        self.model = model
        self.editor = editor
        self.position = position
        self.step = step
        _openStep = openStep
    }

    /// docs/08's order constraint this step breaks, as the parser's own token. Read off the editor
    /// rather than passed in: it is live, and moving a step changes it with no field being wrong.
    private var order: String? { editor.order[step.id] }

    public var body: some View {
        InspectorTitle(title: step.label, code: step.id)
        // docs/08: an order constraint is broken by moving a step, not by typing in it, so it is
        // said as soon as it is true rather than when the save is refused.
        if order != nil {
            Text(verbatim: WorkflowsModel.orderMessage)
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.danger)
        }
        switch step {
        case .drive(let drive):
            BlueprintField(
                loc("Folder template"),
                text: driveField(drive.folder) { $0.folder = $1 },
                mono: true
            )
            .plainTextEntry()
            SwitchRow(
                title: loc("Include meta.json"),
                isOn: Binding(
                    get: { drive.includeMeta },
                    set: { value in
                        model.updateStep(at: position) { edit in
                            guard case .drive(var drive) = edit else { return }
                            drive.includeMeta = value
                            edit = .drive(drive)
                        }
                    }
                )
            )

        case .hook(let hook):
            BlueprintField(loc("URL"), text: hookField(hook.url) { $0.url = $1 }, mono: true)
                .urlEntry()
            BlueprintField(
                loc("Secret name"),
                text: hookField(hook.secretRef ?? "") { $0.secretRef = $1.isEmpty ? nil : $1 },
                mono: true
            )
            .plainTextEntry()

        // docs/08 `transcribe`. `invokeUrl` is an addressing scheme some providers need, some
        // accept and the rest never read (`WorkflowParser.invokeUrlUse`), so the field is shown for
        // the first two kinds and the value goes with it for the third — a URL the form does not
        // show is one the parser would refuse for a field nobody can see. Between the first two the
        // value stays: both read it.
        case .transcribe(let transcribe):
            label(loc("Provider"))
            providerPicker(transcribe.provider)
            // docs/08 "폴링 · 상태": a provider that answers on one long request is the one a
            // phone's background budget may cut off, and this is where that choice is made. The Mac
            // has no such budget, so it does not say it.
            #if os(iOS)
            if SttProviders.shared.synchronous(name: transcribe.provider) {
                hint(loc(
                    "This provider answers on one long request. On a phone, an asynchronous provider is more reliable in the background."
                ))
                .accessibilityIdentifier("provider-synchronous-hint")
            }
            #endif
            ProviderDisclosure()
            secretField(transcribe.secretRef) { edit, value in
                guard case .transcribe(var step) = edit else { return }
                step.secretRef = value
                edit = .transcribe(step)
            }
            let invokeUrlUse = WorkflowParser.shared.invokeUrlUse(provider: transcribe.provider)
            if invokeUrlUse != .none {
                BlueprintField(
                    loc("Invoke URL"),
                    text: transcribeField(transcribe.invokeUrl) { $0.invokeUrl = $1 },
                    mono: true
                )
                .urlEntry()
                hint(invokeUrlHint(invokeUrlUse))
            }
            label(loc("Language"))
            FlowLayout {
                ForEach([Language.ko, .en, .koEn, .auto], id: \.self) { language in
                    BlueprintChip(language.tag, selected: transcribe.language == language) {
                        model.updateStep(at: position) { edit in
                            guard case .transcribe(var step) = edit else { return }
                            step.language = language
                            edit = .transcribe(step)
                        }
                    }
                }
            }
            SwitchRow(
                title: loc("Separate speakers"),
                isOn: Binding(
                    get: { transcribe.diarize },
                    set: { value in
                        model.updateStep(at: position) { edit in
                            guard case .transcribe(var step) = edit else { return }
                            step.diarize = value
                            edit = .transcribe(step)
                        }
                    }
                )
            )
            speakerBounds(transcribe)
            hint(loc("1–10. A recording that knows how many people were there overrides this."))
        }

        Text(verbatim: loc("On failure"))
            .font(blueprint.fonts.label)
            .tracking(0.6)
            .foregroundStyle(blueprint.palette.textMuted)
        FlowLayout {
            BlueprintChip(loc("onError.abort"), selected: step.onError == .abort) {
                model.updateStep(at: position) { $0.onError = .abort }
            }
            BlueprintChip(loc("onError.continue"), selected: step.onError == .continue) {
                model.updateStep(at: position) { $0.onError = .continue }
            }
        }
        retryFields
        HStack(spacing: Space.s) {
            // The order is the order the steps run in (docs/02), and the graph is not a drag
            // surface on a phone.
            BlueprintButton(loc("Move up")) { move(to: position - 1) }
                .disabled(position == 0)
            BlueprintButton(loc("Move down")) { move(to: position + 2) }
                .disabled(position == editor.edit.steps.count - 1)
            BlueprintButton(loc("Delete"), tone: .danger) {
                model.removeStep(at: position)
                openStep = nil
            }
        }
    }

    /// docs/07 rule 4: a provider id is what the document carries, so the control says it verbatim
    /// and in monospace. Fourteen of them are a list and not a row of chips, in
    /// `WorkflowParser.STT_PROVIDERS` order — the order the core declares, which this inspector no
    /// longer sorts.
    ///
    /// What opens the list is what each platform already has, as [LanguageSection] does it: the
    /// Mac's own pop-up menu on the Mac, and a dialog on the phone, where a menu hanging off a
    /// tapped row is not the idiom.
    @ViewBuilder
    private func providerPicker(_ provider: String) -> some View {
        #if os(macOS)
        BlueprintDropdown(
            loc("Provider"),
            options: WorkflowParser.shared.STT_PROVIDERS.map(ProviderOption.init),
            selection: Binding(
                get: { ProviderOption(name: provider) },
                set: { selectProvider($0.name) }
            ),
            mono: true,
            title: { $0.name }
        )
        .accessibilityIdentifier("step-provider")
        #else
        BlueprintButton(provider, tone: .quiet, mono: true) { pickingProvider = true }
            // docs/09 "접근성": the button says the value, and the label above it says what the
            // value is of — a reader given only the value would hear "openai" and no question.
            .accessibilityLabel(Text(verbatim: loc("Provider")))
            .accessibilityValue(Text(verbatim: provider))
            .accessibilityIdentifier("step-provider")
            .blueprintDialog(isPresented: $pickingProvider) {
                BlueprintDialog(title: loc("Provider")) {
                    // Nothing to cancel: the choice is applied the moment it is made, so the one
                    // answer here closes a question that has already been answered.
                    BlueprintButton(loc("Close"), tone: .quiet) { pickingProvider = false }
                } content: {
                    ForEach(WorkflowParser.shared.STT_PROVIDERS, id: \.self) { name in
                        BlueprintRadioRow(name, selected: provider == name) {
                            pickingProvider = false
                            selectProvider(name)
                        }
                        .accessibilityIdentifier("provider-" + name)
                    }
                }
            }
        #endif
    }

    /// The one rule the three editors share on a provider change: an `invokeUrl` the new provider
    /// never reads goes with it, because the form stops showing it (`WorkflowParser.invokeUrlUse`),
    /// and an empty one a provider requires starts as that provider's template
    /// (`WorkflowParser.invokeUrlTemplate`), so the user edits a URL instead of composing one.
    private func selectProvider(_ name: String) {
        model.updateStep(at: position) { edit in
            guard case .transcribe(var value) = edit else { return }
            value.provider = name
            if WorkflowParser.shared.invokeUrlUse(provider: name) == .none {
                value.invokeUrl = ""
            } else if value.invokeUrl.isEmpty, let template = WorkflowParser.shared.invokeUrlTemplate(provider: name) {
                value.invokeUrl = template
            }
            edit = .transcribe(value)
        }
    }

    /// docs/08 `speakers`. A Mac gives each number a width and lets the row end in whitespace; a
    /// phone has no width to spare and gives the pair a number pad instead.
    @ViewBuilder
    private func speakerBounds(_ transcribe: StepEdit.TranscribeEdit) -> some View {
        #if os(macOS)
        HStack(alignment: .bottom, spacing: Space.s) {
            BlueprintField(
                loc("Speakers, min"),
                text: transcribeField(transcribe.speakersMin) { $0.speakersMin = $1 },
                mono: true
            )
            .frame(width: 130)
            BlueprintField(
                loc("Speakers, max"),
                text: transcribeField(transcribe.speakersMax) { $0.speakersMax = $1 },
                mono: true
            )
            .frame(width: 130)
            Spacer(minLength: 0)
        }
        #else
        HStack(alignment: .bottom, spacing: Space.s) {
            BlueprintField(
                loc("Speakers, min"),
                text: transcribeField(transcribe.speakersMin) { $0.speakersMin = $1 },
                mono: true
            )
            BlueprintField(
                loc("Speakers, max"),
                text: transcribeField(transcribe.speakersMax) { $0.speakersMax = $1 },
                mono: true
            )
        }
        .keyboardType(.numberPad)
        #endif
    }

    /// docs/02 `retry`: three numbers, laid out the way each platform lays numbers out.
    @ViewBuilder
    private var retryFields: some View {
        #if os(macOS)
        HStack(alignment: .bottom, spacing: Space.s) {
            BlueprintField(loc("Retries"), text: retry(\.maxAttempts), mono: true).frame(width: 90)
            BlueprintField(loc("First delay (s)"), text: retry(\.initialDelaySec), mono: true).frame(width: 130)
            BlueprintField(loc("Max delay (s)"), text: retry(\.maxDelaySec), mono: true).frame(width: 130)
            Spacer(minLength: 0)
        }
        #else
        HStack(spacing: Space.s) {
            BlueprintField(loc("Retries"), text: retry(\.maxAttempts), mono: true)
            BlueprintField(loc("First delay (s)"), text: retry(\.initialDelaySec), mono: true)
            BlueprintField(loc("Max delay (s)"), text: retry(\.maxDelaySec), mono: true)
        }
        .keyboardType(.numberPad)
        #endif
    }

    /// The inspector's own field name: small, tracked out, never shouted (docs/09 "타이포").
    private func label(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.label)
            .tracking(0.6)
            .foregroundStyle(blueprint.palette.textMuted)
    }

    /// docs/08: the two things an `invokeUrl` can be — the only address the provider has, or an
    /// override of a public default the user is better off leaving alone.
    private func invokeUrlHint(_ use: InvokeUrlUse) -> String {
        if use == .required {
            return loc("The provider's endpoint URL. Required for this provider.")
        }
        return loc(
            "Leave empty for the provider's default endpoint. Set it for a self-hosted or regional endpoint."
        )
    }

    /// The line under a field that says what leaving it empty means.
    private func hint(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.sans(TypeSize.small))
            .foregroundStyle(blueprint.palette.textMuted)
    }

    private func move(to index: Int) {
        model.moveStep(from: IndexSet(integer: position), to: index)
        openStep = min(max(index > position ? index - 1 : index, 0), editor.edit.steps.count - 1)
    }

    private func driveField(
        _ value: String,
        _ set: @escaping (inout StepEdit.DriveEdit, String) -> Void
    ) -> Binding<String> {
        Binding(get: { value }, set: { next in
            model.updateStep(at: position) { edit in
                guard case .drive(var drive) = edit else { return }
                set(&drive, next)
                edit = .drive(drive)
            }
        })
    }

    private func hookField(
        _ value: String,
        _ set: @escaping (inout StepEdit.HookEdit, String) -> Void
    ) -> Binding<String> {
        Binding(get: { value }, set: { next in
            model.updateStep(at: position) { edit in
                guard case .hook(var hook) = edit else { return }
                set(&hook, next)
                edit = .hook(hook)
            }
        })
    }

    private func transcribeField(
        _ value: String,
        _ set: @escaping (inout StepEdit.TranscribeEdit, String) -> Void
    ) -> Binding<String> {
        Binding(get: { value }, set: { next in
            model.updateStep(at: position) { edit in
                guard case .transcribe(var step) = edit else { return }
                set(&step, next)
                edit = .transcribe(step)
            }
        })
    }

    /// docs/05 "시크릿": what the document carries is the *name* of a key, never the key itself. So
    /// the step picks one of the names this device has in its Keychain, and a key it does not have
    /// yet is entered here — the value goes to the Keychain and only the name reaches the step.
    @ViewBuilder
    private func secretField(
        _ value: String,
        _ set: @escaping (inout StepEdit, String) -> Void
    ) -> some View {
        label(loc("API key"))
        FlowLayout {
            BlueprintChip(loc("None"), selected: value.isEmpty) {
                model.updateStep(at: position) { set(&$0, "") }
            }
            // docs/07 rule 4: a secret name is what the document carries, not a word.
            ForEach(offered(value), id: \.self) { name in
                BlueprintChip(name, selected: value == name) {
                    model.updateStep(at: position) { set(&$0, name) }
                }
            }
            BlueprintButton(loc("New…")) { model.openSecrets(step: step.id) }
        }
        // docs/05 "새 기기": the name arrived in the document, the value did not — so the key is
        // entered here, under the step that is about to ask for it. The window has room for the line
        // and the button side by side; the phone does not.
        if !value.isEmpty, !model.secrets.contains(value) {
            #if os(macOS)
            HStack(spacing: Space.s) {
                missingSecretLine(value)
                BlueprintButton(loc("Enter it")) {
                    model.openSecrets(prefill: value, step: step.id)
                }
            }
            #else
            missingSecretLine(value)
            BlueprintButton(loc("Enter it")) {
                model.openSecrets(prefill: value, step: step.id)
            }
            #endif
        }
        if let form = model.secretForm, form.stepId == step.id {
            SecretFormView(model: model, form: form) { name in
                model.updateStep(at: position) { set(&$0, name) }
            }
        }
    }

    private func missingSecretLine(_ value: String) -> some View {
        Text(verbatim: loc("This device has no value for ‘%@’", value))
            .font(blueprint.fonts.sans(TypeSize.small))
            .foregroundStyle(blueprint.palette.warningInk)
    }

    /// The names on offer: this device's, and — first — the one the document named that it does not
    /// have, because that is still this step's key rather than one to be swapped for another.
    private func offered(_ value: String) -> [String] {
        value.isEmpty || model.secrets.contains(value) ? model.secrets : [value] + model.secrets
    }

    private func retry(_ keyPath: WritableKeyPath<RetryEdit, String>) -> Binding<String> {
        Binding(
            get: { step.retry[keyPath: keyPath] },
            set: { value in
                model.updateStep(at: position) { edit in
                    var retry = edit.retry
                    retry[keyPath: keyPath] = value
                    edit.retry = retry
                }
            }
        )
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }

    private func loc(_ key: String, _ argument: String) -> String {
        RecKitStrings.localized(key, argument)
    }
}

/// The trigger node's inspector: what the workflow is. ADR-016 left it two fields — which device
/// runs it is that device's own local pointer, set on the list, and never a field of the definition.
///
/// The minimum length is a number, so the Mac puts it beside the name in a fixed width and the phone
/// puts it last with a number pad under it — the one place the two inspectors are laid out
/// differently at all.
public struct TriggerInspector: View {
    @ObservedObject private var model: WorkflowsModel
    private let editor: EditorState
    @Environment(\.locale) private var locale

    public init(model: WorkflowsModel, editor: EditorState) {
        self.model = model
        self.editor = editor
    }

    public var body: some View {
        InspectorTitle(title: loc("Recording finished"), code: "trigger")
        #if os(macOS)
        HStack(alignment: .bottom, spacing: Space.s) {
            BlueprintField(loc("Name"), text: field(\.name))
            BlueprintField(loc("Minimum length (s)"), text: field(\.minDurationSec), mono: true)
                .frame(width: 140)
        }
        #else
        BlueprintField(loc("Name"), text: field(\.name))
        #endif
        #if os(iOS)
        BlueprintField(loc("Minimum length (s)"), text: field(\.minDurationSec), mono: true)
            .keyboardType(.numberPad)
        #endif
    }

    private func field<T>(_ keyPath: WritableKeyPath<WorkflowEdit, T>) -> Binding<T> {
        Binding(
            get: { editor.edit[keyPath: keyPath] },
            set: { value in model.update { $0[keyPath: keyPath] = value } }
        )
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// What the inspector is about: the node's name, and — in monospace — the id the document and the
/// logs know it by (docs/07 rule 4).
public struct InspectorTitle: View {
    @Environment(\.blueprint) private var blueprint
    private let title: String
    private let code: String

    public init(title: String, code: String) {
        self.title = title
        self.code = code
    }

    public var body: some View {
        HStack(spacing: Space.s) {
            Text(verbatim: title)
                .font(blueprint.fonts.sans(TypeSize.body, weight: .semibold))
                .foregroundStyle(blueprint.palette.text)
            Text(verbatim: code)
                .font(blueprint.fonts.monoSmall)
                .foregroundStyle(blueprint.palette.textMuted)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

/// docs/05 "시크릿": the name goes into the document and the value goes into the Keychain, so this is
/// the one form in the editor whose second field is never read back.
public struct SecretFormView: View {
    @ObservedObject private var model: WorkflowsModel
    private let form: SecretForm
    /// What the step editor hangs the stored name on; the secret list has nowhere to put it.
    private let onSaved: (String) -> Void
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: the refusal on the form is a message the model kept, and the sentence is
    /// made here. Reading the locale is what declares the dependency that redraws it.
    @Environment(\.locale) private var locale

    public init(
        model: WorkflowsModel,
        form: SecretForm,
        onSaved: @escaping (String) -> Void = { _ in }
    ) {
        self.model = model
        self.form = form
        self.onSaved = onSaved
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            // docs/05 "시크릿": the value never leaves this device, so a workflow that arrives on
            // another one arrives without it — said before the form rather than after the step has
            // already failed there for want of a key (the same line Android's secrets screen
            // carries above its own form).
            Text(verbatim: loc(
                "Stored on this device only. On another device, enter it again under the same name."
            ))
            .font(blueprint.fonts.sans(TypeSize.small))
            .foregroundStyle(blueprint.palette.textMuted)
            BlueprintField(
                loc("Name (lowercase, digits, underscores)"),
                text: Binding(
                    get: { form.name },
                    set: { model.secretForm?.name = $0; model.secretForm?.error = nil }
                ),
                mono: true
            )
            .plainTextEntry()
            if form.generated {
                generatedValue(form.value)
                // docs/04: shown once and never readable again — the clipboard already has it.
                // `verbatim` over a lookup rather than a `Text` key: a `LocalizedStringKey` inside a
                // package would be resolved against the *app's* bundle, and this sentence is
                // RecKit's now.
                Text(verbatim: loc(
                    "Copy the generated value now. It cannot be read again once it is saved (it is on the clipboard)."
                ))
                .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
            } else {
                BlueprintField(
                    loc("Value"),
                    text: Binding(
                        get: { form.value },
                        set: { model.secretForm?.value = $0; model.secretForm?.generated = false }
                    ),
                    mono: true,
                    secure: true
                )
            }
            if let error = form.error {
                Text(verbatim: error.text)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.danger)
            }
            buttons
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(blueprint.palette.surface)
    }

    /// Five buttons fit across a window; on a phone they are two rows, with the answers to the form
    /// on the second one.
    @ViewBuilder
    private var buttons: some View {
        #if os(macOS)
        HStack(spacing: Space.s) {
            BlueprintButton(loc("Generate a webhook secret")) { model.generateSecret() }
            if form.generated {
                BlueprintButton(loc("Copy")) { model.copyGeneratedSecret() }
            }
            Spacer(minLength: 0)
            BlueprintButton(loc("Save"), tone: .primary) { save() }
            BlueprintButton(loc("Cancel"), tone: .quiet) { model.closeSecrets() }
        }
        #else
        HStack(spacing: Space.s) {
            BlueprintButton(loc("Generate a webhook secret")) { model.generateSecret() }
            if form.generated {
                BlueprintButton(loc("Copy")) { model.copyGeneratedSecret() }
            }
        }
        HStack(spacing: Space.s) {
            BlueprintButton(loc("Save"), tone: .primary) { save() }
            BlueprintButton(loc("Cancel"), tone: .quiet) { model.closeSecrets() }
        }
        #endif
    }

    /// docs/05 "시크릿": the name is what the step carries, so the step that opened this form is
    /// handed it — a save that was refused hands back nothing and leaves the form open.
    private func save() {
        Task {
            if let name = await model.saveSecret() {
                model.closeSecrets()
                onSaved(name)
            }
        }
    }

    /// A label, not a field: a copy made from a selection would put the same signing key on the
    /// pasteboard without the local-only and concealed options `copyGeneratedSecret` sets, so the
    /// Copy button beside it is the only way it leaves this screen.
    private func generatedValue(_ value: String) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(verbatim: loc("Value"))
                .font(blueprint.fonts.label)
                .tracking(0.6)
                .foregroundStyle(blueprint.palette.textMuted)
            Text(verbatim: value)
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.text)
                .textSelection(.disabled)
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// A provider id as a list entry. `BlueprintDropdown` picks between things that identify themselves,
/// and a wire name *is* its own identity — a wrapper rather than a retroactive conformance on
/// `String`, which would be this package's to impose on everything that imports it.
private struct ProviderOption: Hashable, Identifiable {
    let name: String
    var id: String { name }
}

public extension Array {
    /// The inspector's position can outlive the step it names — a delete, a pull that replaced the
    /// workflow — and an out-of-range read there is a crash rather than an empty pane. Both editors
    /// index their steps through it.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

private extension View {
    /// A field whose value is data rather than prose — a folder template, a secret name, a model
    /// id. Nothing at all on a Mac, which has no software keyboard to configure.
    func plainTextEntry() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        self
        #endif
    }

    /// The same, for the two fields that are URLs.
    func urlEntry() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        #else
        self
        #endif
    }
}
#endif
