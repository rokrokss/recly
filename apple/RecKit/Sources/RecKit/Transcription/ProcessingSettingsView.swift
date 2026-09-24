#if os(iOS) || os(macOS)
import Foundation
import ReclyCore
import SwiftUI
import UniformTypeIdentifiers

/// A device settings draft. Saving affects future captures; imports are editable previews.
@MainActor
public final class ProcessingSettingsModel: ObservableObject {
    @Published public private(set) var draft: ProcessingDraft?
    @Published public private(set) var dirty = false
    @Published public private(set) var busy = false
    @Published public private(set) var preparingModel = false
    @Published public private(set) var importing = false
    @Published public private(set) var message: UiMessage?
    @Published public private(set) var localLanguages: [Language] = []
    @Published public private(set) var local: LocalEngineInfo?
    @Published public private(set) var summaryKey = "On device"
    @Published public private(set) var providerSummary: String?
    @Published public private(set) var secretNames: [String] = []
    @Published public private(set) var providers: [String] = []
    private var stored: ProcessingSettingsStateReady?
    private let core: ReclyCore_
    private let canPrepare: () -> Bool
    public var onSaved: (() -> Void)?

    public init(core: ReclyCore_, canPrepare: @escaping () -> Bool = { true }) {
        self.core = core
        self.canPrepare = canPrepare
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
                let step = Step.Transcribe(id: "transcribe", onError: .abort, retry: Retry(maxAttempts: 5, initialDelaySec: 30, maxDelaySec: 3600), provider: draft.provider, secretRef: draft.secretRef, invokeUrl: draft.invokeUrl.isEmpty ? nil : draft.invokeUrl, language: draft.language, diarize: draft.settings().transcription.diarize, speakers: Speakers(min: 1, max: 8), model: draft.model.isEmpty ? nil : draft.model)
                _ = try await core.deps.transcriptionPolicy.refresh()
                if let issue = core.deps.transcriptionPolicy.issue(step: step, endpoint: nil) { message = .core(issue.code(arg: nil, detail: nil)); return }
            }
            let result = try await core.processingSettings.save(settings: draft.settings(), expectedRevision: stored.document.revision)
            if result is ProcessingSaveResultSaved {
                await reload(); message = .key("Settings saved"); onSaved?()
            } else if let invalid = result as? ProcessingSaveResultInvalid {
                message = .core(CoreMessage.stepFailed.code(arg: nil, detail: invalid.errors.joined(separator: "\n")))
            } else if result is ProcessingSaveResultStale { message = .core(CoreMessage.stale.code(arg: nil, detail: nil)) }
            else { message = .key("These settings cannot be read by this version. The original data has been preserved.") }
        } catch { failed(error) }
    }
    public func saveKey(_ name: String, value: String) async -> Bool {
        guard name.range(of: "^[a-z][a-z0-9_]{0,31}$", options: .regularExpression) != nil, !value.isEmpty else {
            message = .key("Starts with a lowercase letter; lowercase, digits and underscores, up to 32"); return false
        }
        do { try await core.secrets.put(name: name, value: value); secretNames = try await core.secrets.names(); message = .key("API key saved"); return true }
        catch { failed(error); return false }
    }
    public func deleteKey(_ name: String) async {
        do { try await core.secrets.delete(name: name); secretNames = try await core.secrets.names() }
        catch { failed(error) }
    }
    public func prepare() async {
        guard let draft, !busy, canPrepare() else { return }
        let language = draft.language
        busy = true; preparingModel = true; message = nil
        defer { busy = false; preparingModel = false }
        do {
            let prepared = try await core.prepareLocalEngine(language: language.name.lowercased().replacingOccurrences(of: "_", with: "-"))
            if self.draft?.language == language { local = prepared }
            onSaved?()
        }
        catch { failed(error) }
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
        providerSummary = draft.mode == .external ? draft.provider : nil
    }
    private func failed(_ error: Error) { message = .core(CoreMessage.stepFailed.code(arg: nil, detail: error.localizedDescription)) }
}

