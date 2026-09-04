#if os(macOS)
import AppKit
#endif
#if os(iOS) || os(macOS)
import Foundation
import os
import ReclyCore
import SwiftUI
import UniformTypeIdentifiers

/// A file the user picked and has not agreed to yet: [workflows] is the number the confirmation
/// names, and [json] is what replaces the document once they have.
public struct PickedWorkflows: Identifiable, Equatable, Sendable {
    public let json: String
    public let workflows: Int

    public var id: String { json }
}

/// docs/05 "워크플로우 내보내기 · 가져오기" as the two settings screens drive it — the phone's
/// `SettingsView` and the Mac's `SettingsPane` hold one of these and nothing else about the file.
///
/// Definitions are this device's own now, so a file is the only way one device's workflows reach
/// another. The core decides what is *in* the file (`exportJson`: the document as it is stored, no
/// pointer and no secret values) and what an import does with it (`importJson`: it replaces the
/// whole document, there is no merge); everything here is where the bytes go and what is said about
/// them afterwards.
@MainActor
public final class WorkflowTransferModel: ObservableObject {
    /// The picked file, while the "this replaces everything" confirmation is up.
    @Published public private(set) var confirm: PickedWorkflows?
    /// docs/07 rule 3: what the last export or import had to say, as a key and its arguments —
    /// the line stands under the section for as long as the user leaves it there, and a sentence
    /// made when it was set would outlive a language change made while it stands.
    @Published public private(set) var message: UiMessage?
    /// Whether [message] is a complaint — the danger colour and nothing else depends on it.
    @Published public private(set) var failed = false
    @Published public private(set) var exporting: ProcessingState = .idle
    @Published public private(set) var importing: ProcessingState = .idle

    /// docs/05 "워크플로우 내보내기": the name every shell offers, so a file written on one device is
    /// recognisable on the next. The stem as well as the whole name, because SwiftUI's exporter
    /// puts the content type's extension on itself and a save panel is given the finished name.
    public static let fileStem = "recly-workflows"

    public static let fileName = fileStem + ".json"

    private let core: ReclyCore_
    private let logger = Logger(subsystem: CoreBridge.appName, category: "workflows")

    public init(core: ReclyCore_) {
        self.core = core
    }

    // MARK: - Export

    /// The document as it is stored, or nil when the core would not give it up — which is said
    /// here rather than left to a picker that would go on to write an empty file.
    public func exportJson() async -> String? {
        exporting = .processing
        do {
            return try await core.workflows.exportJson()
        } catch {
            fileFailed(error)
            return nil
        }
    }

    /// The whole export, for a shell that chose the destination itself (the Mac's save panel).
    public func export(to url: URL) async {
        guard let json = await exportJson() else { return }
        do {
            try Data(json.utf8).write(to: url, options: .atomic)
            exported()
        } catch {
            fileFailed(error)
        }
    }

    public func exported() {
        message = .key("Exported.")
        failed = false
        exporting = .done
    }

    // MARK: - Import

    /// The picked file, read and parsed for the one number the confirmation has to name. A file
    /// that does not parse never gets a confirmation: `importJson` refuses it without writing
    /// anything, and it is the one place the parser's complaints are turned into the list the
    /// editor shows.
    public func pick(_ url: URL) async {
        importing = .processing
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let json: String
        do {
            json = try String(contentsOf: url, encoding: .utf8)
        } catch {
            fileFailed(error)
            return
        }
        if let parsed = WorkflowParser.shared.parse(json: json) as? ParseResultOk {
            confirm = PickedWorkflows(json: json, workflows: parsed.document.workflows.count)
            message = nil
            failed = false
            importing = .idle
        } else {
            await replace(json)
        }
    }

    public func cancelImport() {
        confirm = nil
        importing = .idle
    }

    /// docs/05: the confirmed replace. There is no merge — the file becomes the whole document.
    public func confirmImport() async {
        guard let picked = confirm else { return }
        importing = .processing
        await replace(picked.json)
    }

    /// What the core made of the file, either from the confirmation or straight off a file that
    /// never parsed. docs/02 owns the complaints, so they are shown as they stand.
    private func replace(_ json: String) async {
        confirm = nil
        do {
            switch onEnum(of: try await core.workflows.importJson(json: json)) {
            case .imported(let imported):
                message = .key(
                    "Imported — %@ workflow(s) on this device now.",
                    args: [.verbatim("\(imported.workflows)")]
                )
                failed = false
                importing = .done
            case .invalid(let invalid):
                message = .verbatim(invalid.errors.joined(separator: "\n"))
                failed = true
                importing = .failed
            }
        } catch {
            fileFailed(error)
        }
    }

    // MARK: - Both

    /// Nothing was picked, so nothing is said — a cancelled picker is not a failure.
    public func cancelled() {
        exporting = .idle
        importing = .idle
    }

    /// The file the user chose could not be read or written — the shell's own complaint, never the
    /// core's, so the system's own reason is what stands inside it.
    public func fileFailed(_ error: Error) {
        message = .key("Could not open the file: %@", args: [.verbatim(error.localizedDescription)])
        failed = true
        exporting = .failed
        importing = .failed
        logger.error("workflows.file.failed error=\(String(describing: error), privacy: .private)")
    }
}

