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
    @State private var query = ""
    @State private var searching = false
    @State private var copied = false
    @State private var matches: [TranscriptBlock]

    init(document: TranscriptDocument, seekableDurationSec: Double, canSeek: Bool, onSeek: @escaping (Double) -> Void) {
        self.document = document
        self.seekableDurationSec = seekableDurationSec
        self.canSeek = canSeek
        self.onSeek = onSeek
        _matches = State(initialValue: document.blocks)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Space.s) {
                FlowLayout {
                    BlueprintButton(loc("Search transcript"), tone: .quiet) {
                        searching.toggle()
                        if !searching { query = "" }
                    }
                    .accessibilityIdentifier("transcript-search-toggle")
                    BlueprintButton(loc(copied ? "Copied" : "Copy all"), tone: .quiet) {
                        #if os(iOS)
                        UIPasteboard.general.string = document.plainText
                        #else
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(document.plainText, forType: .string)
                        #endif
                        copied = true
                    }
                    .accessibilityIdentifier("transcript-copy")
                }
                .padding(.horizontal, Space.m)
                if searching {
                    BlueprintField(loc("Search transcript"), text: $query)
                        .accessibilityIdentifier("transcript-search")
                        .padding(.horizontal, Space.m)
                    if !query.isEmpty {
                        BlueprintButton(loc("Clear search"), tone: .quiet) { query = "" }
                            .padding(.horizontal, Space.m)
                    }
                }
                if matches.isEmpty {
                    Text(verbatim: loc("No matching passages"))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
                ForEach(matches, id: \.index) { block in
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
        .onChange(of: query) { _, value in matches = document.search(query: value) }
        .onChange(of: document) { _, value in matches = value.search(query: query) }
        .padding(.top, Space.s)
        .task(id: copied) {
            guard copied else { return }
            do { try await Task.sleep(for: .seconds(3)); copied = false } catch {}
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
