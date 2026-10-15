import ReclyCore
#if os(iOS) || os(macOS)
import SwiftUI
#if os(iOS)
import UIKit
#endif
#endif

/// docs/08 "Exports": what the detail's Share offers, in the order the sheet lists them.
public enum ShareFormat: CaseIterable, Identifiable, Sendable {
    case transcript, notes, summary, subtitles, webSubtitles, audio

    public var id: Self { self }

    public var title: String {
        switch self {
        case .transcript: return RecKitStrings.localized("Transcript")
        case .notes: return RecKitStrings.localized("Transcript for notes")
        case .summary: return RecKitStrings.localized("Summary")
        case .subtitles: return RecKitStrings.localized("Subtitles")
        case .webSubtitles: return RecKitStrings.localized("Subtitles for the web")
        case .audio: return RecKitStrings.localized("Audio")
        }
    }

    /// The format under the name — what an app on the other end will be handed.
    public var detail: String {
        switch self {
        case .transcript, .summary: return RecKitStrings.localized("Text · .txt")
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
        case .summary: return .summary
        case .subtitles: return .srt
        case .webSubtitles: return .vtt
        case .audio: return .audio
        }
    }

    /// The audio needs none, and the summary has its own reason (`No summary yet`).
    public var needsTranscript: Bool { self != .audio && self != .summary }

    var glyph: String {
        switch self {
        case .transcript, .notes, .summary: return "doc.text"
        case .subtitles, .webSubtitles: return "captions.bubble"
        case .audio: return "waveform"
        }
    }
}

#if os(iOS) || os(macOS)

/// One item of a menu that can say why it is off: the reason is the item's second line (docs/09 "Detail
/// header and More menu").
struct ReasonedMenuItem: View {
    let title: String
    let reason: String?
    var role: ButtonRole?
    let action: () -> Void

    var body: some View {
        Button(role: role, action: action) {
            Text(verbatim: title)
            if let reason { Text(verbatim: reason) }
        }
        .disabled(reason != nil)
    }
}

/// docs/09 "Detail header and More menu": the detail's More menu — Rename · Edit transcript · Transcribe
/// again ─ Summarize · Summarize as… · Edit summary · Ask about this recording… · Fill in title and names ─ Add
/// highlight — with the ones
/// that cannot run now shown off, and why. The three groups are parted by a hairline, and a group with nothing in
/// it (no ChatGPT, no summary) takes its divider with it (2026-10-10, 2.2). On the Mac a fourth group is the
/// recording's folder — Open in Drive, or Show in Finder — where the shell hands the page one, and the last is the
/// red Delete (2026-10-15), which only the Mac hands in: the phone's list row has its own.
struct DetailMoreMenu: View {
    @ObservedObject var model: RecordingDetailModel
    let positionSec: Double
    let rename: () -> Void
    let edit: () -> Void
    let transcribeAgain: () -> Void
    /// Summarize, as Settings' format (nil) or as the one picked under `Summarize as…`.
    let summarize: (SummaryFormat?) -> Void
    let editSummary: () -> Void
    let askAbout: () -> Void
    let addHighlight: () -> Void
    @Environment(\.locale) private var locale
    #if os(iOS)
    /// `Summarize as…` asks for the format in a list of its own, where Android's menu turns into one. Not inside
    /// the menu: SwiftUI cannot tell that an open menu closed, so a menu turned into the formats opened on them
    /// again the next time (checked on iOS 26.5, 2026-10-15).
    @State private var choosingFormat = false
    #endif

