#if os(iOS) || os(macOS)
import Foundation
import ReclyCore
import RecKitSpeakers
import SwiftUI
import UniformTypeIdentifiers

/// A device settings draft. Saving affects future captures; imports are editable previews.
@MainActor
public final class ProcessingSettingsModel: ObservableObject {
    @Published public private(set) var draft: ProcessingDraft?
    @Published public private(set) var dirty = false
    @Published public private(set) var busy = false
    @Published public private(set) var importing = false
    @Published public private(set) var message: UiMessage?
    @Published public private(set) var localLanguages: [Language] = []
    @Published public private(set) var local: LocalEngineInfo?
    @Published public private(set) var summaryKey = "On device"
    @Published public private(set) var providerSummary: String?
    @Published public private(set) var secretNames: [String] = []
    @Published public private(set) var providers: [String] = []
    /// docs/15: the destinations a save is waiting on the user's permission for. Asked here, when the
    /// provider is chosen, and never while recording; once allowed, the same destination saves quietly.
    @Published public private(set) var consentNeeded: [TransferTarget] = []
    private var stored: ProcessingSettingsStateReady?
    private let core: ReclyCore_
    /// The shell's one model download, which this screen's "Download model" shares with the
    /// banner, the rows and the first-run card.
    public let download: ModelDownload
    public var onSaved: (() -> Void)?

