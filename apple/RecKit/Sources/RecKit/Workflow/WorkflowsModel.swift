#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
import UniformTypeIdentifiers
#endif
import Foundation
import os
import ReclyCore
import SwiftUI

/// A stamp for a workflow that is only being validated, never written (see `orderErrors`).
private let notSavedYet = "1970-01-01T00:00:00.000Z"

/// A row of the workflow list.
public struct WorkflowItem: Identifiable {
    public let id: String
    public let name: String
    /// ADR-016: whether *this device* falls back to it. It is a local pointer, not a field of the
    /// shared document, so the same row is marked on one device and not on another.
    public let isDeviceDefault: Bool
    /// The steps' labels as docs/07 keys; [steps] is the line the row shows.
    public let stepLabels: [String]

    /// "Drive → Webhook", in the app's language at the moment it is read.
    public var steps: String {
        stepLabels.map { RecKitStrings.localized($0) }.joined(separator: " → ")
    }
    /// `secretRef`s this device has no value for (docs/05 "new device").
    public let missingSecrets: [String]
}

/// The editor over one workflow.
public struct EditorState {
    public var edit: WorkflowEdit
    public let isNew: Bool
    /// Which opening of the editor this is, so an older save cannot close it.
    public let session: Int64
    /// The version this editor was opened on — nil for a workflow that is not stored yet.
    public let openedOn: OpenedOn?
    /// Something replaced the workflow while this editor was open — a second window, an import —
    /// so nothing can be saved on top of it.
    public var stale = false
    /// The parser's own sentences, from the last refused save.
    public var errors: [String] = []
    /// docs/08's order constraints by step id, as the parser's own tokens. Unlike [errors] these
    /// are live: moving a step breaks a workflow without any field being wrong, so the editor says
    /// so as soon as it is true rather than when a save is refused.
    public var order: [String: String] = [:]
}

/// The secret being entered. [generated] marks a value the user has not seen anywhere else.
public struct SecretForm {
    public var name = ""
    public var value = ""
    public var generated = false
    /// docs/07 rule 3: the complaint as a message, resolved by the form where it is drawn — the
    /// form outlives a language change, and a sentence made when the save was refused would not.
    public var error: UiMessage?
    /// The step that asked for it, so the form is shown where it was asked for and nowhere else.
    /// nil when it was opened from the secret list.
    public var stepId: String?

    public init() {}

    init(name: String, stepId: String? = nil) {
        self.name = name
        self.stepId = stepId
    }
}

/// docs/12 M7 · docs/13 I5 · docs/11 A6: the workflow editor, with the phone's rules — and shared
/// by both Apple shells, because "the same features on the phone and on macOS" is easier to keep
/// true than to re-check. The
/// editor never mutates the document it is shown — it edits a copy of one workflow and hands the
/// whole `WorkflowsDocument` to `core.workflows.save`, which is the only thing that validates
/// (docs/02) and the only thing that writes. The document is this device's own (docs/05): a save
/// that succeeds is the whole of what happened to it.
@MainActor
public final class WorkflowsModel: ObservableObject {
    @Published public private(set) var items: [WorkflowItem] = []
    @Published public private(set) var secrets: [String] = []
    @Published public var editor: EditorState?
    @Published public var secretForm: SecretForm?
    /// The row whose delete has been asked for and not yet answered. A workflow leaves this device
    /// and does not come back — the document is local (docs/05), so there is no copy anywhere to
    /// restore it from — and the row's button opens the question rather than doing the write.
    @Published public var confirmDelete: WorkflowItem?
    /// docs/07 rule 3: the banner as a key and its arguments, never as words — it stays on screen
    /// for as long as the condition holds, and a sentence made when it was set would outlive the
    /// language change it is meant to answer. `UiMessage.text` is the sentence, read by the view.
    @Published public var message: UiMessage?

    /// The document was re-read — after a save, an import, or a reopen. Anything outside this model
    /// that has an answer *about* the document, such as which workflow a source's default would
    /// run, is computed from a copy that has just moved; this is where it recomputes rather than
    /// keeping what it read at launch.
    public var onDocumentChanged: (() -> Void)?

    // The same, for the two banners that are a condition rather than an event.
    public static let staleNotice = UiMessage.key(
        "The document changed while this was open — reopen it"
    )

    public static let savedElsewhereNotice = UiMessage.key(
        "The workflow you were editing earlier was saved"
    )

