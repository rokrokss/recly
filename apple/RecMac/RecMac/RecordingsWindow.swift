import AppKit
import RecKit
import ReclyCore
import SwiftUI
import UniformTypeIdentifiers

/// docs/08 "Result files", deliverable 3: the recent recordings and what the `transcribe` step wrote for
/// the one that is picked. A window of its own for the same reason the editor is one — `LSUIElement`
/// means the popover is the only other surface, and a transcript does not fit in a popover.
struct RecordingsWindow: View {
    static let id = "recordings"

    @ObservedObject var menu: MenuModel
    @Environment(\.blueprint) private var blueprint
    @Environment(\.openWindow) private var openWindow
    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI, so reading the
    /// locale is what declares the dependency that redraws it in the new language.
    @Environment(\.locale) private var locale
    /// Files are being dragged over the list.
    @State private var dropping = false

    var body: some View {
        HSplitView {
            VStack(spacing: 0) {
                ScreenHeader(title: loc("Details"), meta: "\(menu.recordingCount)") {
                    // ux §7: an agent app shows no menu bar, so the list header's button carries ⌘I.
                    if MenuModel.importer != nil {
                        Button { pickFiles() } label: {
                            Image(systemName: "square.and.arrow.down")
                                .font(blueprint.fonts.sans(TypeSize.body))
                                .foregroundStyle(blueprint.palette.textMuted)
                                .frame(width: minTouch, height: minTouch)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .keyboardShortcut("i")
                        .help(loc("Import audio…"))
                        .accessibilityLabel(Text(verbatim: loc("Import audio")))
                        .accessibilityIdentifier("import-audio")
                    }
                }
                HairLine()
                if let failure = menu.importFailure {
                    Banner(
                        ([loc("Could not import this file")] + (failure.isEmpty ? [] : [CoreMessages.text(failure).sentence]))
                            .joined(separator: "\n"),
                        tone: .danger
                    )
                    .padding(Space.s)
                }
                ScrollView {
                    // Lazy, so the page marker under the rows appears only when it is scrolled to.
                    LazyVStack(spacing: 0) {
                        if menu.recents.isEmpty {
                            if menu.recentsLoading {
                                EmptyListMessage(title: loc("Loading…"))
                            } else {
                                EmptyListMessage(title: loc("No recordings yet"), hint: loc("Recordings you make appear here.")) {
                                    BlueprintButton(loc("Start recording"), tone: .primary) { menu.start() }
                                }
                            }
                        }
                        ForEach(menu.recents) { item in
                            row(item)
                        }
                        // docs/12 "Menu bar": the same paging as the popover's ledger — the next page
                        // when the end comes into view, keyed on the count so a page that did not
                        // push it out of view asks again.
                        if !menu.recents.isEmpty {
                            Color.clear
                                .frame(height: 1)
                                .id(menu.recents.count)
                                .onAppear { Task { await menu.loadMoreRecents() } }
                        }
                    }
                }
            }
            .frame(minWidth: 300)
            .dotGridBackground()
            // ux §7: audio and video files dropped on the list are imported, one row each.
            .dropDestination(for: URL.self) { urls, _ in
                menu.importAudio(urls)
                return MenuModel.importer != nil
            } isTargeted: { dropping = $0 }
            .overlay {
                if dropping, MenuModel.importer != nil {
                    Rectangle().strokeBorder(blueprint.palette.accent, lineWidth: blueprint.line + 1)
                }
            }

            Group {
                if let detail = menu.detail {
                    RecordingDetailView(model: detail)
                } else {
                    Text(verbatim: loc("Pick a recording."))
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .dotGridBackground()
                }
            }
            .frame(minWidth: 380, maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 720, minHeight: 420)
        // ux §2–3: the picked recording's `Share` in the window's toolbar, at its end.
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                if let detail = menu.detail {
                    ShareMenu(detail: detail) { await menu.export(detail.recordingId, $0) }
                        .id(detail.recordingId)
                }
            }
        }
        // docs/03: the window opening is this Mac asking Drive what the other devices have uploaded
        // since it last looked. The rows it adopts arrive on the model's recordings observation, so
        // nothing here waits for it.
        .task { await menu.pullRemoteRecordings() }
        // docs/03 "Deleting in the app": this window has one, so the dialog is the platform's own sheet here
        // rather than the popover's in-place overlay — and only for the deletes its own rows asked.
        // A question started in the popover is answered there, not on a window behind it.
        .blueprintDialog(
            item: Binding(
                get: { menu.deleteRequest?.source == .recordingsWindow ? menu.deleteRequest : nil },
                // A sheet only ever writes nil back, and a dismissal is a cancel like any other.
                set: { if $0 == nil { menu.cancelDelete() } }
            )
        ) { ask in
            DeleteDialog(request: ask.request, device: .mac) {
                menu.delete($0, deleteDrive: $1)
            } cancel: {
                menu.cancelDelete()
            }
        }
    }

    /// The system's picker for audio and video, several at once.
    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.audiovisualContent]
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK else { return }
        menu.importAudio(panel.urls)
    }

    /// docs/09 screen principle 2: the same ledger row the popover and the phone draw — one accessibility
    /// element with the whole sentence in it, and a real button rather than a tap gesture, so it is
    /// announced as something you can press and can be reached from the keyboard.
    ///
    /// What the row cannot hold goes beside or under it, outside the button, because a button with
    /// buttons inside it is one target that swallows the others: the delete as a chip beside the
    /// state, on the state's own line and at the state's own size, and the reason a job is stuck —
    /// with the two things to do about it — underneath.
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
            action: { menu.showDetail(item) }
        ) {
            // docs/03: a recording being written to, arriving from the watch, or uploaded right now
            // — here or on the device that made it — is not one to delete ([RecentItem.canDelete]).
            if item.canDelete {
                BadgeButton(loc("Delete"), tone: .danger) {
                    menu.confirmDelete(item, from: .recordingsWindow)
                }
                    .accessibilityIdentifier("delete")
            }
        }
        .accessibilityIdentifier("open-detail")
        // docs/08 "Errors": what to do about it, and — for a key — where to do it. docs/07 §5:
        // `lastError` is a core message key, and a row an older build wrote is prose that
        // `CoreMessages` shows as it stands.
        let fixes = item.needsKey || item.alert == .needsSpace || item.waitingForModel
        if (item.alert != .needsAuth && item.reason != nil) || fixes {
            VStack(alignment: .leading, spacing: Space.xs) {
                // Red for a failure, the badge's warning tone for a job that is only waiting.
                RowReason(item: item, download: menu.modelDownload, showsDetail: false)
                if fixes {
                    FlowLayout {
                        // docs/05 "Fixed processing settings": the model this recording waits for, first.
                        if item.waitingForModel, let download = menu.modelDownload {
                            ModelDownloadButton(download: download, language: item.localLanguage)
                                .accessibilityIdentifier("download-model")
                        }
                        if item.needsKey {
                            BlueprintButton(RecordingDetailStrings.checkKey) {
                                // The settings are a window of their own (`LSUIElement`), and it
                                // may not be open.
                                openWindow(id: "processing-settings")
                            }
                            .accessibilityIdentifier("check-key")
                        }
                        if item.alert == .needsSpace {
                            BlueprintButton(loc("Open Drive storage")) {
                                menu.openDriveStorage()
                            }
                            .accessibilityIdentifier("open-storage")
                        }
                    }
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.s)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(blueprint.palette.surface)
        }
    }
}
