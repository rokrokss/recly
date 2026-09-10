#if os(iOS) || os(macOS)
import SwiftUI

/// Optional setup guidance beside the workflow list, shared by both Apple editors.
public struct TranscriptionSetupHelp: View {
    @Environment(\.blueprint) private var blueprint
    @State private var expanded = false
    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            BlueprintButton(RecKitStrings.localized("Transcription setup"), leading: expanded ? "−" : "+") {
                expanded.toggle()
            }
            .accessibilityIdentifier("transcription-setup")
            .accessibilityValue(Text(verbatim: RecKitStrings.localized(expanded ? "Expanded" : "Collapsed")))
            if expanded {
                Text(verbatim: RecKitStrings.localized("To get a transcript, connect Google Drive in Settings, add a transcription step after Drive upload in a workflow, and enter the provider’s API key. Select that workflow before recording. Recordings without a transcription step are still saved."))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("transcription-setup-body")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Space.m)
    }
}
#endif
