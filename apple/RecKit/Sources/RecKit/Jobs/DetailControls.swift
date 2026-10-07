import ReclyCore
#if os(iOS) || os(macOS)
import SwiftUI
#if os(iOS)
import UIKit
#endif
#endif

/// docs/08 "Exports": what the detail's Share offers, in the order the sheet lists them.
public enum ShareFormat: CaseIterable, Identifiable, Sendable {
    case transcript, notes, subtitles, webSubtitles, audio

    public var id: Self { self }

    public var title: String {
        switch self {
        case .transcript: return RecKitStrings.localized("Transcript")
        case .notes: return RecKitStrings.localized("Transcript for notes")
        case .subtitles: return RecKitStrings.localized("Subtitles")
        case .webSubtitles: return RecKitStrings.localized("Subtitles for the web")
        case .audio: return RecKitStrings.localized("Audio")
        }
    }

    /// The format under the name — what an app on the other end will be handed.
    public var detail: String {
        switch self {
        case .transcript: return RecKitStrings.localized("Text · .txt")
        case .notes: return RecKitStrings.localized("Markdown · .md")
        case .subtitles: return RecKitStrings.localized("SubRip · .srt")
        case .webSubtitles: return RecKitStrings.localized("WebVTT · .vtt")
        case .audio: return RecKitStrings.localized("M4A")
        }
    }

    public var export: ExportFormat {
        switch self {
        case .transcript: return .txt
        case .notes: return .md
        case .subtitles: return .srt
        case .webSubtitles: return .vtt
        case .audio: return .audio
        }
    }

    public var needsTranscript: Bool { self != .audio }

    var glyph: String {
        switch self {
        case .transcript, .notes: return "doc.text"
        case .subtitles, .webSubtitles: return "captions.bubble"
        case .audio: return "waveform"
        }
    }
}

#if os(iOS) || os(macOS)

/// The detail header's icon buttons (docs/09 §2): an SF Symbol in the quiet ink, a 44pt target.
struct HeaderIcon: View {
    let systemName: String
    @Environment(\.blueprint) private var blueprint
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Image(systemName: systemName)
            .font(blueprint.fonts.sans(TypeSize.body))
            .foregroundStyle(isEnabled ? blueprint.palette.textMuted : blueprint.palette.grid)
            .frame(width: minTouch, height: minTouch)
            .contentShape(Rectangle())
    }
}

/// One item of a menu that can say why it is off: the reason is the item's second line (docs/09 §2).
struct ReasonedMenuItem: View {
    let title: String
    let reason: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(verbatim: title)
            if let reason { Text(verbatim: reason) }
        }
        .disabled(reason != nil)
    }
}

/// docs/09 §2: the detail's More menu — Rename · Edit transcript · Transcribe again · Add highlight — with
/// the ones that cannot run now shown off, and why.
struct DetailMoreMenu: View {
    @ObservedObject var model: RecordingDetailModel
    let positionSec: Double
    let rename: () -> Void
    let edit: () -> Void
    let transcribeAgain: () -> Void
    let addHighlight: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        Menu {
            Button(RecKitStrings.localized("Rename"), action: rename).disabled(model.writing)
            ReasonedMenuItem(title: RecKitStrings.localized("Edit transcript"), reason: model.editReason, action: edit)
            ReasonedMenuItem(title: RecKitStrings.localized("Transcribe again"), reason: model.retranscribeReason, action: transcribeAgain)
            ReasonedMenuItem(
                title: RecKitStrings.localized("Add highlight at %@", LedgerFormat.clock(Int(positionSec))),
                reason: model.hasAudio ? nil : RecKitStrings.localized("No audio on this device"),
                action: addHighlight
            )
        } label: {
            HeaderIcon(systemName: "ellipsis.circle")
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .fixedSize()
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized("More")))
        .accessibilityIdentifier("detail-more")
    }
}

/// docs/09 §1: a marked moment's two actions, from its tick on the waveform or its flag in the
/// transcript. Removing one is not deleting a recording, so it is not red.
struct HighlightMenu<Label: View>: View {
    let atSec: Double
    let go: () -> Void
    let remove: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Menu {
            Button(RecKitStrings.localized("Go to %@", LedgerFormat.clock(Int(atSec))), action: go)
            Button(RecKitStrings.localized("Remove highlight"), action: remove)
        } label: {
            label()
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Highlight %@", LedgerFormat.clock(Int(atSec)))))
        .accessibilityAction(named: Text(verbatim: RecKitStrings.localized("Go to %@", LedgerFormat.clock(Int(atSec)))), go)
        .accessibilityAction(named: Text(verbatim: RecKitStrings.localized("Remove highlight")), remove)
    }
}

