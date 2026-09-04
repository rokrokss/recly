import Foundation
import ReclyCore

/// One workflow as the editor holds it (the phone's `WorkflowEdit`, docs/11 A6): the docs/02 shape
/// with every number kept as the text the user typed, so a half-written field is not silently
/// rounded into something valid. The mapping back turns unparseable text into a value the parser is
/// guaranteed to reject ([invalidNumber]) — the editor's only judge of what is valid is `save()`,
/// which is the parser (docs/02 "validation rules").
public struct WorkflowEdit: Equatable {
    public var id: String
    public var name: String
    public var minDurationSec: String
    public var steps: [StepEdit]

    public init(
        id: String,
        name: String,
        minDurationSec: String,
        steps: [StepEdit]
    ) {
        self.id = id
        self.name = name
        self.minDurationSec = minDurationSec
        self.steps = steps
    }

    /// `>= 30s`, or nothing at all for the docs/02 default of 0 — a code, not a sentence, because
    /// the graph node's detail line is monospace and a number with a unit is the same in either
    /// language. ADR-016 left it the only thing the head of the graph still has to say.
    public var minimumCode: String {
        let trimmed = minDurationSec.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, trimmed != "0" else { return "" }
        return ">= \(trimmed)s"
    }
}

public struct RetryEdit: Equatable {
    public var maxAttempts: String
    public var initialDelaySec: String
    public var maxDelaySec: String

    public init(
        maxAttempts: String = String(StepDefaults.retry.maxAttempts),
        initialDelaySec: String = String(StepDefaults.retry.initialDelaySec),
        maxDelaySec: String = String(StepDefaults.retry.maxDelaySec)
    ) {
        self.maxAttempts = maxAttempts
        self.initialDelaySec = initialDelaySec
        self.maxDelaySec = maxDelaySec
    }
}

/// docs/02's defaults for a step the editor is about to create. Kotlin's default arguments do not
/// cross the Obj-C boundary — `Step.DriveUpload(id:)` is not a call Swift can make — so they are
/// restated here, in the one place in the shell that needs them.
public enum StepDefaults {
    public static let retry = Retry(maxAttempts: 8, initialDelaySec: 30, maxDelaySec: 3600)
    public static let folder = "recly/{{yyyy}}/{{yyyy}}-{{MM}}"
    public static let includeMeta = true
    public static let speakers = Speakers(min: 1, max: 10)

    /// What a step the user has just added starts as — the provider table's first row (docs/08),
    /// asynchronous, which docs/08 recommends on a phone.
    public static let sttProvider = "assemblyai"
}

/// What "단계 추가" offers — one per docs/02·docs/08 step type.
public enum StepKind: CaseIterable {
    case drive
    case hook
    case transcribe

    /// What the button that adds one says, read where it is drawn — the same words the node it
    /// creates will carry ([StepEdit.label]).
    public var label: String {
        switch self {
        case .drive: return RecKitStrings.localized("Drive upload")
        case .hook: return RecKitStrings.localized("Webhook")
        case .transcribe: return RecKitStrings.localized("Transcribe")
        }
    }

    /// Whether the editor offers this kind on top of [steps]. A second `drive.upload` has nothing to
    /// do: the same folder is a no-op, a different one a copy the later steps never see (only the
    /// last upload's folder gets the transcript and the webhook payload). Webhooks may repeat —
    /// each is another endpoint.
    public func canAdd(to steps: [StepEdit]) -> Bool {
        switch self {
        case .drive:
            return !steps.contains { if case .drive = $0 { return true } else { return false } }
        case .hook, .transcribe:
            return true
        }
    }

    /// A step the user has just asked for, with the defaults docs/08 says to start from.
    public func newStep(taken: Set<String>) -> StepEdit {
        switch self {
        case .drive:
            return .drive(StepEdit.DriveEdit(id: nextStepId(base: "upload", taken: taken)))
        case .hook:
            return .hook(StepEdit.HookEdit(id: nextStepId(base: "hook", taken: taken)))
        case .transcribe:
            return .transcribe(StepEdit.TranscribeEdit(id: nextStepId(base: "stt", taken: taken)))
        }
    }
}

public enum StepEdit: Equatable, Identifiable {
    case drive(DriveEdit)
    case hook(HookEdit)
    case transcribe(TranscribeEdit)

    public struct DriveEdit: Equatable {
        public var id: String
        public var onError: OnError = .abort
        public var retry = RetryEdit()
        public var folder: String = StepDefaults.folder
        public var includeMeta: Bool = StepDefaults.includeMeta

        public init(id: String) {
            self.id = id
        }
    }

    public struct HookEdit: Equatable {
        public var id: String
        public var onError: OnError = .abort
        public var retry = RetryEdit()
        public var url: String = ""
        public var secretRef: String?

        public init(id: String) {
            self.id = id
        }
    }