    var body: some View {
        let menu = Menu {
            items
        } label: {
            BlueprintIconLabel("ellipsis.circle")
        }
        .menuIndicator(.hidden)
        .buttonStyle(BlueprintPressStyle())
        .focusEffectDisabled()
        .fixedSize()
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized("More")))
        .accessibilityIdentifier("detail-more")
        #if os(iOS)
        menu.confirmationDialog(
            RecKitStrings.localized("Summarize as…"),
            isPresented: $choosingFormat,
            titleVisibility: .visible
        ) {
            formatButtons
            Button(RecKitStrings.localized("Cancel"), role: .cancel) {}
        }
        #else
        menu
        #endif
    }

    @ViewBuilder
    private var items: some View {
        ReasonedMenuItem(title: RecKitStrings.localized("Rename"), reason: model.renameReason, action: rename)
        ReasonedMenuItem(title: RecKitStrings.localized("Edit transcript"), reason: model.editReason, action: edit)
        ReasonedMenuItem(title: RecKitStrings.localized("Transcribe again"), reason: model.retranscribeReason, action: transcribeAgain)
        if model.summaryOffered || model.summaryEditable { Divider() }
        // docs/08 "Summaries": asked for here, one recording at a time; not offered where ChatGPT is not.
        if model.summaryOffered {
            ReasonedMenuItem(title: model.summarizeTitle, reason: model.summarizeReason) { summarize(nil) }
            // Off, it is a plain item with its reason: the phone draws a disabled submenu as if it were on.
            if let reason = model.summarizeReason {
                ReasonedMenuItem(title: RecKitStrings.localized("Summarize as…"), reason: reason) {}
            } else {
                #if os(iOS)
                // The phone's list: the menu closes and the formats are asked for on their own.
                Button(RecKitStrings.localized("Summarize as…")) { choosingFormat = true }
                    .accessibilityIdentifier("more-summarize-as")
                #else
                // A submenu on the desktop — the speaker menu's one-of-many — with the current summary's ✓.
                Menu(RecKitStrings.localized("Summarize as…")) { formatButtons }
                #endif
            }
        }
        // docs/08 "Summaries": the summary in the user's own words, once there is one. No ChatGPT is asked.
        if model.summaryEditable {
            ReasonedMenuItem(title: RecKitStrings.localized("Edit summary"), reason: model.editSummaryReason, action: editSummary)
        }
        // docs/08 "Ask": one question about the recording, in a panel of its own.
        if model.summaryOffered {
            ReasonedMenuItem(title: RecKitStrings.localized("Ask about this recording…"), reason: model.askReason, action: askAbout)
            // docs/08 "Titles and speaker names": what the recording lacks, from the plan. It opens nothing — the title
            // shows in the header and the names in the transcript.
            ReasonedMenuItem(title: RecKitStrings.localized("Fill in title and names"), reason: model.fillReason) {
                Task { await model.fillTitleAndNames() }
            }
        }
        Divider()
        ReasonedMenuItem(
            title: RecKitStrings.localized("Add highlight at %@", model.stamp(positionSec)),
            reason: model.highlightReason,
            action: addHighlight
        )
        // The recording's folder, as the list row offers it — only where the shell handed one over (the Mac).
        if model.openInDrive != nil || model.showInFinder != nil { Divider() }
        if let openInDrive = model.openInDrive {
            Button(RecKitStrings.localized("Open in Drive"), action: openInDrive)
        }
        if let showInFinder = model.showInFinder {
            Button(RecKitStrings.localized("Show in Finder"), action: showInFinder)
        }
        // docs/03 "Deleting in the app": the shell's question, with its storage choice. No shortcut — a delete is
        // asked for by a hand on the menu.
        if let delete = model.delete {
            Divider()
            ReasonedMenuItem(title: RecKitStrings.localized("Delete"), reason: model.deleteReason, role: .destructive, action: delete)
        }
    }

    @ViewBuilder
    private var formatButtons: some View {
        ForEach(model.summaryFormats, id: \.self) { format in
            let label = ChatGptText.formatLabel(format)
            Button(model.summaryFormat == format ? "\(BlueprintChip.selectionMark) \(label)" : label) {
                summarize(format)
            }
        }
    }
}

/// docs/09 "Highlights": a marked moment's two actions, from its tick on the waveform or its square in the
/// transcript. Removing one is not deleting a recording, so it is not red.
struct HighlightMenu<Label: View>: View {
    let atSec: Double
    /// The moment as the page draws its times (2026-10-08 §3); what a screen reader hears stays the
    /// full `00:12:34`.
    let stamp: String
    let go: () -> Void
    let remove: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Menu {
            Button(RecKitStrings.localized("Go to %@", stamp), action: go)
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

/// docs/09 "Transcript reader": who is speaking, as a quiet badge — the name when the user gave one, else the
/// id in mono. Told apart by the label only: no speaker has a colour (2026-10-02). The box is the time
/// button's — one height, one corner — so the two stand on one line (2026-10-09).
struct SpeakerBadge: View {
    let text: String
    let mono: Bool
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        Text(verbatim: text)
            .font(mono ? blueprint.fonts.monoBodySmall : blueprint.fonts.sans(TypeSize.small, weight: .medium))
            .foregroundStyle(blueprint.palette.textMuted)
            .lineLimit(1)
            // The time button's box: a button's 8 at the sides.
            .padding(.horizontal, Space.s)
            .frame(minWidth: minTouch, minHeight: minTouch)
            .overlay {
                // 2026-10-08 §12: the secondary colour, so the badge reads as one against the page.
                RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.textMuted, lineWidth: blueprint.line)
            }
            .contentShape(Rectangle())
    }
}

