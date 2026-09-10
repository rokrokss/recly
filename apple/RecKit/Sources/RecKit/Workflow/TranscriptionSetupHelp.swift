#if os(iOS) || os(macOS)
import SwiftUI

/// Optional help beside the new-workflow action; opening it never changes the workflow list.
public struct TranscriptionSetupHelp: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @State private var presented = false
    public init() {}

    private var title: String { RecKitStrings.localized("How to set up transcription") }

    public var body: some View {
        Button { presented = true } label: {
            Image(systemName: "info.circle")
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(minWidth: minTouch, minHeight: minTouch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: title))
        .accessibilityIdentifier("transcription-setup")
        #if os(iOS)
        .sheet(isPresented: $presented) {
            content
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        #else
        .help(title)
        .popover(isPresented: $presented, arrowEdge: .bottom) {
            content.frame(width: 360, height: 300)
                .onExitCommand { presented = false }
        }
        #endif
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            HStack(alignment: .center, spacing: Space.s) {
                Text(verbatim: title)
                    .font(blueprint.fonts.rowTitle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                Button { presented = false } label: {
                    Image(systemName: "xmark")
                        .frame(minWidth: minTouch, minHeight: minTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close")))
                .accessibilityIdentifier("transcription-setup-close")
            }
            ScrollView {
                Text(verbatim: RecKitStrings.localized("To get a transcript, connect Google Drive in Settings, add a transcription step after Drive upload in a workflow, and enter the provider’s API key. Select that workflow before recording. Recordings without a transcription step are still saved."))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("transcription-setup-body")
            }
        }
        .padding(Space.m)
        .foregroundStyle(blueprint.palette.text)
        .background(blueprint.palette.surface)
    }
}
#endif
