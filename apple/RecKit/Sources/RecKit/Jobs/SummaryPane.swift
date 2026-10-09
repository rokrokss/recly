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
    @State private var copied = false
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

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
        Text(verbatim: summary.text)
            .font(blueprint.fonts.body)
            .foregroundStyle(blueprint.palette.text)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("summary-text")
    }

    private func footer(_ summary: Summary) -> some View {
        HStack(spacing: Space.s) {
            Text(verbatim: RecKitStrings.localized("ChatGPT · %@", ChatGptText.modelLabel(summary.model, connection: model.chatGpt)))
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

    /// One centred notice and at most one way out of it (docs/09 screen principle 8).
    private func notice(_ reason: String) -> some View {
        let sentence = ChatGptText.sentence(reason)
        let message = ChatGptText.message(reason)
        return VStack(spacing: Space.s) {
            // A sentence that says what to do is something to attend to; anything else failed.
            Text(verbatim: sentence ?? loc("Could not summarize"))
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
                BlueprintButton(loc("Manage usage"), tone: .primary) { openURL(ChatGptSettingsModel.usage) }
            } else if message != .chatgptSignInRequired {
                BlueprintButton(loc("Retry"), tone: .quiet, action: retry)
                    .disabled(model.summarizing)
                    .accessibilityIdentifier("summary-retry")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.m)
        .accessibilityIdentifier("summary-failed")
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
