import ReclyCore
import RecKit
import SwiftUI
import UniformTypeIdentifiers

/// docs/09 screen principle 2, on the phone: the recordings are a ledger. One row per recording — when
/// (monospace), what, how long, and the state as a code — and the detail is behind the row rather
/// than in front of it: what the core last said, and the two or three things that can still be done
/// about it (docs/13 I3 "list").
struct RecordingsView: View {
    @ObservedObject var model: RecordingModel
    /// Changes whenever the List tab is tapped: the ledger goes back to every row closed.
    var collapse = 0
    @Environment(\.blueprint) private var blueprint
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var expanded: String?
    /// docs/08 "Result files": the recording whose transcript is being read, as a page over the list —
    /// the ledger has no navigation stack to push onto (docs/09 screen principle 2).
    @State private var detail: RecordingDetailModel?
    /// docs/10 "Search": what is typed in the search field, and what the core found for it.
    @State private var query = ""
    @State private var hits: [SearchHit] = []
    /// docs/09 "Import": the system picker for audio and video files is up.
    @State private var importing = false

    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    var body: some View {
        // docs/09 "Search": the search field is the platform's own (`.searchable`), and it lives in a
        // navigation bar — so the list has one, holding nothing but the field.
        NavigationStack {
            list
                .navigationBarTitleDisplayMode(.inline)
                .searchable(
                    text: $query,
                    placement: .navigationBarDrawer(displayMode: .always),
                    prompt: Text(verbatim: RecKitStrings.localized("Search titles and transcripts"))
                )
        }
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
                Button { importing = true } label: {
                    Image(systemName: "square.and.arrow.down")
                        .font(blueprint.fonts.sans(TypeSize.body))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(width: minTouch, height: minTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!model.isReady)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Import audio")))
                .accessibilityIdentifier("import-audio")
            }
            // docs/10 "iPhone": a banner at the top of the list — one row per
            // reason however many jobs are behind it, and the row is the way to the screen that
            // fixes it.
            AlertBanner(alerts: model.alerts, download: model.modelDownload) { model.fix($0) }
            if let message = model.message {
                Banner(message.text, tone: .warning)
                    .padding(.horizontal, Space.m)
                    .padding(.bottom, Space.s)
                    .onTapGesture { model.dismissMessage() }
                    .accessibilityIdentifier("message")
            }
            // docs/09 "Import": a failed import leaves no row, so the list says it here — and why.
            if let failure = model.importFailure {
                Banner("\(RecKitStrings.localized("Could not import this file")) — \(CoreMessages.text(failure).sentence)", tone: .danger)
                    .padding(.horizontal, Space.m)
                    .padding(.bottom, Space.s)
                    .onTapGesture { model.dismissImportFailure() }
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
        .sheet(item: $detail) { detail in
            RecordingDetailView(model: detail) { self.detail = nil }
        }
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
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dotGridBackground()
        .onChange(of: collapse) { expanded = nil }
        .task { await model.refreshRecents() }
        // docs/03: and beside it, never in front of it — the list is drawn from what is already
        // here, and a row another device uploaded arrives on the recordings observation.
        .task { await model.pullRemoteRecordings() }
        .sheet(item: $detail) { detail in
            RecordingDetailView(model: detail) { self.detail = nil }
        }
        // docs/03 "Deleting in the app": one recording, two answers about Drive, and the default is the one
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
            subtitle: "",
            length: length,
            status: item.badge,
            announce: LedgerFormat.announce(
                title: item.titleLabel,
                at: LedgerFormat.startedAt(item.startedAt),
                length: length,
                state: item.stateLabel
            ),
            // docs/09 "Accessibility": the row opens what is behind it, which a screen reader would
            // otherwise only find out by tapping.
            expanded: expanded == item.id,
            // docs/09 "Motion": 200 ms ease-in-out, and nothing at all with reduce motion on — the
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

    /// docs/09 screen principle 2: what is behind the row — where the recording stands, and the two or three
    /// things the user can do about it.
    private func expansion(_ item: RecentItem) -> some View {
        VStack(alignment: .leading, spacing: Space.s) {
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

            // docs/09 "Accessibility" · Fluid typography: several buttons across is a layout for
            // ordinary type sizes. On a narrow phone, or at an accessibility size, the same ones
            // wrap onto further lines — a label cut to a syllable says nothing, and a column is not
            // what the chips elsewhere do.
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
        // docs/09 screen principle 2: Delete ends the last line, across the row's whole width.
        ActionFlowLayout(trailingLast: item.canDelete) {
            // docs/05 "Fixed processing settings": the model this recording waits for, in its own
            // language, first — and nothing while the download runs (the banner has it).
            if item.waitingForModel, let download = model.modelDownload {
                ModelDownloadButton(download: download, language: item.localLanguage)
                    .accessibilityIdentifier("download-model")
            }
            if item.link != nil {
                BlueprintButton(loc("Open in Drive")) { model.openInDrive(item) }
            }
            // docs/10 "Drive out of space": nothing here retries on its own, and the only thing that
            // changes the answer is on Google's storage page.
            if item.alert == .needsSpace {
                BlueprintButton(loc("Open Drive storage")) { model.openDriveStorage() }
                    .accessibilityIdentifier("open-storage")
            }
            if item.alert == .needsConsent {
                BlueprintButton(RecKitStrings.localized("Review transfers")) {
                    model.fix(JobAlert(reason: .needsConsent, count: 1))
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
                BlueprintButton(RecordingDetailStrings.checkKey) { model.showProcessingSettings() }
                    .accessibilityIdentifier("check-key")
            }
            // docs/08 "Result files": the transcript of this recording, the local copy first and Drive
            // after (`RecordingDetailModel`). As wide as the detail's own Play button.
            BlueprintButton(RecordingDetailStrings.open, minWidth: playButtonMinWidth) {
                detail = model.detail(for: item)
            }
                .accessibilityIdentifier("open-detail")
            // docs/03: a recording being written to, arriving from the watch, or uploaded right now
            // — here or on the device that made it — is not one to delete ([RecentItem.canDelete]).
            if item.canDelete {
                BlueprintButton(loc("Delete"), tone: .danger, minWidth: minTouch) { model.confirmDelete(item) }
                    .accessibilityIdentifier("delete")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
