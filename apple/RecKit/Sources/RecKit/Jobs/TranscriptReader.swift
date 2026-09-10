#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// One cached document and lazy paragraphs; playback ticks do not regroup the transcript.
struct TranscriptReader: View {
    let document: TranscriptDocument
    let seekableDurationSec: Double
    let canSeek: Bool
    let onSeek: (Double) -> Void
    @Environment(\.blueprint) private var blueprint

    init(document: TranscriptDocument, seekableDurationSec: Double, canSeek: Bool, onSeek: @escaping (Double) -> Void) {
        self.document = document
        self.seekableDurationSec = seekableDurationSec
        self.canSeek = canSeek
        self.onSeek = onSeek
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Space.s) {
                ForEach(document.blocks, id: \.index) { block in
                    VStack(alignment: .leading, spacing: Space.xs) {
                        let stamp = LedgerFormat.elapsed(Int(block.start))
                        BlueprintButton("\(stamp) \(block.speaker)", tone: .quiet, mono: true) {
                            onSeek(block.start)
                        }
                        .disabled(!canSeek || block.start >= seekableDurationSec)
                        .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Go to %@", stamp)))
                        .accessibilityIdentifier("transcript-time-\(block.index)")
                        Text(verbatim: block.text)
                            .font(blueprint.fonts.bodySmall)
                            .foregroundStyle(blueprint.palette.text)
                            .textSelection(.enabled)
                            .accessibilityIdentifier("transcript-text-\(block.index)")
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .id(block.index)
                }
            }
            .padding(.vertical, Space.s)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.top, Space.s)
    }

}

/// The detail header's copy action, with a spoken label and icon-only completion feedback.
struct TranscriptCopyButton: View {
    let document: TranscriptDocument
    @Environment(\.blueprint) private var blueprint
    @State private var copied = false

    var body: some View {
        Button {
            #if os(iOS)
            UIPasteboard.general.string = document.plainText
            #else
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(document.plainText, forType: .string)
            #endif
            copied = true
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                .font(blueprint.fonts.sans(TypeSize.body))
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(width: minTouch, height: minTouch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized(copied ? "Copied" : "Copy all")))
        .accessibilityIdentifier("transcript-copy")
        .help(RecKitStrings.localized(copied ? "Copied" : "Copy all"))
        .task(id: copied) {
            guard copied else { return }
            do { try await Task.sleep(for: .seconds(3)); copied = false } catch {}
        }
    }
}
#endif
