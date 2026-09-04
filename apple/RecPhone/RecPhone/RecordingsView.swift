import ReclyCore
import RecKit
import SwiftUI

/// docs/09 화면 원칙 2, on the phone: the recordings are a ledger. One row per recording — when
/// (monospace), what, how long, and the state as a code — and the detail is behind the row rather
/// than in front of it: what the core last said, and the two or three things that can still be done
/// about it (docs/13 I3 "목록").
struct RecordingsView: View {
    @ObservedObject var model: RecordingModel
    @Environment(\.blueprint) private var blueprint
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var expanded: String?
    /// docs/08 "결과 파일": the recording whose transcript is being read, as a page over the list —
    /// the ledger has no navigation stack to push onto (docs/09 화면 원칙 2).
    @State private var detail: RecordingDetailModel?

    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            // docs/09 화면 원칙 2: how many rows, and how many of them are waiting on something or
            // have stopped — the count on its own is a number with nothing to do (Recents.summary).
            ScreenHeader(title: loc("Recordings"), meta: Recents.summary(model.recents))
            // docs/10 "iPhone": 목록 상단 배너 — the same lines the notifications carry, one row per
            // reason however many jobs are behind it, and the row is the way to the screen that
            // fixes it.
            AlertBanner(alerts: model.alerts) { model.fix($0) }
            if let message = model.message {
                Banner(message.text, tone: .warning)
                    .padding(.horizontal, Space.m)
                    .padding(.bottom, Space.s)
                    .onTapGesture { model.dismissMessage() }
                    .accessibilityIdentifier("message")
            }
            LedgerHeader(
                time: loc("Time"),
                title: loc("Title"),
                length: loc("Length"),
                status: loc("Status")
            )
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(model.recents) { item in
                        row(item)
                    }
                    if model.recents.isEmpty {
                        Text("No recordings yet")
                            .font(blueprint.fonts.bodySmall)
                            .foregroundStyle(blueprint.palette.textMuted)
                            .padding(Space.l)
                    }
                }
            }
            // docs/03: a pull-to-refresh is the user asking for everything, this device's list and
            // what the other devices have put in Drive — so the pull is awaited here and the list
            // is read after it, or the gesture would end before its own answer arrived.
            .refreshable {
                await model.pullRemoteRecordings()
                await model.refreshRecents()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dotGridBackground()
        .task { await model.refreshRecents() }
        // docs/03: and beside it, never in front of it — the list is drawn from what is already
        // here, and a row another device uploaded arrives on the recordings observation.
        .task { await model.pullRemoteRecordings() }
        .sheet(item: $detail) { detail in
            RecordingDetailView(model: detail) { self.detail = nil }
        }
        // docs/03 "앱에서 지우기": one recording, two answers about Drive, and the default is the one
        // that can be undone.
        .blueprintDialog(
            item: Binding(
                get: { model.deleteRequest },
                // A sheet only ever writes nil back, and a dismissal is a cancel like any other —
                // which is what invalidates a count still being read for this question.
                set: { if $0 == nil { model.cancelDelete() } }
            )
        ) { request in
            DeleteDialog(request: request, device: .phone) {
                model.delete($0, deleteDrive: $1)
            } cancel: {
                model.cancelDelete()
            }
        }
    }

    @ViewBuilder
    private func row(_ item: RecentItem) -> some View {
        let length = LedgerFormat.length(item.durationSec)
        LedgerRow(
            date: LedgerFormat.date(item.startedAt),
            time: LedgerFormat.time(item.startedAt),
            title: item.titleLabel,
            subtitle: item.id,
            length: length,
            status: item.badge,
            announce: LedgerFormat.announce(
                title: item.titleLabel,
                at: LedgerFormat.startedAt(item.startedAt),
                length: length,
                state: item.stateLabel
            ),
            // docs/09 "접근성": the row opens what is behind it, which a screen reader would
            // otherwise only find out by tapping.
            expanded: expanded == item.id,
            // docs/09 "모션": 200 ms ease-in-out, and nothing at all with reduce motion on — the
            // row simply is open.
            action: {
                withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) {
                    expanded = expanded == item.id ? nil : item.id
                }
            }
        )
        .accessibilityIdentifier("state")
        if expanded == item.id {
            expansion(item)
        }
    }

    /// docs/09 화면 원칙 2: what is behind the row — where the recording stands, and the two or three
    /// things the user can do about it.
    private func expansion(_ item: RecentItem) -> some View {
        VStack(alignment: .leading, spacing: Space.s) {
            // docs/08 "폴링 · 상태": a transcription in flight has no "when", only how long it has
            // been waiting — the badge's RETRY would otherwise read as "stuck".
            if item.waitingMinutes != nil {
                Text(verbatim: item.stateLabel)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
            }

            // docs/07 §5: what the core last said about this job, with its diagnostic under it —
            // the sentence translated, the diagnostic never. For a docs/08 "오류" the sentence is
            // what to do next and the diagnostic is the provider's own words.
            if let reason = item.reason {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: reason.sentence)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.danger)
                    if let detail = reason.detail {
                        Text(verbatim: detail)
                            .font(blueprint.fonts.monoSmall)
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                }
            }

            // docs/09 "접근성" · 유동 타이포: several buttons across is a layout for ordinary type
            // sizes. On a narrow phone, or at an accessibility size, the same ones wrap onto
            // further lines — a label cut to a syllable says nothing, and a column is not what the
            // chips elsewhere do.
            actions(item)
        }
        // The row's own time column is what the detail is indented past; at an accessibility size
        // that column is no longer where the eye is, and the width matters more than the alignment.
        .padding(.leading, typeSize.isAccessibilitySize ? Space.m : 78)
        .padding(.trailing, Space.m)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(blueprint.palette.background)
    }

    /// The things that can still be done about this recording, across the row and onto a second
    /// line when they do not fit.
    private func actions(_ item: RecentItem) -> some View {
        FlowLayout {
            if item.link != nil {
                BlueprintButton(loc("Open in Drive")) { model.openInDrive(item) }
            }
            // docs/10 "Drive 용량 초과": nothing here retries on its own, and the only thing that
            // changes the answer is on Google's storage page.
            if item.alert == .needsSpace {
                BlueprintButton(loc("Open Drive storage")) { model.openDriveStorage() }
                    .accessibilityIdentifier("open-storage")
            }
            // docs/10: the sign-in is the whole of what this job is waiting for, so it is offered
            // where the job is as well as on the banner — and lands on the same tab the banner
            // takes it to.
            if item.alert == .needsAuth {
                BlueprintButton(loc("Sign in")) {
                    model.fix(JobAlert(reason: .needsAuth, count: 1))
                }
                .accessibilityIdentifier("sign-in")
            }
            // docs/10: a retry is for a job that has stopped. One that is waiting out a backoff
            // comes back on its own `next_run_at`, and there is nothing to ask for.
            if item.canRetry {
                ProcessingButton(loc("Retry"), state: model.action) { model.retry(item) }
            }
            // docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where "check
            // the key" lands — which on a phone means the workflow tab, not an editor behind the
            // list.
            if item.needsKey {
                BlueprintButton(RecordingDetailStrings.checkKey) { model.editWorkflow(of: item) }
                    .accessibilityIdentifier("check-key")
            }
            // docs/08 "결과 파일": the transcript of this recording, the local copy first and Drive
            // after (`RecordingDetailModel`).
            BlueprintButton(RecordingDetailStrings.open) { detail = model.detail(for: item) }
                .accessibilityIdentifier("open-detail")
            // docs/03: a recording being written to or uploaded right now is not one to delete —
            // the core refuses it anyway, and offering the button would be offering a refusal.
            if item.state != "Recording", item.state != "Uploading" {
                BlueprintButton(loc("Delete"), tone: .danger) { model.confirmDelete(item) }
                    .accessibilityIdentifier("delete")
            }
        }
    }
}
