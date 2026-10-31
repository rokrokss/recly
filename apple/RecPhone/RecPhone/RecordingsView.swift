import ReclyCore
import RecKit
import SwiftUI
import UniformTypeIdentifiers

/// docs/09 screen principle 2, on the phone: the recordings are a ledger. One row per recording — when
/// (monospace), what with the transcript's first words under it, how long, and the state as a code — and a tap
/// opens the recording's page, which carries what can still be done about it (docs/13 I3 "list").
struct RecordingsView: View {
    @ObservedObject var model: RecordingModel
    @Environment(\.blueprint) private var blueprint
    /// docs/08 "Result files": the recording whose transcript is being read, as a page over the list —
    /// the ledger has no navigation stack to push onto (docs/09 screen principle 2).
    @State private var detail: RecordingDetailModel?
    /// A fix on the page that lands on another tab, run once the page has gone — a tab switched under the sheet would
    /// not be seen, and the privacy review is a sheet of its own that cannot come up while this one is still leaving.
    @State private var afterClose: (() -> Void)?
    /// docs/10 "Search": what is typed in the search field, and what the core found for it.
    @State private var query = ""
    @State private var hits: [SearchHit] = []
    /// docs/09 "Import": the system picker for audio and video files is up.
    @State private var importing = false
    @FocusState private var searchFocused: Bool

    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        list
            .task(id: query) {
                // docs/10 "Search": 200 ms after the last keystroke.
                let typed = query.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !typed.isEmpty else { hits = []; return }
                do { try await Task.sleep(for: .milliseconds(200)) } catch { return }
                hits = await model.search(typed)
            }
    }

    private var searching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var list: some View {
        VStack(spacing: 0) {
            // docs/09 screen principle 2: how many rows, and how many of them are waiting on something or
            // have stopped — the count on its own is a number with nothing to do (Recents.summary).
            ScreenHeader(title: loc("Recordings"), meta: Recents.summary(model.recents), trailingAlignment: .trailing) {
                // docs/09 "Import": a file from elsewhere — audio, or a video's sound — as a recording of
                // this phone.
                BlueprintIconButton("square.and.arrow.down", label: RecKitStrings.localized("Import audio")) { importing = true }
                    .disabled(!model.isReady)
                    .accessibilityIdentifier("import-audio")
            }
            // docs/09 "Search" · 2026-10-08 §12: under the title and its count line, above the ledger —
            // the order Android draws, and the Mac's Details window's own field.
            ListSearchField(text: $query, focus: $searchFocused)
            // docs/10 "iPhone": a banner at the top of the list — one row per
            // reason however many jobs are behind it, and the row is the way to the screen that
            // fixes it.
            AlertBanner(alerts: model.alerts, download: model.modelDownload) { model.fix($0) }
            if let message = model.message {
                dismissible(Banner(message.text, tone: model.messageFailed ? .danger : .warning)) { model.dismissMessage() }
                    .accessibilityIdentifier("message")
            }
            // docs/09 "Import": a failed import leaves no row, so the list says it here — and why.
            if let failure = model.importFailure {
                dismissible(Banner("\(RecKitStrings.localized("Could not import this file")) — \(CoreMessages.text(failure).sentence)", tone: .danger)) {
                    model.dismissImportFailure()
                }
                .accessibilityIdentifier("import-failure")
            }
            if searching {
                results
            } else {
                ledger
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dotGridBackground()
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio, .movie], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { model.importFiles(urls) }
        }
    }

    /// A banner that goes when it is tapped: a button, so VoiceOver and a keyboard reach it too, with the dismissal
    /// named as its action (docs/09 "Accessibility").
    private func dismissible(_ banner: Banner, dismiss: @escaping () -> Void) -> some View {
        Button(action: dismiss) { banner.blueprintFocusRing() }
            .buttonStyle(.plain)
            .focusEffectDisabled()
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.s)
            .accessibilityAction(named: Text(verbatim: RecKitStrings.localized("Close")), dismiss)
    }

    /// docs/10 "Search" · docs/09 "Search": the rows the search found, in place of the ledger while the field
    /// has text; a row opens the detail on its first match.
    private var results: some View {
        VStack(spacing: 0) {
            HairLine()
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(hits, id: \.recordingId) { hit in
                        SearchResultRow(hit: hit) {
                            detail = model.detail(for: hit, query: query.trimmingCharacters(in: .whitespacesAndNewlines))
                        }
                    }
                    if hits.isEmpty {
                        EmptyListMessage(
                            title: RecKitStrings.localized("No recordings match"),
                            hint: RecKitStrings.localized("Search looks in titles and in transcripts on this device.")
                        )
                    }
                }
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .sheet(item: $detail, onDismiss: runAfterClose) { detailPage($0) }
    }

    private var ledger: some View {
        VStack(spacing: 0) {
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
                        // docs/09 screen principle 8: no button here — the tab bar right below already says
                        // Record.
                        EmptyListMessage(
                            title: loc(model.recentsLoading ? "Loading…" : "No recordings yet"),
                            hint: model.recentsLoading ? nil : loc("Recordings you make appear here.")
                        )
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
            .scrollDismissesKeyboard(.immediately)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // 2026-10-08 badge column rule: the status column no wider than the old codes took, so a
        // phone's title column keeps its width; a longer word wraps inside its badge.
        .environment(\.ledgerStatusCapped, true)
        .dotGridBackground()
        .task { await model.refreshRecents() }
        // docs/03: and beside it, never in front of it — the list is drawn from what is already
        // here, and a row another device uploaded arrives on the recordings observation.
        .task { await model.pullRemoteRecordings() }
        .sheet(item: $detail, onDismiss: runAfterClose) { detailPage($0) }
    }

    /// docs/09 screen principle 2 (2026-10-28): the page a row or a search hit opens, with what the open row used to
    /// carry under its notice and the delete question its More menu asks drawn over it.
    private func detailPage(_ detail: RecordingDetailModel) -> some View {
        RecordingDetailView(model: detail, fixes: fixes(for: detail.recordingId)) { self.detail = nil }
            // docs/03 "Deleting in the app": one recording, two answers about Drive, and the default is the one
            // that can be undone. On the page, as a sheet over it — the list under the page cannot present one.
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
                    // The page was about the recording that is going; the list says how it went.
                    self.detail = nil
                } cancel: {
                    model.cancelDelete()
                }
            }
    }

    private func runAfterClose() {
        afterClose?()
        afterClose = nil
    }

    /// Closes the page and does [action] once it has gone.
    private func closeThen(_ action: @escaping () -> Void) {
        afterClose = action
        detail = nil
    }

    private func row(_ item: RecentItem) -> some View {
        let length = LedgerFormat.length(item.durationSec)
        return LedgerRow(
            date: LedgerFormat.date(item.startedAt),
            time: LedgerFormat.time(item.startedAt),
            title: item.titleLabel,
            // 2026-10-08 §8: the transcript's first words under the title, when this phone has them.
            subtitle: item.preview ?? "",
            length: length,
            status: item.badge,
            announce: LedgerFormat.announce(
                title: item.titleLabel,
                preview: item.preview,
                at: LedgerFormat.startedAt(item.startedAt),
                length: length,
                state: item.spokenState
            ),
            // docs/08 "Result files": the transcript of this recording, the local copy first and Drive after
            // (`RecordingDetailModel`).
            action: { detail = model.detail(for: item) }
        )
        .accessibilityIdentifier("open-detail")
    }

    /// The fixes for the open page's recording, read off its row as it is now — so they follow the job while the page
    /// is up — or nil when the row has nothing to say or to fix: the Mac's open-row strip (`RecordingsWindow`), on the
    /// page. A search hit older than the loaded page has no row, and its page shows the notice alone.
    private func fixes(for recordingId: String) -> DetailFixes? {
        guard let item = model.recents.first(where: { $0.id == recordingId }) else { return nil }
        // The reason, or while the model downloads how far it is ([ModelDownloadLine]).
        let reason = item.alert != .needsAuth && item.reason != nil
        guard reason || item.canRetry || item.needsKey || item.alert == .needsSpace || item.alert == .needsConsent
            || item.waitingForModel || item.waitingMinutes != nil
        else { return nil }
        return DetailFixes(saysWhy: reason) { fixStrip(item) }
    }

    private func fixStrip(_ item: RecentItem) -> some View {
        VStack(spacing: Space.s) {
            // docs/08 "Polling · status": a transcription in flight has no "when", only how long it has
            // been waiting — the badge's RETRY would otherwise read as "stuck".
            if item.waitingMinutes != nil {
                Text(verbatim: item.stateLabel)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            // docs/07 §5: what the core last said about this job, with its diagnostic under it —
            // the sentence translated, the diagnostic never. For a docs/08 "Errors" the sentence is
            // what to do next and the diagnostic is the provider's own words. Red for a failure,
            // the badge's warning tone for a job that is only waiting.
            if item.alert != .needsAuth {
                RowReason(item: item, download: model.modelDownload)
            }
            if item.waitingForModel, let download = model.modelDownload {
                ModelDownloadLine(download: download)
            }
            // docs/09 "Accessibility" · Fluid typography: on a narrow phone, or at an accessibility size, the buttons
            // wrap onto further lines — a label cut to a syllable says nothing.
            FlowLayout {
                // docs/05 "Fixed processing settings": the model this recording waits for, in its own
                // language, first — and nothing while the download runs (the banner has it).
                if item.waitingForModel, let download = model.modelDownload {
                    ModelDownloadButton(download: download, language: item.localLanguage)
                        .accessibilityIdentifier("download-model")
                }
                // docs/10 "Drive out of space": nothing here retries on its own, and the only thing that
                // changes the answer is on Google's storage page.
                if item.alert == .needsSpace {
                    BlueprintButton(loc("Open Drive storage")) { model.openDriveStorage() }
                        .accessibilityIdentifier("open-storage")
                }
                if item.alert == .needsConsent {
                    BlueprintButton(RecKitStrings.localized("Review transfers")) {
                        closeThen { model.fix(JobAlert(reason: .needsConsent, count: 1)) }
                    }
                    .accessibilityIdentifier("review-transfers")
                }
                // docs/10: a retry is for a job that has stopped. One that is waiting out a backoff
                // comes back on its own `next_run_at`, and there is nothing to ask for.
                if item.canRetry {
                    ProcessingButton(loc("Retry"), state: model.action) { model.retry(item) }
                }
                // docs/08 AUTH_REJECTED: the key is entered in the recording processing settings, so
                // that is where "check the key" lands — which on a phone means the settings tab.
                if item.needsKey {
                    BlueprintButton(RecordingDetailStrings.checkKey) { closeThen { model.showProcessingSettings() } }
                        .accessibilityIdentifier("check-key")
                }
            }
        }
        .multilineTextAlignment(.center)
    }
}

/// While the model downloads, the reason and Download model step aside — on the list the banner carries the download —
/// so the page says how far it is instead.
private struct ModelDownloadLine: View {
    @ObservedObject var download: ModelDownload
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        if download.downloading {
            Text(verbatim: ModelDownload.progressText(download.progress))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
        }
    }
}
