import SwiftUI

public enum WorkflowProtection: Identifiable {
    case discardEditor
    case discardSecret
    case deleteKey(String, [String])

    public var id: String {
        switch self {
        case .discardEditor: "editor"
        case .discardSecret: "secret"
        case .deleteKey(let name, _): "key/\(name)"
        }
    }
}

private struct WorkflowProtectionModifier: ViewModifier {
    @ObservedObject var model: WorkflowsModel
    @Environment(\.locale) private var locale

    func body(content: Content) -> some View {
        content.blueprintDialog(item: Binding(
            get: { model.protection },
            set: { if $0 == nil { model.answerProtection(false) } }
        )) { prompt in
            switch prompt {
            case .discardEditor, .discardSecret:
                BlueprintDialog(title: loc("Discard unsaved changes?")) {
                    BlueprintButton(loc("Keep editing")) { model.answerProtection(false) }
                    BlueprintButton(loc("Discard changes"), tone: .danger) { model.answerProtection(true) }
                } content: {
                    BlueprintDialogText(loc("Your changes have not been saved."))
                }
            case .deleteKey(let name, let workflows):
                BlueprintDialog(title: RecKitStrings.localized("Delete key: %@", name)) {
                    BlueprintButton(loc("Cancel")) { model.answerProtection(false) }
                    BlueprintButton(loc("Delete"), tone: .danger) { model.answerProtection(true) }
                } content: {
                    BlueprintDialogText(loc(workflows.isEmpty
                        ? "No saved workflow uses this key."
                        : "Workflows using this key will stop working until a key is added again."))
                    if !workflows.isEmpty {
                        BlueprintDialogText(RecKitStrings.localized("Used by: %@", workflows.joined(separator: ", ")))
                    }
                }
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

extension View {
    public func workflowProtection(model: WorkflowsModel) -> some View {
        modifier(WorkflowProtectionModifier(model: model))
    }
}