    /// docs/08's order constraint: the parser's token is the verdict, this is its sentence — read
    /// where the editor draws it, so it follows a language change with the screen (docs/07 rule 3).
    public static var orderMessage: String {
        RecKitStrings.localized(
            "A Drive upload step has to come before this one — the results are written into the folder it makes."
        )
    }

    private let core: ReclyCore_
    private let documents: CoreWorkflowDocuments
    private let mutator: WorkflowMutator
    private let store: SecretStore
    private let sessions = EditorSessions()
    private var document: WorkflowsDocument?
    /// ADR-016: the id this device falls back to, as `observeDeviceDefault` last said. Not in the
    /// document, so it moves without one arriving — which is why it is watched rather than read.
    private var deviceDefault: String?
    private let logger = Logger(subsystem: CoreBridge.appName, category: "workflows")

    public init(core: ReclyCore_) {
        self.core = core
        self.store = SecretStore(secrets: core.secrets)
        let documents = CoreWorkflowDocuments(core: core)
        self.documents = documents
        self.mutator = WorkflowMutator(documents: documents)
        Task { await reload() }
        observeDeviceDefault()
        observeDocument()
    }

    /// The document moves without this model doing the moving — a settings import replaces it
    /// whole — and the list, an open editor's staleness check and the Mac's popover (through
    /// `onDocumentChanged`, which [reload] fires) all read it. So the model follows the store
    /// rather than trusting that every write went through itself.
    private func observeDocument() {
        Task { [weak self] in
            guard let core = self?.core else { return }
            for await _ in core.workflows.observe() {
                guard let self else { return }
                await self.reload()
            }
        }
    }

    /// The pointer decides which row wears the badge and which delete carries a warning, and it
    /// changes without the document moving — a pick made here, a pick made on the record screen, a
    /// delete that cleared it. SKIE hands the core's `Flow` over as an `AsyncSequence`.
    private func observeDeviceDefault() {
        Task { [weak self] in
            guard let core = self?.core else { return }
            for await id in core.workflows.observeDeviceDefault() {
                guard let self else { return }
                self.deviceDefault = id
                if let document = self.document {
                    self.show(document)
                }
            }
        }
    }

    // MARK: - List

