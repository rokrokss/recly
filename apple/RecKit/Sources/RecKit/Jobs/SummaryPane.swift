#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "Summary view": the meeting notes ChatGPT wrote from this recording's transcript, as plain text —
/// no shell renders Markdown — with the model that wrote them and Copy all under them. While a new one is
/// written, or after one failed, the last one stays readable under the line that says so.
struct SummaryPane: View {
    @ObservedObject var model: RecordingDetailModel
    /// Summarize again, from the failure notice.
    let retry: () -> Void
    /// How far the recording here can be played, and the seek a citation makes — nil while it cannot be
    /// played from a point (see [CitedText]).
    let seekableSec: Double
    let onSeek: ((Double) -> Void)?
    @State private var copied = false
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                switch onEnum(of: model.summary) {
                case .none:
                    if model.summarizing { running }
                case .running(let run):
                    running
                    if let previous = run.previous { text(previous) }
                case .ready(let ready):
                    text(ready.summary)
                    footer(ready.summary)
                case .failed(let failed):
                    notice(failed.reason)
                    if let previous = failed.previous { text(previous) }
                }
            }
            .padding(Space.m)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityIdentifier("summary-pane")
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(3))
            copied = false
        }
    }

    private var running: some View {
        LoadingText(text: loc("Summarizing…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
            .accessibilityIdentifier("summary-running")
    }

    private func text(_ summary: Summary) -> some View {
        CitedText(text: summary.text, seekableSec: seekableSec, onSeek: onSeek)
            .accessibilityIdentifier("summary-text")
    }

    private func footer(_ summary: Summary) -> some View {
        HStack(spacing: Space.s) {
            Text(verbatim: ChatGptText.summaryFooter(summary, connection: model.chatGpt))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            BlueprintButton(
                copied ? loc("Copied") : loc("Copy all"),
                tone: .quiet,
                leading: copied ? BlueprintChip.selectionMark : nil
            ) {
                model.copySummary()
                copied = true
            }
            .disabled(copied)
            .accessibilityIdentifier("summary-copy")
        }
    }

    private func notice(_ reason: String) -> some View {
        ChatGptFailureNotice(reason: reason, failed: loc("Could not summarize"), retrying: model.summarizing, retry: retry)
            .accessibilityIdentifier("summary-failed")
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// One centred notice and at most one way out of it (docs/09 screen principle 8): a summary's failure, and an
/// answer's (docs/09 "Ask").
struct ChatGptFailureNotice: View {
    let reason: String
    /// What it says when the code has no sentence the user can act on — `Could not summarize`.
    let failed: String
    /// A run already going again: Retry waits for it.
    let retrying: Bool
    let retry: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    var body: some View {
        let sentence = ChatGptText.sentence(reason)
        let message = ChatGptText.message(reason)
        VStack(spacing: Space.s) {
            // A sentence that says what to do is something to attend to; anything else failed.
            Text(verbatim: sentence ?? failed)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(sentence == nil ? blueprint.palette.danger : BadgeTone.warning.ink(blueprint.palette))
                .multilineTextAlignment(.center)
            if sentence == nil, let detail = ChatGptText.detail(reason) {
                Text(verbatim: detail)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .multilineTextAlignment(.center)
            }
            if message == .chatgptUsageLimit {
                BlueprintButton(RecKitStrings.localized("Manage usage"), tone: .primary) { openURL(ChatGptSettingsModel.usage) }
            } else if message != .chatgptSignInRequired {
                BlueprintButton(RecKitStrings.localized("Retry"), tone: .quiet, action: retry)
                    .disabled(retrying)
                    .accessibilityIdentifier("summary-retry")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.m)
    }
}

/// docs/09 "Summary view": a summary or an answer as selectable plain text, every `[HH:MM:SS]` in it playing the
/// recording from there — the seek a transcript time button makes. Mono and accent, never underlined: a dotted
/// underline means a web page (docs/09, 2026-10-09).
///
/// The text stays one `Text`, because SwiftUI selects within one `Text`, so a citation cannot be a button beside
/// it. From iOS 18 / macOS 15 its target is laid over it instead: at least [minTouch] each way, centred on the
/// citation, placed from the text's own line layout ([CitationTarget]), and a screen reader reaches each citation
/// as its own `Play from 00:12:34` button. A press or a drag anywhere else selects as before. On iOS 17 / macOS 14
/// a citation is only a link inside the text: its target is its own glyphs, and a screen reader reaches it as a
/// named action on the text.
struct CitedText: View {
    let text: String
    /// How far the recording here can be played: a citation past it is plain text, as a time button past it
    /// is off.
    let seekableSec: Double
    /// Nil while the recording cannot be played from a point (no audio here, still being written).
    let onSeek: ((Double) -> Void)?
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        let runs = ChatGptText.runs(text)
        let playable = Self.playable(runs, seekableSec: onSeek == nil ? 0 : seekableSec)
        Group {
            if #available(iOS 18, macOS 15, *) {
                selectable(marked(runs, playable: playable))
                    .accessibilitySortPriority(1)
                    .overlayPreferenceValue(Text.LayoutKey.self) { layouts in
                        CitationTargets(layouts: layouts, playable: playable) { onSeek?($0) }
                    }
                    .accessibilityElement(children: .contain)
            } else {
                selectable(Text(attributed(runs, playable: playable)))
                    .accessibilityActions {
                        ForEach(Array(playable.enumerated()), id: \.offset) { _, citation in
                            Button(ChatGptText.playFrom(citation.text)) { onSeek?(citation.atSec) }
                        }
                    }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // The links stay under the targets on iOS 18 / macOS 15 too: a tap that reaches the glyphs still plays.
        .environment(\.openURL, OpenURLAction { url in
            guard let sec = Self.second(url) else { return .systemAction }
            onSeek?(sec)
            return .handled
        })
    }

    private func selectable(_ text: Text) -> some View {
        text
            .font(blueprint.fonts.body)
            .foregroundStyle(blueprint.palette.text)
            .tint(blueprint.palette.accent)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// A citation a tap can play from, by its place among the text's runs.
    typealias Playable = (run: Int, text: String, atSec: Double)

    /// The citations a tap can play from: inside what the recording here holds.
    static func playable(_ runs: [CitationRun], seekableSec: Double) -> [Playable] {
        runs.enumerated().compactMap { index, run in
            guard case .citation(let text, let atSec) = run, atSec < seekableSec else { return nil }
            return (index, text, atSec)
        }
    }

    private func attributed(_ runs: [CitationRun], playable: [Playable]) -> AttributedString {
        runs.indices.reduce(into: AttributedString()) { out, index in
            out += part(runs[index], playable: playable.contains { $0.run == index })
        }
    }

    /// The same text as [attributed], each playable citation in it marked with its run so the layout can say
    /// where it is.
    @available(iOS 18, macOS 15, *)
    private func marked(_ runs: [CitationRun], playable: [Playable]) -> Text {
        runs.indices.reduce(Text(verbatim: "")) { out, index in
            let plays = playable.contains { $0.run == index }
            let text = Text(part(runs[index], playable: plays))
            return out + (plays ? text.customAttribute(CitationMark(run: index)) : text)
        }
    }

    private func part(_ run: CitationRun, playable: Bool) -> AttributedString {
        switch run {
        case .text(let words):
            return AttributedString(words)
        case .citation(let words, let atSec):
            var part = AttributedString(words)
            part.font = blueprint.fonts.monoBody
            if playable {
                part.link = Self.url(atSec)
                part.foregroundColor = blueprint.palette.accent
            } else {
                part.foregroundColor = blueprint.palette.textMuted
            }
            return part
        }
    }

    private static let scheme = "recly-seek"

    static func url(_ atSec: Double) -> URL? { URL(string: "\(scheme):\(atSec)") }

    static func second(_ url: URL) -> Double? {
        guard url.scheme == scheme else { return nil }
        return Double(url.absoluteString.dropFirst(scheme.count + 1))
    }
}

/// Which of a [CitedText]'s runs a stretch of its laid-out text is.
private struct CitationMark: TextAttribute {
    let run: Int
}

/// docs/09 "Summary view": a citation's target — its glyphs on one line of the text, and around them the box a
/// tap or a click plays it from.
struct CitationTarget: Equatable {
    /// The citation's place among the text's runs.
    let run: Int
    let glyphs: CGRect

    /// At least [minTouch] each way, centred on the glyphs, so it reaches over the lines above and below: where it
    /// meets a neighbour's, [hit] decides.
    var area: CGRect {
        glyphs.insetBy(dx: min(0, (glyphs.width - minTouch) / 2), dy: min(0, (glyphs.height - minTouch) / 2))
    }

    /// One target for each line a citation sits on: the pieces of it the layout lays out on one line — right to
    /// left text cuts `[` and `]` off the digits — are one box.
    static func targets(_ pieces: [(run: Int, line: Int, glyphs: CGRect)]) -> [CitationTarget] {
        var lines: [(run: Int, line: Int)] = []
        var boxes: [CGRect] = []
        for piece in pieces {
            if let at = lines.firstIndex(where: { $0 == (piece.run, piece.line) }) {
                boxes[at] = boxes[at].union(piece.glyphs)
            } else {
                lines.append((piece.run, piece.line))
                boxes.append(piece.glyphs)
            }
        }
        return zip(lines, boxes).map { CitationTarget(run: $0.run, glyphs: $1) }
    }

    /// What a tap at [point] plays: of the targets whose area holds it, the one whose glyphs are nearest — so a
    /// tap on a citation's own glyphs plays that citation even where the next line's target reaches over it.
    static func hit(_ point: CGPoint, in targets: [CitationTarget]) -> CitationTarget? {
        targets.filter { $0.area.contains(point) }.min { $0.distance(point) < $1.distance(point) }
    }

    private func distance(_ point: CGPoint) -> CGFloat {
        hypot(max(glyphs.minX - point.x, 0, point.x - glyphs.maxX), max(glyphs.minY - point.y, 0, point.y - glyphs.maxY))
    }
}

/// The targets over a [CitedText] (iOS 18 / macOS 15), placed by the text's layout. One layer takes a tap or a
/// click anywhere in a target and plays what [CitationTarget.hit] picks; each citation is its own `Play from`
/// button for a screen reader. Nothing here draws.
@available(iOS 18, macOS 15, *)
private struct CitationTargets: View {
    let layouts: Text.LayoutKey.Value
    let playable: [CitedText.Playable]
    let onSeek: (Double) -> Void

    var body: some View {
        GeometryReader { proxy in
            let targets = CitationTarget.targets(pieces(proxy))
            ZStack {
                Color.clear
                    .contentShape(Areas(rects: targets.map(\.area)))
                    .onTapGesture(coordinateSpace: .local) { point in
                        if let target = CitationTarget.hit(point, in: targets) { seek(target.run) }
                    }
                    #if os(macOS)
                    .pointerStyle(.link)
                    #endif
                    .accessibilityHidden(true)
                ForEach(Array(targets.enumerated()), id: \.offset) { index, target in
                    // A citation the layout cut over two lines is one button, on its first line.
                    if let citation = playable.first(where: { $0.run == target.run }),
                       !targets[..<index].contains(where: { $0.run == target.run }) {
                        Color.clear
                            .frame(width: target.area.width, height: target.area.height)
                            .position(x: target.area.midX, y: target.area.midY)
                            .accessibilityElement()
                            .accessibilityLabel(Text(verbatim: ChatGptText.playFrom(citation.text)))
                            .accessibilityAddTraits(.isButton)
                            .accessibilityAction { onSeek(citation.atSec) }
                    }
                }
            }
        }
        // The layout gives its runs from the left in either direction; right to left, `position` would mirror the
        // buttons away from their citations.
        .environment(\.layoutDirection, .leftToRight)
    }

    private func pieces(_ proxy: GeometryProxy) -> [(run: Int, line: Int, glyphs: CGRect)] {
        var pieces: [(run: Int, line: Int, glyphs: CGRect)] = []
        var lineIndex = 0
        for anchored in layouts {
            let origin = proxy[anchored.origin]
            for line in anchored.layout {
                for run in line {
                    guard let mark = run[CitationMark.self] else { continue }
                    pieces.append((mark.run, lineIndex, run.typographicBounds.rect.offsetBy(dx: origin.x, dy: origin.y)))
                }
                lineIndex += 1
            }
        }
        return pieces
    }

    private func seek(_ run: Int) {
        if let citation = playable.first(where: { $0.run == run }) { onSeek(citation.atSec) }
    }

    private struct Areas: Shape {
        let rects: [CGRect]

        func path(in _: CGRect) -> Path { Path { $0.addRects(rects) } }
    }
}

/// docs/09 "Summary view": the summary as the user rewrites it — the whole text in one field.
struct SummaryDraft: Equatable {
    let original: String
    var text: String

    init(_ summary: Summary) {
        original = summary.text
        text = summary.text
    }

    /// An empty or unchanged text is not saved (docs/08 "Summaries"), so neither is a change to keep.
    var changed: Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed != original.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// docs/09 "Summary view": edit mode stands in for the summary the way the transcript's editor stands in for
/// the transcript — one plain field holding the whole text, the note on where a save goes under it, and the
/// phone's Cancel · Save under that, above the keyboard.
struct SummaryEditor: View {
    @Binding var text: String
    let changed: Bool
    /// The recording's storage is this device's own (or none yet): the edit does not reach other devices.
    let staysHere: Bool
    let saving: Bool
    let cancel: () -> Void
    let save: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: Space.s) {
                // A text view, not a field: Return is a new line here, on the Mac too.
                TextEditor(text: $text)
                    .scrollContentBackground(.hidden)
                    .font(blueprint.fonts.body)
                    .foregroundStyle(blueprint.palette.text)
                    // A field's 10 × 9, less the inset the text view keeps of its own on each platform.
                    .padding(.horizontal, 5)
                    .padding(.vertical, Self.verticalInset)
                    .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
                    .overlay {
                        RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
                    }
                    .accessibilityLabel(Text(verbatim: loc("Summary")))
                    .accessibilityIdentifier("summary-edit-text")
                Text(verbatim: loc(staysHere
                    ? "Saving keeps the summary on this device."
                    : "Saving updates the summary in your storage, and your other devices show it."))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(Space.m)
            #if os(iOS)
            HairLine()
            HStack(spacing: Space.s) {
                Spacer(minLength: 0)
                EditorButtons(changed: changed, saving: saving, cancel: cancel, save: save)
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
            .background(blueprint.palette.surface)
            #endif
        }
        #if os(iOS)
        // 2026-10-08 §9: changes leave only through Cancel and its `Discard your changes?`.
        .interactiveDismissDisabled(changed)
        #endif
    }

    #if os(iOS)
    private static let verticalInset: CGFloat = 1
    #else
    private static let verticalInset: CGFloat = 9
    #endif

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