    /// `transcribe` (docs/08). `invokeUrl` and `model` are empty rather than nil while being
    /// edited, and become nil again on the way out — a field the user cleared is a field the
    /// document does not carry.
    ///
    /// `model` has no form of its own (docs/08 leaves the STT model to the provider's default), but
    /// a definition written elsewhere may set one, so it is carried through rather than dropped.
    public struct TranscribeEdit: Equatable {
        public var id: String
        public var onError: OnError = .abort
        public var retry = RetryEdit()
        public var provider: String = StepDefaults.sttProvider
        public var secretRef: String = ""
        public var invokeUrl: String = ""
        public var language: Language = .ko
        public var diarize: Bool = true
        public var speakersMin: String = String(StepDefaults.speakers.min)
        public var speakersMax: String = String(StepDefaults.speakers.max)
        public var model: String = ""

        public init(id: String) {
            self.id = id
        }
    }

    /// docs/02 identity: the job's `step_run` rows are keyed by it, so it never changes once minted.
    public var id: String {
        switch self {
        case .drive(let step): return step.id
        case .hook(let step): return step.id
        case .transcribe(let step): return step.id
        }
    }

    /// The two fields every step has (docs/02), so the editor can bind to them without knowing
    /// which kind of step it is looking at.
    public var onError: OnError {
        get {
            switch self {
            case .drive(let step): return step.onError
            case .hook(let step): return step.onError
            case .transcribe(let step): return step.onError
            }
        }
        set {
            switch self {
            case .drive(var step): step.onError = newValue; self = .drive(step)
            case .hook(var step): step.onError = newValue; self = .hook(step)
            case .transcribe(var step): step.onError = newValue; self = .transcribe(step)
            }
        }
    }

    public var retry: RetryEdit {
        get {
            switch self {
            case .drive(let step): return step.retry
            case .hook(let step): return step.retry
            case .transcribe(let step): return step.retry
            }
        }
        set {
            switch self {
            case .drive(var step): step.retry = newValue; self = .drive(step)
            case .hook(var step): step.retry = newValue; self = .hook(step)
            case .transcribe(var step): step.retry = newValue; self = .transcribe(step)
            }
        }
    }

    public var label: String {
        switch self {
        case .drive: return RecKitStrings.localized("Drive upload")
        case .hook: return RecKitStrings.localized("Webhook")
        case .transcribe: return RecKitStrings.localized("Transcribe")
        }
    }
}

/// Not a number the schema can hold, so "", "3 " and "abc" all come back as one validation error.
private let invalidNumber: Int32 = -1

private func asInt(_ text: String, blank: Int32) -> Int32 {
    let trimmed = text.trimmingCharacters(in: .whitespaces)
    if trimmed.isEmpty { return blank }
    return Int32(trimmed) ?? invalidNumber
}

public extension Workflow {
    func toEdit() -> WorkflowEdit {
        WorkflowEdit(
            id: id,
            name: name,
            minDurationSec: String(minDurationSec),
            steps: steps.map { $0.toEdit() }
        )
    }
}

public extension Step {
    func toEdit() -> StepEdit {
        switch onEnum(of: self) {
        case .driveUpload(let step):
            var edit = StepEdit.DriveEdit(id: step.id)
            edit.onError = step.onError
            edit.retry = step.retry.toEdit()
            edit.folder = step.folder
            edit.includeMeta = step.includeMeta
            return .drive(edit)

        case .webhook(let step):
            var edit = StepEdit.HookEdit(id: step.id)
            edit.onError = step.onError
            edit.retry = step.retry.toEdit()
            edit.url = step.url
            edit.secretRef = step.secretRef
            return .hook(edit)

        case .transcribe(let step):
            var edit = StepEdit.TranscribeEdit(id: step.id)
            edit.onError = step.onError
            edit.retry = step.retry.toEdit()
            edit.provider = step.provider
            edit.secretRef = step.secretRef
            edit.invokeUrl = step.invokeUrl ?? ""
            edit.language = step.language
            edit.diarize = step.diarize
            edit.speakersMin = String(step.speakers.min)
            edit.speakersMax = String(step.speakers.max)
            edit.model = step.model ?? ""
            return .transcribe(edit)
        }
    }

    /// A docs/07 key for a list row, which `WorkflowItem.steps` resolves as it draws it.
    var label: String {
        switch onEnum(of: self) {
        case .driveUpload: return "Drive"
        case .webhook: return "Webhook"
        case .transcribe: return "Transcribe"
        }
    }

    /// The `secretRef` this step needs, whichever kind of step it is (docs/05 "새 기기"). Named
    /// apart from the subclasses' own `secretRef`, which it would otherwise make ambiguous.
    var usedSecretRef: String? {
        switch onEnum(of: self) {
        case .driveUpload: return nil
        case .webhook(let step): return step.secretRef
        case .transcribe(let step): return step.secretRef
        }
    }
}

