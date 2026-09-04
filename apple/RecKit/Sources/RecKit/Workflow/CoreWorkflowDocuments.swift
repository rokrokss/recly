import Foundation
import ReclyCore

/// The core's `WorkflowDocuments` over the real core — the same two things the Android and Windows
/// shells hand `WorkflowMutator` (`core.workflows.current/save`), so every platform's writes go
/// through one implementation of the gate rather than a port each.
///
/// `save` is the only thing that validates (docs/02); the document it hands back is what was
/// stored, and there is nowhere else for it to go — definitions are this device's own (docs/05).
///
/// The `__`-prefixed names are the raw Kotlin members: SKIE hides them behind the `async` wrappers
/// that callers use, but a Swift *implementation* of the interface still fills in the originals
/// (the same shape as `KeychainSecureStore`).
public final class CoreWorkflowDocuments: ReclyCore.WorkflowDocuments {
    private let core: ReclyCore_

    public init(core: ReclyCore_) {
        self.core = core
    }

    public func __current() async throws -> WorkflowsDocument {
        try await core.workflows.current()
    }

    public func __save(document: WorkflowsDocument) async throws -> any SaveResult {
        try await core.workflows.save(document: document)
    }
}

/// A Swift closure as the core mutator's `block` — a Kotlin `suspend (WorkflowsDocument) ->
/// WorkflowsDocument?`, which crosses the bridge as an object implementing the exported function
/// type rather than as a Swift function value.
///
/// The change itself is the shell's, and it is applied to the document the gate read *inside* its
/// lock: that is the whole point of handing the mutator a block instead of a finished document.
final class DocumentMutation: ReclyCore.KotlinSuspendFunction1 {
    private let block: @MainActor (WorkflowsDocument) -> WorkflowsDocument?

    init(_ block: @escaping @MainActor (WorkflowsDocument) -> WorkflowsDocument?) {
        self.block = block
    }

    /// Nil is Kotlin's "nothing to do", which the mutator answers with `MutationResult.Skipped`.
    func __invoke(p1: Any?) async throws -> Any? {
        guard let document = p1 as? WorkflowsDocument else { return nil }
        return await block(document)
    }
}