/// docs/09 §4: who is speaking, as a quiet badge — the name when the user gave one, else the id in mono.
/// Told apart by the label only: no speaker has a colour (2026-10-02).
struct SpeakerBadge: View {
    let text: String
    let mono: Bool
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        Text(verbatim: text)
            .font(mono ? blueprint.fonts.monoBodySmall : blueprint.fonts.sans(TypeSize.small, weight: .medium))
            .foregroundStyle(blueprint.palette.textMuted)
            .lineLimit(1)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .overlay {
                RoundedRectangle(cornerRadius: Radius.badge).strokeBorder(blueprint.palette.grid, lineWidth: blueprint.line)
            }
            .frame(minHeight: minTouch)
            .contentShape(Rectangle())
    }
}

/// docs/09 §5: the speaker menu — rename the speaker, or move this line to another one, or to a new one.
struct SpeakerMenu<Label: View>: View {
    let transcript: Transcript
    let current: String
    let rename: (() -> Void)?
    let change: (String?) -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Menu {
            if let rename {
                Button(RecKitStrings.localized("Rename speaker"), action: rename)
            }
            Menu(RecKitStrings.localized("Change speaker for this line")) {
                ForEach(transcript.speakers.map(\.id).filter { $0 != current }, id: \.self) { id in
                    Button(transcript.label(of: id)) { change(id) }
                }
                Button(RecKitStrings.localized("New speaker")) { change(nil) }
            }
        } label: {
            label()
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
    }
}

/// docs/10 "Search" · docs/09 §6: the find bar over the transcript — where this match is among them, and
/// the way to the one before and after it. Moving between matches scrolls the text, not the playhead.
public struct FindBar: View {
    private let index: Int
    private let count: Int
    private let previous: () -> Void
    private let next: () -> Void
    private let close: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    /// - Parameter index: the current match, from 0.
    public init(index: Int, count: Int, previous: @escaping () -> Void, next: @escaping () -> Void, close: @escaping () -> Void) {
        self.index = index
        self.count = count
        self.previous = previous
        self.next = next
        self.close = close
    }

    public var body: some View {
        HStack(spacing: Space.xs) {
            arrow("‹", label: "Previous match", action: previous)
            Text(verbatim: "\(count == 0 ? 0 : index + 1) / \(count)")
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("%1$@ of %2$@", "\(count == 0 ? 0 : index + 1)", "\(count)")))
            arrow("›", label: "Next match", action: next)
            Spacer(minLength: 0)
            Button(action: close) {
                Text(verbatim: "×")
                    .font(blueprint.fonts.sans(TypeSize.body))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(width: minTouch, height: minTouch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close search")))
            .accessibilityIdentifier("find-close")
        }
        .padding(.horizontal, Space.s)
        .background(blueprint.palette.surface)
        .accessibilityIdentifier("find-bar")
    }

    private func arrow(_ glyph: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: glyph)
                .font(blueprint.fonts.sans(TypeSize.title))
                .foregroundStyle(count > 1 ? blueprint.palette.accent : blueprint.palette.grid)
                .frame(width: minTouch, height: minTouch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(count < 2)
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized(label)))
    }
}

