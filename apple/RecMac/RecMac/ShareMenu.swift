import AppKit
import ReclyCore
import RecKit
import SwiftUI

/// docs/08 "Exports" · ux §3: the Details window's `Share` toolbar button — the recording's transcript as
/// text, Markdown, SubRip or WebVTT, or its audio, handed to the system's share picker under the button,
/// or saved where the user picks; and `Copy all`, the text with its times on the clipboard.
struct ShareMenu: View {
    @ObservedObject var detail: RecordingDetailModel
    /// `core.exportFile`: the absolute path of the file, nil when there is nothing in that format.
    let export: (ExportFormat) async -> String?
    @Environment(\.blueprint) private var blueprint
    @State private var anchor = Anchor()
    @State private var preparing = false
    @State private var copied = false

    /// One format: the label and what it is.
    private struct Item {
        let format: ExportFormat
        let title: String
        let kind: String
    }

    /// The five formats, in the order every shell lists them.
    private static let formats = [
        Item(format: .txt, title: "Transcript", kind: "Text · .txt"),
        Item(format: .md, title: "Transcript for notes", kind: "Markdown · .md"),
        Item(format: .srt, title: "Subtitles", kind: "SubRip · .srt"),
        Item(format: .vtt, title: "Subtitles for the web", kind: "WebVTT · .vtt"),
        Item(format: .audio, title: "Audio", kind: "M4A"),
    ]

    var body: some View {
        Menu {
            ForEach(Self.formats, id: \.title) { item in
                Button { share(item.format) } label: {
                    Text(verbatim: loc(item.title))
                    Text(verbatim: reason(item.format) ?? loc(item.kind))
                }
                .disabled(reason(item.format) != nil)
            }
            Divider()
            Button(loc("Copy all")) { copyAll() }
                .disabled(detail.document == nil)
            Menu(loc("Save as…")) {
                ForEach(Self.formats, id: \.title) { item in
                    Button(loc(item.title)) { save(item.format) }
                        .disabled(reason(item.format) != nil)
                }
            }
        } label: {
            if preparing {
                LoadingText(text: loc("Preparing…"), font: blueprint.fonts.bodySmall, color: blueprint.palette.textMuted)
            } else {
                Image(systemName: copied ? "checkmark" : "square.and.arrow.up")
            }
        }
        .disabled(preparing)
        .background(AnchorView(anchor: anchor))
        .help(loc(copied ? "Copied" : "Share"))
        .accessibilityLabel(Text(verbatim: loc(copied ? "Copied" : "Share")))
        .accessibilityIdentifier("detail-share")
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(3))
            copied = false
        }
    }

    /// Why a format cannot be had right now, nil when it can.
    private func reason(_ format: ExportFormat) -> String? {
        if format == .audio { return detail.hasAudio ? nil : loc("No audio on this device") }
        return detail.document == nil || detail.availability == .empty ? loc("No transcript yet") : nil
    }

    private func copyAll() {
        guard let text = detail.document?.plainText else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        copied = true
    }

    /// The file, then the share picker under the button it was asked from.
    private func share(_ format: ExportFormat) {
        Task {
            guard let file = await prepare(format), let view = anchor.view else { return }
            NSSharingServicePicker(items: [file]).show(relativeTo: view.bounds, of: view, preferredEdge: .minY)
        }
    }

    /// The file, then a save panel named the way the core named it.
    private func save(_ format: ExportFormat) {
        Task {
            guard let file = await prepare(format) else { return }
            let panel = NSSavePanel()
            panel.nameFieldStringValue = file.lastPathComponent
            panel.canCreateDirectories = true
            let answer: NSApplication.ModalResponse
            if let window = anchor.view?.window {
                answer = await panel.beginSheetModal(for: window)
            } else {
                answer = panel.runModal()
            }
            guard answer == .OK, let target = panel.url else { return }
            do {
                // The panel has already asked whether to replace a file of that name.
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.copyItem(at: file, to: target)
            } catch {
                NSSound.beep()
            }
        }
    }

    /// Joining the audio parts can take a moment; the button says so meanwhile.
    private func prepare(_ format: ExportFormat) async -> URL? {
        preparing = true
        defer { preparing = false }
        return await export(format).map { URL(fileURLWithPath: $0) }
    }
}

/// The toolbar button's own view, for the share picker to stand under.
final class Anchor {
    weak var view: NSView?
}

private struct AnchorView: NSViewRepresentable {
    let anchor: Anchor

    func makeNSView(context: Self.Context) -> NSView {
        let view = NSView()
        anchor.view = view
        return view
    }

    func updateNSView(_ nsView: NSView, context: Self.Context) {
        anchor.view = nsView
    }
}
