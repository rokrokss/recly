import RecKit
import ReclyCore
import SwiftUI

/// docs/08 "결과 파일", deliverable 3: the recent recordings and what the `transcribe` step wrote for
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

    var body: some View {
        HSplitView {
            VStack(spacing: 0) {
                ScreenHeader(title: loc("Details"), meta: "\(menu.recordingCount)")
                HairLine()
                ScrollView {
                    // Lazy, so the page marker under the rows appears only when it is scrolled to.
                    LazyVStack(spacing: 0) {
                        ForEach(menu.recents) { item in
                            row(item)
                        }
                        // docs/12 "메뉴바": the same paging as the popover's ledger — the next page
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
        // docs/03: the window opening is this Mac asking Drive what the other devices have uploaded
        // since it last looked. The rows it adopts arrive on the model's recordings observation, so
        // nothing here waits for it.
        .task { await menu.pullRemoteRecordings() }
        // docs/03 "앱에서 지우기": this window has one, so the dialog is the platform's own sheet here
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

    /// docs/09 화면 원칙 2: the same ledger row the popover and the phone draw — one accessibility
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
            subtitle: item.id,
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
            // docs/03: a recording being written to or uploaded right now is not one to delete —
            // the core refuses it anyway.
            if item.state != "Recording", item.state != "Uploading" {
                BadgeButton(loc("Delete"), tone: .danger) {
                    menu.confirmDelete(item, from: .recordingsWindow)
                }
                    .accessibilityIdentifier("delete")
            }
        }
        .accessibilityIdentifier("open-detail")
        // docs/08 "오류": what to do about it, and — for a key — where to do it. docs/07 §5:
        // `lastError` is a core message key, and a row an older build wrote is prose that
        // `CoreMessages` shows as it stands.
        let fixes = item.needsKey || item.alert == .needsSpace
        if item.reason != nil || fixes {
            VStack(alignment: .leading, spacing: Space.xs) {
                if let reason = item.reason {
                    Text(verbatim: reason.sentence)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.danger)
                }
                if fixes {
                    FlowLayout {
                        if item.needsKey {
                            BlueprintButton(RecordingDetailStrings.checkKey) {
                                menu.editWorkflow(of: item)
                                // The editor is a window of its own (`LSUIElement`), and it may not
                                // be open — selecting a workflow in a window nobody can see is no
                                // answer.
                                openWindow(id: WorkflowWindow.id)
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