/// docs/10 "Search" · docs/09 §6: one recording a search found — when, the title with its matches in the
/// accent, up to two lines of transcript with the matches marked, and the moment of the first.
/// The phone's list and the Mac's window list draw the same row.
public struct SearchResultRow: View {
    private let hit: SearchHit
    private let action: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(hit: SearchHit, action: @escaping () -> Void) {
        self.hit = hit
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: LedgerFormat.date(hit.startedAt))
                    Text(verbatim: LedgerFormat.time(hit.startedAt))
                }
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(width: 62, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(Self.marked(title, ranges: hit.titleRanges, tint: blueprint.palette.accent, background: nil))
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.text)
                        .lineLimit(2)
                    ForEach(Array(hit.snippets.prefix(2).enumerated()), id: \.offset) { _, snippet in
                        Text(Self.marked(snippet.text, ranges: snippet.ranges, tint: blueprint.palette.text, background: blueprint.palette.accent.opacity(0.16)))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let first = hit.snippets.first {
                    Text(verbatim: LedgerFormat.clock(Int(first.atSec)))
                        .font(blueprint.fonts.monoBodySmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { HairLine() }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("search-result")
    }

    private var title: String {
        hit.title?.isEmpty == false ? hit.title! : RecKitStrings.localized("Untitled")
    }

    /// [text] with the core's ranges marked. The core counts in UTF-16 code units, as Kotlin strings do.
    static func marked(_ text: String, ranges: [SearchRange], tint: Color, background: Color?) -> AttributedString {
        var attributed = AttributedString(text)
        let utf16 = text.utf16
        for range in ranges {
            guard range.offset >= 0, range.length > 0, Int(range.offset + range.length) <= utf16.count else { continue }
            let start = String.Index(utf16Offset: Int(range.offset), in: text)
            let end = String.Index(utf16Offset: Int(range.offset + range.length), in: text)
            guard let from = AttributedString.Index(start, within: attributed),
                  let to = AttributedString.Index(end, within: attributed) else { continue }
            attributed[from ..< to].foregroundColor = tint
            if let background { attributed[from ..< to].backgroundColor = background }
        }
        return attributed
    }
}

/// docs/08 "Exports" · docs/09 §3 (phones): the Share sheet — one row per format, and Copy all. A file
/// row asks the core for the file, says `Preparing…` while it does (joining the audio takes a moment),
/// then hands it to the system share sheet.
#if os(iOS)
struct DetailShareSheet: View {
    @ObservedObject var model: RecordingDetailModel
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @State private var preparing: ShareFormat?
    @State private var copied = false

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: RecKitStrings.localized("Share"))
            HairLine()
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(ShareFormat.allCases) { format in
                        row(format)
                    }
                    copyRow
                }
            }
        }
        .dotGridBackground()
        .presentationDetents([.medium, .large])
    }

    private func reason(_ format: ShareFormat) -> String? {
        if format.needsTranscript, model.transcript == nil { return RecKitStrings.localized("No transcript yet") }
        if format == .audio, model.audioUnavailable { return RecKitStrings.localized("No audio on this device") }
        return nil
    }

    private func row(_ format: ShareFormat) -> some View {
        let reason = reason(format)
        return Button {
            share(format)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: format.glyph)
                    .font(blueprint.fonts.sans(TypeSize.body))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: format.title)
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(reason == nil ? blueprint.palette.text : blueprint.palette.textMuted)
                    Text(verbatim: reason ?? format.detail)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if preparing == format {
                    LoadingText(text: RecKitStrings.localized("Preparing…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                }
            }
            .padding(.horizontal, Space.m)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(reason != nil || preparing != nil)
        .overlay(alignment: .bottom) { HairLine() }
        .background(blueprint.palette.surface)
        .accessibilityIdentifier("share-\(format.export.name.lowercased())")
    }

    private var copyRow: some View {
        Button {
            model.copyAll()
            copied = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(blueprint.fonts.sans(TypeSize.body))
                    .foregroundStyle(copied ? blueprint.palette.success : blueprint.palette.textMuted)
                    .frame(width: 24)
                Text(verbatim: RecKitStrings.localized("Copy all"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(model.document == nil ? blueprint.palette.textMuted : blueprint.palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if copied {
                    Text(verbatim: "\(BlueprintChip.selectionMark) \(RecKitStrings.localized("Copied"))")
                        .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                        .foregroundStyle(blueprint.palette.success)
                }
            }
            .padding(.horizontal, Space.m)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.document == nil)
        .overlay(alignment: .bottom) { HairLine() }
        .background(blueprint.palette.surface)
        .accessibilityIdentifier("share-copy")
        .task(id: copied) {
            guard copied else { return }
            do { try await Task.sleep(for: .seconds(3)); copied = false } catch {}
        }
    }

    private func share(_ format: ShareFormat) {
        preparing = format
        Task {
            let url = await model.export(format)
            preparing = nil
            if let url { SystemShare.present(url) }
        }
    }
}

/// The system share sheet, over whatever is presented on top — here, the Share sheet itself.
enum SystemShare {
    @MainActor static func present(_ url: URL) {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        guard var top = scene?.keyWindow?.rootViewController else { return }
        while let presented = top.presentedViewController { top = presented }
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = top.view
        activity.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
        top.present(activity, animated: true)
    }
}
#endif
#endif
