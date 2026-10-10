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

/// docs/09 "Summary view": a summary or an answer as selectable plain text, every `[HH:MM:SS]` in it a link that
/// plays the recording from there — the seek a transcript time button makes. A link inside the one `Text`,
/// rather than a button beside it, is what keeps the whole text selectable (SwiftUI selects within one
/// `Text`), so a citation's target is its own glyphs rather than a 44pt box, and a screen reader reaches each
/// one as a named action, `Play from 00:12:34`. Mono and accent, never underlined: a dotted underline means
/// a web page (docs/09, 2026-10-09).
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
        Text(attributed(runs, playable: playable))
            .font(blueprint.fonts.body)
            .foregroundStyle(blueprint.palette.text)
            .tint(blueprint.palette.accent)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .environment(\.openURL, OpenURLAction { url in
                guard let sec = Self.second(url) else { return .systemAction }
                onSeek?(sec)
                return .handled
            })
            .accessibilityActions {
                ForEach(Array(playable.enumerated()), id: \.offset) { _, citation in
                    Button(ChatGptText.playFrom(citation.text)) { onSeek?(citation.atSec) }
                }
            }
    }

    /// The citations a tap can play from: inside what the recording here holds.
    static func playable(_ runs: [CitationRun], seekableSec: Double) -> [(text: String, atSec: Double)] {
        runs.compactMap { run in
            guard case .citation(let text, let atSec) = run, atSec < seekableSec else { return nil }
            return (text, atSec)
        }
    }

    private func attributed(_ runs: [CitationRun], playable: [(text: String, atSec: Double)]) -> AttributedString {
        var out = AttributedString()
        for run in runs {
            switch run {
            case .text(let words):
                out += AttributedString(words)
            case .citation(let words, let atSec):
                var part = AttributedString(words)
                part.font = blueprint.fonts.monoBody
                if playable.contains(where: { $0.atSec == atSec }) {
                    part.link = Self.url(atSec)
                    part.foregroundColor = blueprint.palette.accent
                } else {
                    part.foregroundColor = blueprint.palette.textMuted
                }
                out += part
            }
        }
        return out
    }

    private static let scheme = "recly-seek"

    static func url(_ atSec: Double) -> URL? { URL(string: "\(scheme):\(atSec)") }

    static func second(_ url: URL) -> Double? {
        guard url.scheme == scheme else { return nil }
        return Double(url.absoluteString.dropFirst(scheme.count + 1))
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
