import SwiftUI

/// docs/07 §5: what the core last said about a ledger row's job, under the row — the sentence
/// translated, and the diagnostic under it never. Red for a failure; the badge's warning tone for a
/// job that is only waiting ([RecentItem.reasonTone]). For a recording waiting for the speech model
/// it says nothing while the one download runs: the banner carries the progress then, and "not
/// downloaded yet" beside it would contradict it (docs/05 "Fixed processing settings").
public struct RowReason: View {
    private let item: RecentItem
    private let download: ModelDownload?
    private let showsDetail: Bool

    public init(item: RecentItem, download: ModelDownload?, showsDetail: Bool = true) {
        self.item = item
        self.download = download
        self.showsDetail = showsDetail
    }

    public var body: some View {
        if item.waitingForModel, let download {
            HiddenWhileDownloading(download: download) { sentence }
        } else {
            sentence
        }
    }

    @ViewBuilder private var sentence: some View {
        if let reason = item.reason {
            ReasonText(reason: reason, tone: item.reasonTone, showsDetail: showsDetail)
        }
    }
}

private struct ReasonText: View {
    @Environment(\.blueprint) private var blueprint
    let reason: CoreMessages.Text
    let tone: BadgeTone
    let showsDetail: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: reason.sentence)
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(tone.ink(blueprint.palette))
            if showsDetail, let detail = reason.detail {
                Text(verbatim: detail)
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
        }
    }
}

private struct HiddenWhileDownloading<Content: View>: View {
    @ObservedObject var download: ModelDownload
    @ViewBuilder let content: () -> Content

    var body: some View {
        if !download.downloading { content() }
    }
}
