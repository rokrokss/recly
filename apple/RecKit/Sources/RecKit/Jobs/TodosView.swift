#if os(iOS) || os(macOS)
import os
import ReclyCore
import SwiftUI

extension RecordingTodos {
    /// Its title as its row in the list says it ([RecentItem.titleLabel]).
    public var titleLabel: String {
        title?.isEmpty == false ? title! : RecKitStrings.localized("Untitled")
    }

    /// Its id behind `Done`: a recording can have rows both open and done, and a lazy stack draws only one view of an id,
    /// leaving the other's room blank.
    fileprivate var doneId: String { "done-\(recordingId)" }
}

/// docs/09 "To-dos view": what the list's header and the view read — every recording's to-dos as they change, whether the
/// list's area is the view, and what was checked in it. The list owns one for as long as it stands.
@MainActor
public final class TodosModel: ObservableObject {
    /// docs/08 "To-dos": the collection. The header's button stands while it has a recording.
    @Published public private(set) var all: [RecordingTodos] = []
    /// The list's area is the view.
    @Published public private(set) var shown = false
    /// The items checked since the view opened, which stay where they are until it is left.
    @Published private(set) var held: Set<String> = []
    /// `Done` is open.
    @Published var doneOpen = false
    private var core: ReclyCore_?
    private let logger = Logger(subsystem: CoreBridge.appName, category: "todos")

    public init() {}

    var groups: TodoGroups { TodoGroups(all: all, held: held) }

    /// The number still open, which the header says: an item checked here no longer counts, though it stays where it is.
    public var openCount: Int {
        all.reduce(0) { count, group in count + group.items.filter { !$0.done }.count }
    }

    public func follow(core: ReclyCore_) async {
        self.core = core
        for await next in core.notes.observeTodos() {
            guard !Task.isCancelled else { return }
            all = next
            // A view left with no to-do at all goes back by itself.
            if next.isEmpty { leave() }
        }
    }

    /// The header's button: the view, and back to the list.
    public func toggleShown() {
        if shown { leave() } else { shown = true }
    }

    /// Back to the list; an import does it too, so that its row is seen coming in.
    public func leave() {
        shown = false
        held = []
        doneOpen = false
    }

    /// The box: the change arrives through [follow]. A failed write leaves the row as it was.
    func toggleDone(_ item: NoteItem, of group: RecordingTodos) {
        guard let core else { return }
        let done = !item.done
        if done { held.insert(item.id) }
        let logger = logger
        Task {
            do {
                _ = try await core.notes.setDone(recordingId: group.recordingId, itemId: item.id, done: done)
            } catch {
                logger.error("shell.notes.done.failed error=\(String(describing: error), privacy: .private)")
                if done { held.remove(item.id) }
            }
        }
    }
}

/// docs/09 "To-dos view": the list's area while the header's button has turned it. Each recording with something open is a
/// group, its time and title over its rows; `Done` under them opens the rest, grouped the same way, and with nothing open
/// the rest is simply there.
public struct TodosView: View {
    @ObservedObject private var model: TodosModel
    /// A group's header, or anywhere on a row but its box: the recording's detail on its `Summary` chip.
    private let open: (RecordingTodos) -> Void
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: the words here are resolved outside SwiftUI.
    @Environment(\.locale) private var locale

    public init(model: TodosModel, open: @escaping (RecordingTodos) -> Void) {
        self.model = model
        self.open = open
    }

    public var body: some View {
        let groups = model.groups
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(groups.open, id: \.recordingId) { group($0) }
                if groups.open.isEmpty {
                    ForEach(groups.done, id: \.doneId) { group($0) }
                } else if groups.doneCount > 0 {
                    doneRow(count: groups.doneCount)
                    if model.doneOpen {
                        ForEach(groups.done, id: \.doneId) { group($0) }
                    }
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.bottom, Space.m)
        }
        .accessibilityIdentifier("todos")
    }

    private func group(_ todos: RecordingTodos) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            header(todos)
            ForEach(todos.items, id: \.id) { row($0, of: todos) }
        }
    }

    /// `MM-dd HH:mm` in mono, then the title.
    private func header(_ todos: RecordingTodos) -> some View {
        Button { open(todos) } label: {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Text(verbatim: "\(LedgerFormat.date(todos.startedAt)) \(LedgerFormat.time(todos.startedAt))")
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize()
                Text(verbatim: todos.titleLabel)
                    .font(blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
                    .foregroundStyle(blueprint.palette.text)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: minTouch)
            .contentShape(Rectangle())
            .blueprintFocusRing(radius: 0)
        }
        .buttonStyle(BlueprintPressStyle())
        .focusEffectDisabled()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(todos.titleLabel), \(LedgerFormat.startedAt(todos.startedAt))"))
        .accessibilityAddTraits([.isButton, .isHeader])
        .accessibilityIdentifier("todo-group")
    }

    /// The box, then the words of a row of the notes, without the time button. Laid out as a row of the notes is: as tall
    /// as its words, 8 above and below.
    private func row(_ item: NoteItem, of todos: RecordingTodos) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            TodoBox(item: item) { model.toggleDone(item, of: todos) }
            NoteWords(item: item, struck: item.done)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentDirection(item.text)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { open(todos) }
        }
        .padding(.vertical, Space.s)
        .contentShape(Rectangle())
        .onTapGesture { open(todos) }
        .pointerCursor()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("todo-\(item.id)")
    }

    /// `Done` and the count in mono: a quiet row that opens and closes the rest.
    private func doneRow(count: Int32) -> some View {
        Button { model.doneOpen.toggle() } label: {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Text(verbatim: RecKitStrings.localized("Done"))
                    .font(blueprint.fonts.bodySmall)
                Text(verbatim: String(count))
                    .font(blueprint.fonts.monoBodySmall)
            }
            .foregroundStyle(blueprint.palette.textMuted)
            .frame(maxWidth: .infinity, minHeight: minTouch, alignment: .leading)
            .contentShape(Rectangle())
            .blueprintFocusRing(radius: 0)
        }
        .buttonStyle(BlueprintPressStyle())
        .focusEffectDisabled()
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(model.doneOpen ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("todos-done")
    }
}

/// docs/09 "To-dos view": a to-do's check box — the Blueprint selection mark, a check box to accessibility, named by the
/// item's words. Its target is the touch minimum each way but takes only the mark's size in the layout, reaching past the
/// row's words and the rows around it as a summary row's time does ([TouchTarget]'s `tight`), so a row stays as tall as
/// its words.
struct TodoBox: View {
    let item: NoteItem
    let toggle: () -> Void

    private static let overhang = (minTouch - 18) / 2

    var body: some View {
        Button(action: toggle) {
            SelectionMark(selected: item.done, filled: true)
                .blueprintFocusRing(radius: Radius.badge)
                .padding(Self.overhang)
                .contentShape(Rectangle())
                .padding(-Self.overhang)
        }
        .buttonStyle(.plain)
        .pointerCursor()
        .focusEffectDisabled()
        // Over the words beside it, which come later in the row and would otherwise take the end of its target.
        .zIndex(1)
        // The mark sits on the first line of words that wrapped, not in the middle of them (`OptionRow`).
        .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 3 }
        .accessibilityRepresentation {
            Toggle(isOn: Binding(get: { item.done }, set: { _ in toggle() })) { Text(verbatim: NoteGroups.text(item)) }
        }
        .accessibilityIdentifier("todo-box-\(item.id)")
    }
}
#endif
