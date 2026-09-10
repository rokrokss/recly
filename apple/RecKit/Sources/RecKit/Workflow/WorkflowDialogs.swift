import SwiftUI

/// ADR-016: a workflow deleted here is gone from this device, and docs/05 keeps the document local —
/// there is no sync and no copy anywhere to bring it back from. So the question is asked before the
/// write, in the same words Android asks it in.
///
/// A selection change while the question is open also disables its destructive action.
public struct WorkflowDeleteDialog: View {
    private let item: WorkflowItem
    private let delete: (WorkflowItem) -> Void
    private let cancel: () -> Void

    /// docs/07 rule 3: the lines below are resolved outside SwiftUI, and reading the locale is what
    /// declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale

    public init(
        item: WorkflowItem,
        delete: @escaping (WorkflowItem) -> Void,
        cancel: @escaping () -> Void
    ) {
        self.item = item
        self.delete = delete
        self.cancel = cancel
    }

    public var body: some View {
        BlueprintDialog(title: loc("Delete ‘%@’?", item.name.isEmpty ? loc("Unnamed") : item.name)) {
            BlueprintButton(loc("Cancel"), tone: .quiet) { cancel() }
            BlueprintButton(loc("Delete"), tone: .danger) { delete(item) }
                .disabled(item.isDeviceDefault)
                .accessibilityIdentifier("workflow-delete-confirm")
        } content: {
            BlueprintDialogText(
                loc("This workflow disappears from this device. Jobs that already exist still run.")
            )
            .accessibilityIdentifier("workflow-delete-body")
            if item.isDeviceDefault {
                BlueprintDialogText(
                    loc("Select another workflow before deleting this one."),
                    tone: .danger
                )
                .accessibilityIdentifier("workflow-delete-in-use")
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }

    private func loc(_ key: String, _ argument: String) -> String {
        RecKitStrings.localized(key, argument)
    }
}
