import AppKit
import ReclyCore
import RecKit
import SwiftUI

/// docs/08 "Exports" · ux §3: the Details window's `Share` toolbar button — the recording's transcript as
/// text, Markdown, SubRip or WebVTT, or its audio, handed to the system's share picker under the button,
/// or saved where the user picks; and `Copy all`, the text with its times on the clipboard.
struct ShareMenu: View {
    @ObservedObject var detail: RecordingDetailModel
    @Environment(\.blueprint) private var blueprint
    @State private var anchor = Anchor()
    @State private var preparing = false
    @State private var copied = false

    var body: some View {
        Menu {
            ForEach(ShareFormat.allCases) { format in
                Button { share(format) } label: {
                    Text(verbatim: format.title)
                    Text(verbatim: reason(format) ?? format.detail)
                }
                .disabled(reason(format) != nil)
            }
            Divider()
            Button(RecKitStrings.localized("Copy all")) {
                detail.copyAll()
                copied = true
            }
            .disabled(detail.document == nil)
            Menu(loc("Save as…")) {
                ForEach(ShareFormat.allCases) { format in
                    Button(format.title) { save(format) }
                        .disabled(reason(format) != nil)
                }
            }
        } label: {
            if preparing {
                LoadingText(
                    text: RecKitStrings.localized("Preparing…"), font: blueprint.fonts.bodySmall, color: blueprint.palette.textMuted
                )
            } else {
                Image(systemName: copied ? "checkmark" : "square.and.arrow.up")
            }
        }
        .disabled(preparing)
        .background(AnchorView(anchor: anchor))
        .help(RecKitStrings.localized(copied ? "Copied" : "Share"))
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized(copied ? "Copied" : "Share")))
        .accessibilityIdentifier("detail-share")
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(3))
            copied = false
        }
    }

    /// Why a format cannot be had right now, nil when it can.
    private func reason(_ format: ShareFormat) -> String? {
        if !format.needsTranscript {
            return detail.audioUnavailable ? RecKitStrings.localized("No audio on this device") : nil
        }
        return detail.document == nil || detail.availability == .empty ? RecKitStrings.localized("No transcript yet") : nil
    }

    /// The file, then the share picker under the button it was asked from.
    private func share(_ format: ShareFormat) {
        Task {
            guard let file = await prepare(format), let view = anchor.view else { return }
            NSSharingServicePicker(items: [file]).show(relativeTo: view.bounds, of: view, preferredEdge: .minY)
        }
    }

    /// The file, then a save panel named the way the core named it.
    private func save(_ format: ShareFormat) {
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
    private func prepare(_ format: ShareFormat) async -> URL? {
        preparing = true
        defer { preparing = false }
        return await detail.export(format)
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