    /// Re-reads the local copy. There is no `Flow` across the Obj-C bridge, so every write and
    /// every import ends here rather than the list redrawing itself.
    public func reload() async {
        await loadSecrets()
        do {
            let document = try await core.workflows.current()
            self.document = document
            show(document)
            onDocumentChanged?()
        } catch {
            message = .key("Could not read the workflows")
            logger.error("workflows.reload.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    public func add() {
        editor = EditorState(
            edit: WorkflowEdit(
                id: mintWorkflowId(now: core.deps.clock.now()),
                name: "",
                minDurationSec: "0",
                // docs/02 wants 1..10 steps, so a new workflow starts with the one everybody wants.
                steps: [.drive(StepEdit.DriveEdit(id: "upload"))]
            ),
            isNew: true,
            session: sessions.open(),
            openedOn: nil
        )
    }

    public func edit(_ id: String) {
        guard let workflow = document?.workflows.first(where: { $0.id == id }) else { return }
        editor = EditorState(
            edit: workflow.toEdit(),
            isNew: false,
            session: sessions.open(),
            openedOn: OpenedOn(id: workflow.id, updatedAt: workflow.updatedAt),
            order: Self.orderErrors(workflow.toEdit())
        )
    }

    public func cancel() {
        sessions.close()
        editor = nil
    }

    /// Discards the local edits and starts again from what the document says now.
    public func reopen() async {
        guard let id = editor?.edit.id else { return }
        await reload()
        // A reopen is a new editor over the stored version: a save still in flight from the old one
        // has no say in what happens to it.
        sessions.close()
        guard document?.workflows.contains(where: { $0.id == id }) == true else {
            editor = nil
            message = .key("This workflow is no longer in the document")
            return
        }
        edit(id)
    }

    /// ADR-016: the row's one control. It writes nothing to the document — the pointer is local, and
    /// which workflow this device falls back to is its own answer.
    public func setDeviceDefault(_ item: WorkflowItem) async {
        do {
            try await core.workflows.setDeviceDefault(workflowId: item.id)
        } catch {
            message = .key("Could not save")
            logger.error(
                "workflows.deviceDefault.failed error=\(String(describing: error), privacy: .private)"
            )
        }
    }

    /// ADR-016: any workflow may be deleted, this device's default among them — the confirmation
    /// says what that costs before it happens, and the core clears the pointer with it so the
    /// screens ask for a new pick.
    public func delete(_ item: WorkflowItem) async {
        confirmDelete = nil
        await mutate { $0.without(item.id) }
    }

    /// The one place [items] is rebuilt, so an open delete confirmation is the row as it is *now*:
    /// ADR-016 lets the default pointer move while the question is on screen, and that pointer is
    /// exactly what the question's warning is about.
    private func show(_ document: WorkflowsDocument) {
        items = document.workflows.map { item(for: $0) }
        if let open = confirmDelete {
            // A question about a row that is gone is over — and it must not lie in wait: an import
            // can bring the same fixed id back, and a stale confirmation re-arming against the new
            // workflow would be a delete nobody asked of *it*.
            confirmDelete = items.first { $0.id == open.id }
        }
    }

    // MARK: - Editor

    /// Every field edit clears the errors: they are the verdict `save()` passed on a document that
    /// no longer exists the moment anything changes.
    public func update(_ block: (inout WorkflowEdit) -> Void) {
        guard var editor else { return }
        block(&editor.edit)
        editor.errors = []
        editor.order = Self.orderErrors(editor.edit)
        self.editor = editor
    }

    /// The parser's docs/08 order verdict, by the step that has to move.
    static func orderErrors(_ edit: WorkflowEdit) -> [String: String] {
        var order: [String: String] = [:]
        for error in WorkflowParser.shared.orderErrors(workflow: edit.toWorkflow(updatedAt: notSavedYet)) {
            guard let range = error.range(of: "step '"),
                  let end = error[range.upperBound...].firstIndex(of: "'")
            else { continue }
            let stepId = String(error[range.upperBound ..< end])
            order[stepId] = WorkflowParser.shared.TRANSCRIBE_NEEDS_UPLOAD
        }
        return order
    }

    public func addStep(_ kind: StepKind) {
        update { edit in
            guard kind.canAdd(to: edit.steps) else { return }
            let taken = Set(edit.steps.map(\.id))
            edit.steps.append(kind.newStep(taken: taken))
        }
    }

    public func removeStep(at index: Int) {
        update { $0.steps.remove(at: index) }
    }

    public func moveStep(from: IndexSet, to: Int) {
        update { $0.steps.move(fromOffsets: from, toOffset: to) }
    }

    public func updateStep(at index: Int, _ block: (inout StepEdit) -> Void) {
        update { edit in
            guard edit.steps.indices.contains(index) else { return }
            block(&edit.steps[index])
        }
    }

    /// The only write path out of the editor.
    public func save() async {
        guard let editor else { return }
        let now = core.deps.clock.now()
        let edit = editor.edit
        await mutate(expect: editor.openedOn, session: editor.session) { $0.with(edit, now: now) }
    }

    // MARK: - Secrets

    public func openSecrets(prefill: String? = nil, step: String? = nil) {
        secretForm = SecretForm(name: prefill ?? "", stepId: step)
    }

    public func closeSecrets() {
        secretForm = nil
    }

    /// docs/04: the `whsec_` value is shown once, here, and is never readable again afterwards —
    /// so it goes to the clipboard as well, marked concealed so clipboard managers skip it.
    public func generateSecret() {
        let secret = store.generate()
        secretForm?.value = secret
        secretForm?.generated = true
        secretForm?.error = nil
        copyGeneratedSecret()
    }

    /// The only way the generated value reaches the pasteboard. The field it is shown in is not
    /// selectable, because a ⌘C over selected text would put the same signing key on the
    /// pasteboard *without* the concealed marker and clipboard managers would keep it.
    ///
    /// Nothing at all on watchOS: there is no editor there and no pasteboard to put it on.
    public func copyGeneratedSecret() {
        guard let secret = secretForm?.value, secretForm?.generated == true else { return }
        #if os(macOS)
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString("", forType: .init("org.nspasteboard.ConcealedType"))
        pasteboard.setString(secret, forType: .string)
        #elseif os(iOS)
        // The phone's two halves of the same promise: `localOnly` keeps the signing key off the
        // user's other devices through Universal Clipboard, and the expiry keeps it from sitting
        // in the pasteboard for the rest of the day.
        //
        // The pasteboard at all, rather than a value shown only inside the app, is docs/04's own
        // choice — one display, one copy, never readable again — and it is what the Android and
        // macOS editors do too. Those two mitigations are what make it acceptable here, alongside
        // iOS 16+ putting every cross-app pasteboard read behind the user's own paste permission.
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: secret]],
            options: [
                .localOnly: true,
                .expirationDate: Date().addingTimeInterval(Self.secretClipboardSec),
            ]
        )
        #endif
    }

    #if os(iOS)
    /// Long enough to paste it into the service that asked for it, short enough that walking away
    /// is not how it leaks.
    private static let secretClipboardSec: TimeInterval = 120
    #endif

    /// The name that was stored, for a caller that has somewhere to put it — the step editor
    /// assigns it to the `secretRef` it was opened from. nil when nothing was stored.
    @discardableResult
    public func saveSecret() async -> String? {
        guard let form = secretForm else { return nil }
        let name = form.name.trimmingCharacters(in: .whitespaces)
        let problem = SecretName.problem(name, existing: secrets)
            ?? (form.value.isEmpty
                ? UiMessage.key("Enter a value, or generate a webhook secret")
                : nil)
        if let problem {
            secretForm?.error = problem
            return nil
        }
        do {
            try await store.put(name: name, value: form.value)
            secretForm = SecretForm()
            await reload()
            return name
        } catch {
            // The Keychain itself refused the write — locked, an ACL, a missing entitlement. The
            // form stays up saying so: nothing was stored, and the `OSStatus` in the sentence is
            // the only thing that says which of those it was.
            secretForm?.error = .key("Could not save the key: %@", args: [.verbatim(error.localizedDescription)])
            logger.error("secrets.put.failed error=\(String(describing: error), privacy: .private)")
            return nil
        }
    }

    public func deleteSecret(_ name: String) async {
        do {
            try await store.delete(name: name)
        } catch {
            message = .key("Could not delete the key: %@", args: [.verbatim(error.localizedDescription)])
            logger.error("secrets.delete.failed error=\(String(describing: error), privacy: .private)")
        }
        await reload()
    }

    /// The names the Keychain holds, for the "no key on this device" badge (docs/05 "새 기기").
    ///
    /// A Keychain that will not be *read* is said out loud rather than answered with an empty list:
    /// every step naming a key would wear the missing-key badge, and the editor would offer to
    /// store a value the device already has. The list still empties, because that is what is known.
    private func loadSecrets() async {
        do {
            secrets = try await store.names()
        } catch {
            secrets = []
            message = .key("Could not read the keys: %@", args: [.verbatim(error.localizedDescription)])
            logger.error("secrets.names.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    // MARK: - Plumbing

    private func mutate(
        expect: OpenedOn? = nil,
        session: Int64? = nil,
        _ block: @MainActor @escaping (WorkflowsDocument) -> WorkflowsDocument?
    ) async {
        do {
            let result = try await mutator.mutate(expect: expect, block: DocumentMutation(block))
            await apply(result, session: session)
        } catch {
            message = .key("Could not save")
            logger.error("workflows.save.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// What a finished mutation does to the screen. Everything that only the editor cares about —
    /// closing it, marking it stale, hanging the parser's complaints on it — happens only when the
    /// editor on screen is still the one the mutation was started from; a result that belongs to an
    /// editor the user has already left says its piece in [message] and leaves the screen alone.
    private func apply(_ result: any MutationResult, session: Int64?) async {
        let mine = sessions.isCurrent(session: session.map(KotlinLong.init(longLong:)))
        switch onEnum(of: result) {
        case .saved(let saved):
            self.document = saved.document
            if mine {
                sessions.close()
                editor = nil
            } else if session != nil {
                message = Self.savedElsewhereNotice
            }
            await reload()

        case .invalid(let invalid):
            if mine {
                editor?.errors = invalid.errors
            } else {
                message = .verbatim(invalid.errors.joined(separator: "\n"))
            }

        // The editor stays open with what the user typed: the only choices v1 offers are reopening
        // (losing it) and cancelling, and both are theirs to make.
        case .stale:
            if mine { editor?.stale = true } else { message = Self.staleNotice }

        case .skipped:
            break
        }
    }

    private func item(for workflow: Workflow) -> WorkflowItem {
        WorkflowItem(
            id: workflow.id,
            name: workflow.name,
            isDeviceDefault: workflow.id == deviceDefault,
            stepLabels: workflow.steps.map(\.label),
            // docs/05 "new device": a key is a key, whichever kind of step named it.
            missingSecrets: workflow.steps
                .compactMap(\.usedSecretRef)
                .filter { !secrets.contains($0) }
        )
    }
}