    public init(core: ReclyCore_, download: ModelDownload? = nil) {
        self.core = core
        self.download = download ?? ModelDownload(core: core)
    }
    public func reload() async {
        do {
            providers = WorkflowParser.shared.STT_PROVIDERS.filter { core.deps.transcriptionPolicy.providerAvailable(provider: $0) }
            let state = try await core.initializeProcessing()
            stored = state
            secretNames = try await core.secrets.names()
            let draft = ProcessingDraft.companion.from(settings: state.document.settings)
            self.draft = draft
            dirty = false; importing = false
            updateSummary(draft)
            await refreshLocal()
        } catch { failed(error) }
    }
    public func edit(_ change: (ProcessingDraft) -> Void) {
        guard let draft else { return }
        let next = draft.snapshot(); change(next); self.draft = next; dirty = true; message = nil
    }
    public func selectMode(_ mode: TranscriptionMode) {
        edit { $0.selectAppleTranscriptionMode(mode, preferredLanguage: TranscriptionLanguages.shared.preferred(deviceLocale: CoreBridge.deviceLanguage)) }
        local = nil
        Task { await refreshLocal() }
    }
    public func save() async {
        guard let draft, let stored, !busy, languageSupported else { return }
        busy = true; defer { busy = false }
        do {
            if draft.mode == .external {
                let step = Step.Transcribe(id: "transcribe", onError: .abort, retry: Retry(maxAttempts: 5, initialDelaySec: 30, maxDelaySec: 3600), provider: draft.provider, secretRef: draft.secretRef, invokeUrl: draft.invokeUrl.isEmpty ? nil : draft.invokeUrl, language: draft.language, diarize: draft.settings().transcription.diarize, speakers: Speakers(min: 1, max: 8), model: draft.model.isEmpty ? nil : draft.model, vocabulary: draft.settings().transcription.vocabulary)
                _ = try await core.deps.transcriptionPolicy.refresh()
                if let issue = core.deps.transcriptionPolicy.issue(step: step, endpoint: nil) { message = .core(issue.code(arg: nil, detail: nil)); return }
                // Empty where no permission is required (every shell but the iPhone's).
                let missing = try await core.transferConsents.missing(targets: TransferTargets.shared.forStep(step: step).map { [$0] } ?? [])
                if !missing.isEmpty { consentNeeded = missing; return }
            }
            let result = try await core.processingSettings.save(settings: draft.settings(), expectedRevision: stored.document.revision)
            if result is ProcessingSaveResultSaved {
                await reload(); message = .key("Settings saved"); onSaved?()
            } else if let invalid = result as? ProcessingSaveResultInvalid {
                message = .key("Failed: %@", args: [.verbatim(invalid.errors.joined(separator: "\n"))])
            } else if result is ProcessingSaveResultStale { message = .core(CoreMessage.stale.code(arg: nil, detail: nil)) }
            else { message = .key("These settings cannot be read by this version. The original data has been preserved.") }
        } catch { failed(error) }
    }
    /// The answer to the question [save] asked. Allowed: exactly the destinations shown are granted,
    /// jobs waiting on them resume, and the save goes ahead. Declined: nothing is saved.
    public func answerConsent(allow: Bool) async {
        let shown = consentNeeded
        consentNeeded = []
        guard allow, !shown.isEmpty else { return }
        do {
            try await core.transferConsents.grant(targets: shown)
            try await core.resumeConsentedJobs()
        } catch { message = .key("Could not save transfer permissions"); return }
        await save()
    }
    public func saveKey(_ name: String, value: String) async -> Bool {
        guard name.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil, !value.isEmpty else {
            message = .key("Starts with a lowercase letter; lowercase, digits and underscores, up to 32"); return false
        }
        do { try await core.secrets.put(name: name, value: value); secretNames = try await core.secrets.names(); message = nil; return true }
        catch { failed(error); return false }
    }
    public func deleteKey(_ name: String) async {
        do { try await core.secrets.delete(name: name); secretNames = try await core.secrets.names() }
        catch { failed(error) }
    }
    /// The language on screen, which is the one whose model status this screen shows.
    public func prepare() {
        guard let draft, !busy else { return }
        message = nil
        download.start(language: ModelDownload.code(draft.language))
    }
    public func export() async -> String? {
        do { return try await core.processingSettings.exportJson() }
        catch { failed(error); return nil }
    }
    public func pick(_ url: URL) async {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= 1_048_576 else { message = .key("These settings cannot be read by this version. The original data has been preserved."); return }
            let json = try String(contentsOf: url, encoding: .utf8)
            guard let parsed = ProcessingSettingsParser.shared.parse(json: json) as? ProcessingParseResultValid else {
                message = .key("These settings cannot be read by this version. The original data has been preserved."); return
            }
            draft = ProcessingDraft.companion.from(settings: parsed.document.settings); importing = true; dirty = true; message = nil
        } catch { failed(error) }
    }
    public func refreshProviders() async {
        _ = try? await core.deps.transcriptionPolicy.refresh()
        providers = WorkflowParser.shared.STT_PROVIDERS.filter { core.deps.transcriptionPolicy.providerAvailable(provider: $0) }
    }
    /// docs/03 "Storage location": the storage was switched from the storage section, which saved a new
    /// revision under this form. The draft never carries the storage — saving keeps the stored one —
    /// so it stays as typed and is saved on top of the new revision.
    public func storageChanged() async {
        if let state = try? await core.initializeProcessing() { stored = state }
    }

    /// Export writes the stored document, so only offer it when that is exactly what the screen shows.
    public var canExport: Bool { stored != nil && !dirty }
    public var languages: [Language] { draft?.mode == .local ? localLanguages : draft?.languages ?? [] }
    public var languageSupported: Bool { draft?.mode == .off || draft.map { languages.contains($0.language) } == true }
    public func refreshLocal() async {
        localLanguages = await LocalSpeechEngine.supportedLanguages()
        guard let draft else { return }
        let language = draft.language
        do {
            let info = try await core.localEngineInfo(language: language.name.lowercased().replacingOccurrences(of: "_", with: "-"))
            if self.draft?.language == language { local = info }
        }
        catch { failed(error) }
    }
    private func updateSummary(_ draft: ProcessingDraft) {
        summaryKey = draft.mode == .local ? "On device" : "Off"
        providerSummary = draft.mode == .external ? SttProviders.shared.displayName(name: draft.provider) : nil
    }
    // The reason goes in the sentence, as on Android and Windows: `UiMessage.text` shows a core
    // code's sentence only, so a reason passed as its detail left "Failed:" with nothing after it.
    private func failed(_ error: Error) { message = .key("Failed: %@", args: [.verbatim(error.localizedDescription)]) }
}