/// docs/05 "워크플로우 내보내기 · 가져오기": the two controls, and above them the one thing about the
/// file a user has to know before they carry it anywhere — the keys are not in it.
///
/// The sentences are RecKit's own (`RecKitStrings`) rather than either app's, because the section is
/// one piece of screen and there is no reason for two catalogs to carry it — the same argument the
/// language block is drawn once for.
///
/// What each platform asks with is its own: the phone gets SwiftUI's document pickers, and the Mac
/// the panels a `LSUIElement` app can put in front of its popover. The Mac's confirmation is drawn
/// where every one of its dialogs is drawn, in the popover itself (`MenuPopover`), because it has no
/// window to hang a sheet off.
public struct WorkflowTransferSection: View {
    @ObservedObject private var model: WorkflowTransferModel
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: the rows below are resolved outside SwiftUI, and reading the locale is what
    /// declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale
    #if os(iOS)
    /// The document the exporter is about to write, which is also what presents it.
    @State private var file: WorkflowsFile?
    @State private var importing = false
    #endif

    public init(model: WorkflowTransferModel) {
        self.model = model
    }

    public var body: some View {
        content
        #if os(iOS)
            .fileExporter(
                isPresented: Binding(get: { file != nil }, set: { if !$0 { file = nil } }),
                document: file,
                contentType: .json,
                defaultFilename: WorkflowTransferModel.fileStem
            ) { result in
                file = nil
                finish(result) { _ in model.exported() }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: Self.importable) { result in
                finish(result) { url in Task { await model.pick(url) } }
            }
            .blueprintDialog(
                item: Binding(get: { model.confirm }, set: { if $0 == nil { model.cancelImport() } })
            ) { picked in
                ImportDialog(
                    picked: picked,
                    confirm: { Task { await model.confirmImport() } },
                    cancel: { model.cancelImport() }
                )
            }
        #endif
    }

    private var content: some View {
        VStack(spacing: 0) {
            SectionHeader(loc("Workflows")).padding(.horizontal, Space.m)
            SectionBlock {
                Text(verbatim: loc("Provider keys are not in this file — enter them on each device."))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: Space.s) {
                    ProcessingButton(loc("Export workflows"), state: model.exporting) { export() }
                        .accessibilityIdentifier("export-workflows")
                    ProcessingButton(loc("Import workflows"), state: model.importing) { startImport() }
                        .accessibilityIdentifier("import-workflows")
                }
            }
            if let message = model.message {
                // docs/09 화면 원칙 5: what happened is said where it happened, under the section.
                Text(verbatim: message.text)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(model.failed ? blueprint.palette.danger : blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.vertical, Space.s)
            }
        }
    }

    private func export() {
        #if os(macOS)
        let save = NSSavePanel()
        save.nameFieldStringValue = WorkflowTransferModel.fileName
        save.allowedContentTypes = [.json]
        guard let url = run(save) else { return model.cancelled() }
        Task { await model.export(to: url) }
        #else
        Task { file = await model.exportJson().map(WorkflowsFile.init) }
        #endif
    }

    private func startImport() {
        #if os(macOS)
        let open = NSOpenPanel()
        open.allowsMultipleSelection = false
        open.allowedContentTypes = Self.importable
        guard let url = run(open) else { return model.cancelled() }
        Task { await model.pick(url) }
        #else
        importing = true
        #endif
    }

    /// docs/05: JSON alone hides the very file this app writes on a device whose provider typed it
    /// as text or as a plain byte stream — a file another shell's share sheet handed over, say. All
    /// three are let through and the parser is what refuses.
    private static let importable: [UTType] = [.json, .plainText, .data]

    #if os(macOS)
    /// docs/12: a `LSUIElement` app is not frontmost when its menu closes, so a panel opened
    /// without this appears behind whatever the user was doing (`BlueprintPanel` activates for the
    /// same reason).
    private func run(_ panel: NSSavePanel) -> URL? {
        NSApp.activate(ignoringOtherApps: true)
        return panel.runModal() == .OK ? panel.url : nil
    }
    #endif

    #if os(iOS)
    /// A picker the user backed out of is not a failure and says nothing; anything else the system
    /// refused is the shell's own complaint.
    private func finish(_ result: Result<URL, Error>, _ chosen: (URL) -> Void) {
        switch result {
        case .success(let url): chosen(url)
        case .failure(let error):
            if (error as? CocoaError)?.code == .userCancelled {
                model.cancelled()
            } else {
                model.fileFailed(error)
            }
        }
    }
    #endif

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// docs/05 "워크플로우 가져오기": there is no merge, so the one question worth asking is asked before
/// anything is written — and it is asked with the number the file actually holds.
public struct ImportDialog: View {
    private let picked: PickedWorkflows
    private let confirm: () -> Void
    private let cancel: () -> Void
    @Environment(\.locale) private var locale

    public init(picked: PickedWorkflows, confirm: @escaping () -> Void, cancel: @escaping () -> Void) {
        self.picked = picked
        self.confirm = confirm
        self.cancel = cancel
    }

    public var body: some View {
        BlueprintDialog(title: loc("Replace the workflows on this device?")) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { cancel() }
            BlueprintButton(loc("Import workflows"), tone: .danger) { confirm() }
                .accessibilityIdentifier("import-confirm")
        } content: {
            BlueprintDialogText(
                RecKitStrings.localized(
                    "The %@ workflow(s) in the file replace every workflow on this device.",
                    "\(picked.workflows)"
                ),
                tone: .danger
            )
            BlueprintDialogText(
                loc("Provider keys are not in this file — enter them on each device."),
                tone: .muted
            )
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

#if os(iOS)
/// The exported document as SwiftUI's `fileExporter` wants it: the bytes the core serialized, and
/// the one type they are. Nothing reads through it — an import is `WorkflowTransferModel.pick`,
/// which has the parser's verdict to report and a confirmation to put it behind.
struct WorkflowsFile: FileDocument {
    static let readableContentTypes = [UTType.json]

    let json: String

    init(_ json: String) {
        self.json = json
    }

    init(configuration: ReadConfiguration) throws {
        json = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(json.utf8))
    }
}
#endif
#endif