/// docs/09 "Editing and speakers": the speaker menu — rename the speaker, or move this line to another one,
/// or to a new one.
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

/// docs/10 "Search" · docs/09 "Search": the find bar over the transcript — where this match is among them, and
/// the way to the one before and after it. Moving between matches scrolls the text, not the playhead.
///
/// With a [query] it carries its own field, as Windows' does: ⌘F on the Mac opens it on the transcript that is
/// open and puts the cursor there, every press ([focusRequest]).
public struct FindBar: View {
    private let index: Int
    private let count: Int
    private let query: Binding<String>?
    private let focusRequest: Int
    private let previous: () -> Void
    private let next: () -> Void
    private let close: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @FocusState private var fieldFocused: Bool

    /// - Parameter index: the current match, from 0.
    public init(
        index: Int, count: Int, query: Binding<String>? = nil, focusRequest: Int = 0,
        previous: @escaping () -> Void, next: @escaping () -> Void, close: @escaping () -> Void
    ) {
        self.index = index
        self.count = count
        self.query = query
        self.focusRequest = focusRequest
        self.previous = previous
        self.next = next
        self.close = close
    }

    public var body: some View {
        HStack(spacing: Space.xs) {
            if let query {
                let placeholder = RecKitStrings.localized("Search transcript")
                TextField("", text: query, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette))
                    .fieldPlaceholder(placeholder, empty: query.wrappedValue.isEmpty)
                    .textFieldStyle(.plain)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    .focused($fieldFocused)
                    .onSubmit(next)
                    .accessibilityLabel(Text(verbatim: placeholder))
                    .accessibilityIdentifier("find-field")
                    .frame(maxWidth: .infinity)
                    .task(id: focusRequest) { fieldFocused = true }
            }
            arrow("‹", label: "Previous match", action: previous)
            Text(verbatim: "\(count == 0 ? 0 : index + 1) / \(count)")
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("%1$@ of %2$@", "\(count == 0 ? 0 : index + 1)", "\(count)")))
            arrow("›", label: "Next match", action: next)
            if query == nil { Spacer(minLength: 0) }
            Button(action: close) {
                Text(verbatim: "×")
                    .font(blueprint.fonts.sans(TypeSize.body))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(width: minTouch, height: minTouch)
                    .contentShape(Rectangle())
                    .blueprintFocusRing()
            }
            .buttonStyle(BlueprintPressStyle())
            .focusEffectDisabled()
            .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close search")))
            .accessibilityIdentifier("find-close")
        }
        .padding(.horizontal, Space.s)
        .background(blueprint.palette.surface)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("find-bar")
    }

    private func arrow(_ glyph: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: glyph)
                .font(blueprint.fonts.sans(TypeSize.title))
                // docs/09 "Disabled, focus and structure": off, the `disabled` ink — never the grid's, which is decoration.
                .foregroundStyle(count > 1 ? blueprint.palette.accent : blueprint.palette.disabled)
                .frame(width: minTouch, height: minTouch)
                .contentShape(Rectangle())
                .blueprintFocusRing()
        }
        .buttonStyle(BlueprintPressStyle())
        .focusEffectDisabled()
        .disabled(count < 2)
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized(label)))
    }
}