public struct ProcessingSettingsView: View {
    @Environment(\.blueprint) private var blueprint
    @ObservedObject private var model: ProcessingSettingsModel
    @ObservedObject private var download: ModelDownload
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @State private var pickingLanguage = false
    @State private var pickingProvider = false
    @State private var deletingKey: String?
    @State private var trainingOff = false
    /// The settings file at the end of the block; a shell that shows it as a section of its own,
    /// further down ([ProcessingSettingsFileSection]), leaves it out here.
    private let settingsFile: Bool
    public init(model: ProcessingSettingsModel, settingsFile: Bool = true) {
        self.model = model
        self.download = model.download
        self.settingsFile = settingsFile
    }
    public var body: some View {
        SectionHeader(loc("Recording processing")).padding(.horizontal, Space.m)
        SectionBlock {
            if let draft = model.draft {
                if model.importing { SectionFootnote(loc("Review the folder and transcription method before saving. Keys are not included.")) }
                BlueprintField(loc("Storage folder"), text: field(\.folder))
                BlueprintField(loc("Minimum length (s)"), text: field(\.minimumSeconds), mono: true)
                SectionHeader(loc("Transcription"))
                ChoiceRow {
                    ForEach([TranscriptionMode.local, .external, .off].filter { $0 != .local || LocalSpeechEngine.available || draft.mode == .local }, id: \.self) { mode in
                        BlueprintChip(loc(mode == .local ? "On device" : mode == .external ? "External API" : "Off"), selected: draft.mode == mode, fill: true) { model.selectMode(mode) }
                    }
                }
                if draft.mode == .local {
                    // The model by name, as the phone and Windows show theirs; a product name, not translated.
                    if LocalSpeechEngine.available {
                        SectionRow(title: loc("Speech recognition model")) {
                            Text(verbatim: "Apple Speech")
                                .font(blueprint.fonts.bodySmall)
                                .foregroundStyle(blueprint.palette.textMuted)
                        }
                    }
                    if model.local?.status == .unsupported { SectionFootnote(CoreMessages.sentence(.localTranscriptionUnavailable)) }
                    if download.downloading {
                        HStack(spacing: Space.s) {
                            LoadingText(text: ModelDownload.progressText(download.progress), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                            Spacer(minLength: 0)
                            // Not "Cancel": the form's own Cancel can stand a few rows below it.
                            BlueprintButton(loc("Cancel download"), tone: .quiet) { download.cancel() }
                        }
                        .padding(.vertical, Space.s)
                    } else if model.local?.status == .modelRequired {
                        SectionFootnote(loc("To transcribe on this device, download this model once."))
                        BlueprintButton(loc("Download model")) { model.prepare() }
                            .disabled(model.busy || download.capturing)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    if let message = download.message { SectionFootnote(message.text) }
                    // docs/recly.md §15: the diarization models ship with the app, so the row is a name and nothing to do.
                    if model.local?.supportsDiarization == true {
                        SectionRow(title: loc("Speaker model")) {
                            Text(verbatim: SpeakerSeparation.modelName)
                                .font(blueprint.fonts.bodySmall)
                                .foregroundStyle(blueprint.palette.textMuted)
                        }
                        SectionFootnote(loc("Speakers are separated on this device."))
                    } else {
                        SectionFootnote(loc("On-device transcription does not separate speakers."))
                    }
                }
                if draft.mode == .external {
                    // docs/09 principle 4: a settings row, "Provider … ElevenLabs", like Language below.
                    SectionRow(title: loc("Provider")) {
                        #if os(macOS)
                        BlueprintDropdown(loc("Provider"), options: model.providers.map(ProviderOption.init),
                            selection: Binding(get: { ProviderOption(name: draft.provider) }, set: { option in
                                model.edit { $0.selectProvider(value: option.name) }
                            }), title: { SttProviders.shared.displayName(name: $0.name) })
                        #else
                        BlueprintButton(SttProviders.shared.displayName(name: draft.provider), tone: .quiet) { pickingProvider = true }
                        #endif
                    }
                    ProviderDisclosure(provider: draft.provider)
                    if SttProviders.shared.keyIsClientPair(name: draft.provider) { SectionFootnote(loc("Enter the key as client ID:client secret.")) }
                    ProcessingKeyField(model: model, name: draft.secretRef) { deletingKey = draft.secretRef }
                        .id(draft.secretRef)
                    if WorkflowParser.shared.invokeUrlUse(provider: draft.provider) != .none {
                        BlueprintField(loc("Invoke URL"), text: field(\.invokeUrl), mono: true, placeholder: draft.invokeUrlHint).processingURLEntry()
                    }
                    if draft.acceptsModel { BlueprintField(loc("Model (optional)"), text: field(\.model), mono: true) }
                }
                if draft.mode != .off {
                    SectionRow(title: loc("Spoken language")) {
                        #if os(macOS)
                        BlueprintDropdown(loc("Spoken language"), options: model.languages.map(SpeechLanguageOption.init),
                            selection: Binding(get: { SpeechLanguageOption(language: draft.language) }, set: { option in
                                model.edit { $0.language = option.language }
                                Task { await model.refreshLocal() }
                            }), title: { speechLanguageTitle($0.language) })
                        #else
                        BlueprintButton(speechLanguageTitle(draft.language), tone: .quiet) { pickingLanguage = true }
                        #endif
                    }
                    if !model.languageSupported { SectionFootnote(loc("This language is not supported by the selected transcription method.")) }
                    // docs/09 §11: after the language, part of the same draft (Cancel · Save).
                    VocabularyEditor(terms: field(\.vocabulary), description: vocabularyDescription(draft))
                }
                if let message = model.message { SectionFootnote(message.text) }
                // docs/09 screen principle 8: Cancel · Save only appear when there is something to save.
                if model.dirty {
                    FlowLayout(alignment: .trailing) {
                        BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) { Task { await model.reload() } }.disabled(model.busy)
                        BlueprintButton(loc("Save"), tone: .primary) { Task { await model.save() } }.disabled(model.busy || !model.languageSupported)
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
                // Keys only matter to an external provider, so the list lives with it — after the
                // provider's own settings and their Save, since deleting one is not part of them.
                // The current provider's key is managed on its own row above; this lists the rest.
                if draft.mode == .external {
                    let others = model.secretNames.filter { $0 != draft.secretRef }
                    if !others.isEmpty {
                        SectionHeader(loc("Other providers’ keys"))
                        ForEach(others, id: \.self) { name in
                            // docs/09: a delete that cannot be undone is red.
                            SectionRow(title: SttProviders.shared.displayName(name: name)) { BlueprintButton(loc("Delete"), tone: .danger, minWidth: minTouch) { deletingKey = name } }
                        }
                    }
                }
                if settingsFile {
                    SectionHeader(loc("Settings file"))
                    SettingsFileButtons(model: model)
                }
            } else if let message = model.message {
                SectionFootnote(message.text)
            }
        }
        .task { await model.refreshProviders(); await model.refreshLocal() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshLocal() } }
        }
        // Wherever the download was started from, the model row here says what it came to.
        .onChange(of: download.downloading) { _, running in
            if !running { Task { await model.refreshLocal() } }
        }
        .blueprintDialog(isPresented: Binding(get: { deletingKey != nil }, set: { if !$0 { deletingKey = nil } })) {
            BlueprintDialog(title: RecKitStrings.localized("Delete key: %@", SttProviders.shared.displayName(name: deletingKey ?? ""))) {
                BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) { deletingKey = nil }
                BlueprintButton(loc("Delete"), tone: .danger, minWidth: minTouch) { if let name = deletingKey { Task { await model.deleteKey(name) } }; deletingKey = nil }
            } content: { EmptyView() }
        }
        // docs/15 · App Review 5.1.2(i): what is sent, to whom, and the user's permission, before the
        // provider is saved — and so before anything could be sent to it.
        .blueprintDialog(isPresented: Binding(get: { !model.consentNeeded.isEmpty }, set: { if !$0 { trainingOff = false; Task { await model.answerConsent(allow: false) } } })) {
            BlueprintDialog(title: RecKitStrings.localized("Send recordings to %@?", SttProviders.shared.displayName(name: model.consentNeeded.first?.provider ?? ""))) {
                BlueprintButton(loc("Don't allow"), tone: .quiet) { trainingOff = false; Task { await model.answerConsent(allow: false) } }
                BlueprintButton(loc("Allow & save"), tone: .primary) { trainingOff = false; Task { await model.answerConsent(allow: true) } }
                    .disabled(TrainingOptOut.required(model.consentNeeded) && !trainingOff)
                    .accessibilityIdentifier("allow-and-save")
            } content: {
                TransferDisclosureList(targets: model.consentNeeded, trainingOff: $trainingOff)
            }
        }
        .blueprintDialog(isPresented: $pickingLanguage) {
            BlueprintDialog(title: loc("Spoken language")) {
                BlueprintButton(loc("Close"), tone: .quiet, minWidth: minTouch) { pickingLanguage = false }
            } content: {
                ForEach(model.languages, id: \.self) { language in
                    BlueprintRadioRow(speechLanguageTitle(language), selected: model.draft?.language == language) {
                        model.edit { $0.language = language }; pickingLanguage = false
                        Task { await model.refreshLocal() }
                    }
                }
            }
        }
        .blueprintDialog(isPresented: $pickingProvider) {
            BlueprintDialog(title: loc("Provider")) {
                BlueprintButton(loc("Close"), tone: .quiet, minWidth: minTouch) { pickingProvider = false }
            } content: {
                ForEach(model.providers, id: \.self) { name in
                    BlueprintRadioRow(SttProviders.shared.displayName(name: name), selected: model.draft?.provider == name) {
                        model.edit { $0.selectProvider(value: name) }; pickingProvider = false
                    }
                }
            }
        }
    }
    private func speechLanguageTitle(_ language: Language) -> String { SpeechLanguageName.title(language) }
    /// docs/09 §11: who reads the vocabulary — the provider, with the audio, or this device — or that nobody does.
    private func vocabularyDescription(_ draft: ProcessingDraft) -> String {
        if draft.mode == .local {
            return loc(model.local?.supportsVocabulary == true
                ? "Names and terms to spell correctly, used on this device."
                : "On-device transcription does not use a vocabulary.")
        }
        let name = SttProviders.shared.displayName(name: draft.provider)
        return SttProviders.shared.supportsVocabulary(name: draft.provider, model: draft.model.isEmpty ? nil : draft.model, language: draft.language)
            ? RecKitStrings.localized("Names and terms to spell correctly. They are sent with the audio to %@.", name)
            : RecKitStrings.localized("%@ does not use a vocabulary.", name)
    }
    private func field<Value>(_ path: ReferenceWritableKeyPath<ProcessingDraft, Value>) -> Binding<Value> {
        Binding(get: { model.draft![keyPath: path] }, set: { value in model.edit { $0[keyPath: path] = value } })
    }
    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// A spoken language as the settings name it — and the Transcribe again confirmation after them.
enum SpeechLanguageName {
    static func title(_ language: Language) -> String {
        if language == .auto { return RecKitStrings.localized("Automatic") }
        if language == .koEn { return RecKitStrings.localized("Korean and English") }
        let tag = TranscriptionLanguages.shared.localeTag(language: language)
        return Locale(identifier: tag).localizedString(forIdentifier: tag) ?? tag
    }
}

/// docs/09 screen principle 4: the settings file as a section of its own, for a shell that shows it
/// after the sections below Recording processing — the Mac, under Agent connection.
public struct ProcessingSettingsFileSection: View {
    @ObservedObject private var model: ProcessingSettingsModel
    public init(model: ProcessingSettingsModel) {
        self.model = model
    }
    public var body: some View {
        if model.draft != nil {
            SectionHeader(RecKitStrings.localized("Settings file")).padding(.horizontal, Space.m)
            SectionBlock { SettingsFileButtons(model: model) }
        }
    }
}

/// docs/05: the processing settings out to a file and back, with the panels that takes.
private struct SettingsFileButtons: View {
    @ObservedObject var model: ProcessingSettingsModel
    @State private var importer = false
    @State private var exporter = false
    @State private var file: ProcessingFile?
    var body: some View {
        FlowLayout(alignment: .trailing) {
            BlueprintButton(loc("Export settings"), tone: .quiet) { export() }.disabled(!model.canExport)
            BlueprintButton(loc("Import settings"), tone: .quiet) { importer = true }.disabled(model.dirty)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .fileImporter(isPresented: $importer, allowedContentTypes: [.json, .plainText]) { result in
            if case .success(let url) = result { Task { await model.pick(url) } }
        }
        .fileExporter(isPresented: $exporter, document: file, contentType: .json, defaultFilename: "recly-settings") { _ in }
    }
    private func export() { Task { if let json = await model.export() { file = ProcessingFile(json: json); exporter = true } } }
    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

private struct ProviderOption: Hashable, Identifiable {
    let name: String
    var id: String { name }
}

private struct SpeechLanguageOption: Hashable, Identifiable {
    let language: Language
    var id: String { language.name }
}

/// docs/05 "Secrets": the value is never read back. A saved key is a row that says so — the chip's
/// ✓ in the success colour, colour and text together (docs/09 "Every state is color + text") — with
/// Replace and Delete; the empty field only comes back to take a new value.
private struct ProcessingKeyField: View {
    @Environment(\.blueprint) private var blueprint
    @ObservedObject var model: ProcessingSettingsModel
    let name: String
    let delete: () -> Void
    @State private var value = ""
    @State private var replacing = false
    var body: some View {
        if !name.isEmpty && model.secretNames.contains(name) && !replacing {
            SectionRow(title: RecKitStrings.localized("API key")) {
                Text(verbatim: "\(BlueprintChip.selectionMark) \(RecKitStrings.localized("Saved on this device"))")
                    .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                    .foregroundStyle(blueprint.palette.success)
            }
            FlowLayout(alignment: .trailing) {
                BlueprintButton(RecKitStrings.localized("Replace key"), tone: .quiet) { replacing = true }
                BlueprintButton(RecKitStrings.localized("Delete"), tone: .danger, minWidth: minTouch, action: delete)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        } else {
            BlueprintField(RecKitStrings.localized("API key"), text: $value, secure: true)
            if !name.isEmpty && !replacing { SectionFootnote(RecKitStrings.localized("Not saved on this device")) }
            FlowLayout(alignment: .trailing) {
                if replacing {
                    BlueprintButton(RecKitStrings.localized("Cancel"), tone: .quiet, minWidth: minTouch) { value = ""; replacing = false }
                }
                BlueprintButton(RecKitStrings.localized("Save key"), tone: .quiet) {
                    Task { if await model.saveKey(name, value: value) { value = ""; replacing = false } }
                }.disabled(name.isEmpty || value.isEmpty)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}
private struct ProcessingFile: FileDocument {
    static let readableContentTypes = [UTType.json]
    let json: String
    init(json: String) { self.json = json }
    init(configuration: ReadConfiguration) throws { json = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self) }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(json.utf8)) }
}
private extension View {
    @ViewBuilder func processingURLEntry() -> some View {
        #if os(iOS)
        self.keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        self
        #endif
    }
}

extension ProcessingDraft {
    /// Local speech needs an explicit language; runtime support is queried from the OS.
    func selectAppleTranscriptionMode(_ value: TranscriptionMode, preferredLanguage: Language) {
        mode = value
        guard value == .local else { return }
        if language == .auto || language == .koEn { language = preferredLanguage }
    }
}
#endif