public struct ProcessingSettingsView: View {
    @ObservedObject private var model: ProcessingSettingsModel
    private let preparationAllowed: Bool
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @State private var pickingLanguage = false
    @State private var pickingProvider = false
    @State private var importer = false
    @State private var exporter = false
    @State private var file: ProcessingFile?
    @State private var deletingKey: String?
    public init(model: ProcessingSettingsModel, preparationAllowed: Bool = true) {
        self.model = model
        self.preparationAllowed = preparationAllowed
    }
    public var body: some View {
        SectionHeader(loc("Recording processing")).padding(.horizontal, Space.m)
        SectionBlock {
            if let draft = model.draft {
                if model.importing { SectionFootnote(loc("Review the folder and transcription method before saving. Keys are not included.")) }
                SectionFootnote(loc("Applies to new recordings. Watch recordings use these settings when received."))
                BlueprintField(loc("Storage folder"), text: field(\.folder))
                BlueprintField(loc("Minimum length (s)"), text: field(\.minimumSeconds), mono: true)
                SectionHeader(loc("Transcription"))
                FlowLayout {
                    ForEach([TranscriptionMode.local, .external, .off].filter { $0 != .local || LocalSpeechEngine.available || draft.mode == .local }, id: \.self) { mode in
                        BlueprintChip(loc(mode == .local ? "On device" : mode == .external ? "External API" : "Off"), selected: draft.mode == mode) { model.selectMode(mode) }
                    }
                }
                if draft.mode == .local {
                    if model.local?.status == .unsupported { SectionFootnote(CoreMessages.sentence(.localTranscriptionUnavailable)) }
                    if model.preparingModel {
                        ProgressView(loc("Preparing speech model…"))
                    } else if model.local?.status == .modelRequired {
                        SectionFootnote(loc("Download Apple’s speech model to transcribe on this device. Recordings waiting for this model will resume when it is ready."))
                        BlueprintButton(loc("Prepare model")) { Task { await model.prepare() } }
                            .disabled(model.busy || !preparationAllowed)
                    } else if model.local?.status == .ready {
                        SectionFootnote(loc("Ready for on-device transcription"))
                    }
                    SectionFootnote(loc("On-device transcription does not separate speakers."))
                }
                if draft.mode == .external {
                    BlueprintButton(draft.provider) { pickingProvider = true }
                    ProviderDisclosure()
                    SectionFootnote(loc("API keys stay on this device and are not included in exported settings."))
                    BlueprintField(loc("Secret name"), text: field(\.secretRef), mono: true)
                    ProcessingKeyField(model: model, name: draft.secretRef)
                    if WorkflowParser.shared.invokeUrlUse(provider: draft.provider) != .none {
                        BlueprintField(loc("Invoke URL"), text: field(\.invokeUrl), mono: true).processingURLEntry()
                    }
                    if draft.acceptsModel { BlueprintField(loc("Model (optional)"), text: field(\.model), mono: true) }
                }
                if draft.mode != .off {
                    SectionRow(title: loc("Language")) {
                        #if os(macOS)
                        BlueprintDropdown(loc("Language"), options: model.languages.map(SpeechLanguageOption.init),
                            selection: Binding(get: { SpeechLanguageOption(language: draft.language) }, set: { option in
                                model.edit { $0.language = option.language }
                                Task { await model.refreshLocal() }
                            }), title: { speechLanguageTitle($0.language) })
                        #else
                        BlueprintButton(speechLanguageTitle(draft.language), tone: .quiet) { pickingLanguage = true }
                        #endif
                    }
                    if !model.languageSupported { SectionFootnote(loc("This language is not supported by the selected transcription method.")) }
                }
                if !model.secretNames.isEmpty {
                    SectionHeader(loc("API keys"))
                    ForEach(model.secretNames, id: \.self) { name in
                        SectionRow(title: name) { BlueprintButton(loc("Delete"), tone: .quiet) { deletingKey = name } }
                    }
                }
                if let message = model.message { SectionFootnote(message.text) }
                FlowLayout {
                    BlueprintButton(loc("Cancel"), tone: .quiet) { Task { await model.reload() } }.disabled(model.busy || !model.dirty)
                    BlueprintButton(loc("Save"), tone: .primary) { Task { await model.save() } }.disabled(model.busy || !model.languageSupported || !model.dirty)
                }
                FlowLayout {
                    BlueprintButton(loc("Export settings"), tone: .quiet) { export() }.disabled(!model.canExport)
                    BlueprintButton(loc("Import settings"), tone: .quiet) { importer = true }.disabled(model.dirty)
                }
            } else if let message = model.message {
                SectionFootnote(message.text)
            }
        }
        .task { await model.refreshProviders(); await model.refreshLocal() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshLocal() } }
        }
        .blueprintDialog(isPresented: Binding(get: { deletingKey != nil }, set: { if !$0 { deletingKey = nil } })) {
            BlueprintDialog(title: RecKitStrings.localized("Delete key: %@", deletingKey ?? "")) {
                BlueprintButton(loc("Cancel"), tone: .quiet) { deletingKey = nil }
                BlueprintButton(loc("Delete")) { if let name = deletingKey { Task { await model.deleteKey(name) } }; deletingKey = nil }
            } content: { SectionFootnote(loc("API keys stay on this device and are not included in exported settings.")) }
        }
        .fileImporter(isPresented: $importer, allowedContentTypes: [.json, .plainText]) { result in
            if case .success(let url) = result { Task { await model.pick(url) } }
        }
        .fileExporter(isPresented: $exporter, document: file, contentType: .json, defaultFilename: "recly-settings") { _ in }
        .blueprintDialog(isPresented: $pickingLanguage) {
            BlueprintDialog(title: loc("Language")) {
                BlueprintButton(loc("Close"), tone: .quiet) { pickingLanguage = false }
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
                BlueprintButton(loc("Close"), tone: .quiet) { pickingProvider = false }
            } content: {
                ForEach(model.providers, id: \.self) { name in
                    BlueprintRadioRow(name, selected: model.draft?.provider == name) {
                        model.edit { $0.selectProvider(value: name) }; pickingProvider = false
                    }
                }
            }
        }
    }
    private func speechLanguageTitle(_ language: Language) -> String {
        if language == .auto { return loc("Automatic") }
        if language == .koEn { return loc("Korean and English") }
        let tag = TranscriptionLanguages.shared.localeTag(language: language)
        return Locale(identifier: tag).localizedString(forIdentifier: tag) ?? tag
    }
    private func field<Value>(_ path: ReferenceWritableKeyPath<ProcessingDraft, Value>) -> Binding<Value> {
        Binding(get: { model.draft![keyPath: path] }, set: { value in model.edit { $0[keyPath: path] = value } })
    }
    private func export() { Task { if let json = await model.export() { file = ProcessingFile(json: json); exporter = true } } }
    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

private struct SpeechLanguageOption: Hashable, Identifiable {
    let language: Language
    var id: String { language.name }
}

private struct ProcessingKeyField: View {
    @ObservedObject var model: ProcessingSettingsModel
    let name: String
    @State private var value = ""
    var body: some View {
        BlueprintField(RecKitStrings.localized("API key"), text: $value, secure: true)
        // docs/05 "시크릿": the value is never read back, so the field stays empty and this line says whether one is stored.
        if !name.isEmpty {
            SectionFootnote(RecKitStrings.localized(model.secretNames.contains(name) ? "Saved on this device" : "Not saved on this device"))
        }
        BlueprintButton(RecKitStrings.localized("Save key"), tone: .quiet) {
            Task { if await model.saveKey(name, value: value) { value = "" } }
        }.disabled(name.isEmpty || value.isEmpty)
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