/// docs/10 "Search" · docs/09 "Search": one recording a search found — when, the title with its matches in the
/// accent, up to two lines of transcript with the matches marked, the summary's line under `Summary · `, and
/// the moment of the first transcript match. The phone's list and the Mac's window list draw the same row.
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
            HStack(alignment: .top, spacing: Space.s) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: LedgerFormat.date(hit.startedAt))
                    Text(verbatim: LedgerFormat.time(hit.startedAt))
                }
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(width: LedgerMetrics.timeColumn, alignment: .leading)
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Text(Self.marked(title, ranges: hit.titleRanges, tint: blueprint.palette.accent, background: nil))
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.text)
                        .lineLimit(2)
                    ForEach(Array(hit.snippets.prefix(2).enumerated()), id: \.offset) { _, snippet in
                        let (text, ranges) = Self.around(snippet)
                        Text(Self.marked(text, ranges: ranges, tint: blueprint.palette.text, background: blueprint.palette.accent.opacity(0.16)))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                            .lineLimit(1)
                    }
                    if let summary = hit.summary {
                        let (text, ranges) = Self.summaryLine(summary)
                        Text(Self.marked(text, ranges: ranges, tint: blueprint.palette.text, background: blueprint.palette.accent.opacity(0.16)))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                            .lineLimit(1)
                            .accessibilityIdentifier("search-result-summary")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let first = hit.snippets.first {
                    // 2026-10-08 §3: a hit has no length beside it, so the time is shaped by itself.
                    Text(verbatim: LedgerFormat.elapsed(Int(first.atSec)))
                        .font(blueprint.fonts.monoBodySmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                }
            }
            // docs/09 "Component measurements": a ledger row's 12 top and bottom.
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.sm)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .blueprintFocusRing(radius: 0)
        }
        .buttonStyle(.plain)
        .focusEffectDisabled()
        .overlay(alignment: .bottom) { HairLine() }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("search-result")
    }

    private var title: String {
        hit.title?.isEmpty == false ? hit.title! : RecKitStrings.localized("Untitled")
    }

    /// A snippet cut to start a few words before its first match, so the match is on the one line shown.
    static func around(_ snippet: SearchSnippet) -> (String, [SearchRange]) {
        let text = snippet.text
        guard let first = snippet.ranges.first, first.offset > lead else { return (text, snippet.ranges) }
        let utf16 = Array(text.utf16)
        var start = Int(first.offset) - lead
        // Back to the start of a word, so the line does not open in the middle of one.
        while start > 0, utf16[start - 1] != 0x20 { start -= 1 }
        guard start > 0 else { return (text, snippet.ranges) }
        let rest = String(utf16CodeUnits: Array(utf16[start...]), count: utf16.count - start)
        let shift = Int32(start) - 1
        return ("…" + rest, snippet.ranges.map { SearchRange(offset: $0.offset - shift, length: $0.length) })
    }

    private static let lead = 24

    /// The summary's line as the row shows it: `Summary · ` and the line cut like a snippet, its ranges moved
    /// past the label.
    static func summaryLine(_ match: SummaryMatch) -> (String, [SearchRange]) {
        let (text, ranges) = around(SearchSnippet(atSec: 0, text: match.text, ranges: match.ranges))
        let label = RecKitStrings.localized("Summary") + " · "
        let shift = Int32(label.utf16.count)
        return (label + text, ranges.map { SearchRange(offset: $0.offset + shift, length: $0.length) })
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

extension SearchHit {
    /// docs/10 "Search": the hit is in the summary and nowhere else, so its recording opens on the summary.
    public var onlyInSummary: Bool { summary != nil && snippets.isEmpty && !matchesInTitle }
}

/// docs/08 "Exports" · docs/09 "Share / export" (phones): the Share sheet — one row per format, and Copy all.
/// A file row asks the core for the file, says `Preparing…` while it does (joining the audio takes a moment),
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
                    ForEach(model.shareFormats) { format in
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
        if format == .summary { return model.summaryExportReason }
        if format.needsTranscript, model.transcript == nil { return RecKitStrings.localized("No transcript yet") }
        if format == .audio { return model.audioExportReason }
        return nil
    }

    private func row(_ format: ShareFormat) -> some View {
        let reason = reason(format)
        return Button {
            share(format)
        } label: {
            HStack(spacing: Space.sm) {
                Image(systemName: format.glyph)
                    .font(blueprint.fonts.sans(TypeSize.body))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: Space.xxs) {
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
            HStack(spacing: Space.sm) {
                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                    .font(blueprint.fonts.sans(TypeSize.body))
                    .foregroundStyle(copied ? blueprint.palette.success : blueprint.palette.textMuted)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Text(verbatim: RecKitStrings.localized("Copy all"))
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(model.document == nil ? blueprint.palette.textMuted : blueprint.palette.text)
                    // 2026-10-08 §12: an item that is off says why, as the format rows above do.
                    if model.document == nil {
                        Text(verbatim: RecKitStrings.localized("No transcript yet"))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                }
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