public extension Retry {
    func toEdit() -> RetryEdit {
        RetryEdit(
            maxAttempts: String(maxAttempts),
            initialDelaySec: String(initialDelaySec),
            maxDelaySec: String(maxDelaySec)
        )
    }
}

public extension WorkflowEdit {
    func toWorkflow(updatedAt: String) -> Workflow {
        Workflow(
            id: id,
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            updatedAt: updatedAt,
            minDurationSec: asInt(minDurationSec, blank: 0),
            steps: steps.map { $0.toStep() }
        )
    }
}

public extension StepEdit {
    func toStep() -> Step {
        switch self {
        case .drive(let step):
            return Step.DriveUpload(
                id: step.id,
                onError: step.onError,
                retry: step.retry.toRetry(),
                folder: step.folder.trimmingCharacters(in: .whitespaces),
                includeMeta: step.includeMeta
            )

        case .hook(let step):
            let ref = step.secretRef?.trimmingCharacters(in: .whitespaces)
            return Step.Webhook(
                id: step.id,
                onError: step.onError,
                retry: step.retry.toRetry(),
                url: step.url.trimmingCharacters(in: .whitespaces),
                secretRef: (ref?.isEmpty ?? true) ? nil : ref
            )

        case .transcribe(let step):
            return Step.Transcribe(
                id: step.id,
                onError: step.onError,
                retry: step.retry.toRetry(),
                provider: step.provider,
                secretRef: step.secretRef.trimmingCharacters(in: .whitespaces),
                // docs/08: a provider either is addressed by an `invokeUrl`, may be, or never reads
                // one — and the form hides the field for the last kind, so carrying a leftover URL
                // out would fail validation for a field the user cannot see.
                invokeUrl: WorkflowParser.shared.invokeUrlUse(provider: step.provider) == .none
                    ? nil
                    : emptyToNil(step.invokeUrl),
                language: step.language,
                diarize: step.diarize,
                speakers: Speakers(
                    min: asInt(step.speakersMin, blank: invalidNumber),
                    max: asInt(step.speakersMax, blank: invalidNumber)
                ),
                model: emptyToNil(step.model)
            )
        }
    }
}

/// An optional string on the way out: only a field the user actually cleared becomes nil. What they
/// did not touch goes back exactly as it came in — trimming it here would rewrite a step nobody
/// edited, and would turn a whitespace-only value the parser accepts into an absent one.
private func emptyToNil(_ text: String) -> String? {
    text.isEmpty ? nil : text
}

public extension Language {
    /// The docs/02 spelling. `Language.wire` is the core's own and is internal to it.
    var tag: String { name.lowercased().replacingOccurrences(of: "_", with: "-") }
}

public extension RetryEdit {
    func toRetry() -> Retry {
        Retry(
            maxAttempts: asInt(maxAttempts, blank: invalidNumber),
            initialDelaySec: asInt(initialDelaySec, blank: invalidNumber),
            maxDelaySec: asInt(maxDelaySec, blank: invalidNumber)
        )
    }
}

public extension WorkflowsDocument {
    /// The edited workflow, and only it, goes back into the document with a fresh `updatedAt` —
    /// every other workflow keeps the timestamp it had, because docs/05 merges per workflow and
    /// restamping an untouched one would let this device win a race it never entered.
    func with(_ edit: WorkflowEdit, now: KotlinInstant) -> WorkflowsDocument {
        let edited = edit.toWorkflow(updatedAt: now.isoUtc)
        let next = workflows.contains { $0.id == edited.id }
            ? workflows.map { $0.id == edited.id ? edited : $0 }
            : workflows + [edited]
        return replacing(workflows: next)
    }

    /// docs/05: v1 has no tombstone, so a deletion is simply an absence.
    func without(_ id: String) -> WorkflowsDocument {
        replacing(workflows: workflows.filter { $0.id != id })
    }

    /// `revision`, `updatedAt` and `updatedBy` are the push's to write (docs/05 push 2), so an edit
    /// carries them through untouched.
    private func replacing(workflows: [Workflow]) -> WorkflowsDocument {
        WorkflowsDocument(
            schema: schema,
            revision: revision,
            updatedAt: updatedAt,
            updatedBy: updatedBy,
            workflows: workflows
        )
    }
}

/// docs/02: a workflow id is a ULID. `Ulid.generate` wants `kotlin.time.Clock`, which is not the
/// core's own `Clock` and is not a type the editor should have to know about.
public func mintWorkflowId(now: KotlinInstant) -> String {
    Ulid.shared.generate(clock: FixedKotlinClock(now))
}

/// Step ids are docs/02 identity, not decoration, so the editor mints one that matches
/// `^[a-z][a-z0-9_]{0,31}$` and never lets it change afterwards.
public func nextStepId(base: String, taken: Set<String>) -> String {
    if !taken.contains(base) { return base }
    var n = 2
    while taken.contains("\(base)\(n)") { n += 1 }
    return "\(base)\(n)"
}
