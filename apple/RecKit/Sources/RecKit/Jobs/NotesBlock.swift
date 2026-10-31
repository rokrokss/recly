import Foundation
import ReclyCore
#if os(iOS) || os(macOS)
import SwiftUI
#endif

/// One of the notes block's groups (docs/08 "Organizing"): its header and its items, newest first as the core gives
/// them — a built-in group, or one of the user's own sections — under the name the notes were made with.
public struct NoteGroup: Identifiable, Equatable {
    public let kind: NoteKind
    public let items: [NoteItem]
    /// The user's section this group is ([NoteKind.section]); nil for the built-in groups.
    public let sectionId: String?
    /// The name the user gave it: a section's always, a built-in group's once they renamed it ([NoteNames]).
    public let name: String?

    init(kind: NoteKind, items: [NoteItem], sectionId: String? = nil, name: String? = nil) {
        self.kind = kind
        self.items = items
        self.sectionId = sectionId
        self.name = name
    }

    public var id: String { sectionId.map { "section-\($0)" } ?? "\(kind)" }

    /// The group's header: the name the user gave it, else the app's word for a built-in group.
    public var title: String { name ?? Self.word(kind) }

    /// The app's word for a built-in group, in the app's language; nothing for a section, which has only its name.
    public static func word(_ kind: NoteKind) -> String {
        switch kind {
        case .point: return RecKitStrings.localized("Key points")
        case .decision: return RecKitStrings.localized("Decisions")
        case .todo: return RecKitStrings.localized("To-dos")
        case .question: return RecKitStrings.localized("Questions")
        case .section: return ""
        }
    }
}

/// docs/08 "Organizing": what the notes block makes of a recording's notes — the rules the detail and the iPhone's
/// recording screen draw by, apart from the drawing so a test can hold them.
public enum NoteGroups {
    /// docs/09 "Notes view": Key points · Decisions · To-dos · Questions — each under the name the user gave it, if
    /// they did — then the user's sections in the order the notes were made with. A group with no items is not there,
    /// header and all. [keyPoints] false leaves the key points out — the recording screen's, as they come after Stop.
    public static func of(_ notes: RecordingNotes?, keyPoints: Bool = true) -> [NoteGroup] {
        guard let notes else { return [] }
        let names = notes.names
        let builtIn = [
            NoteGroup(kind: .point, items: keyPoints ? notes.points : [], name: names.points),
            NoteGroup(kind: .decision, items: notes.decisions, name: names.decisions),
            NoteGroup(kind: .todo, items: notes.todos, name: names.todos),
            NoteGroup(kind: .question, items: notes.questions, name: names.questions),
        ]
        let sections = notes.sections.map { NoteGroup(kind: .section, items: $0.items, sectionId: $0.id, name: $0.name) }
        return (builtIn + sections).filter { !$0.items.isEmpty }
    }

    /// docs/08 "Organizing" — "Copy and share": the groups' headings in the app's language, for the notes' text. The
    /// shells hand them to the core at start and whenever the language changes, as they hand it the locale.
    public static var headings: NotesHeadings {
        NotesHeadings(
            notes: RecKitStrings.localized("Summary"),
            keyPoints: RecKitStrings.localized("Key points"),
            decisions: RecKitStrings.localized("Decisions"),
            todos: RecKitStrings.localized("To-dos"),
            questions: RecKitStrings.localized("Questions"),
            me: RecKitStrings.localized("Me")
        )
    }

    /// The two or three lines over the whole recording, where the block shows them (the detail, not the recording
    /// screen) and there are some.
    public static func overview(_ notes: RecordingNotes?, shown: Bool) -> String? {
        guard shown, let text = notes?.overview?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        return text
    }

    /// No overview to show and no item in any group: the block is not there at all — no header, no empty state. The
    /// detail shows the overview and the key points; the recording screen neither.
    public static func isEmpty(_ notes: RecordingNotes?, showsOverview: Bool) -> Bool {
        overview(notes, shown: showsOverview) == nil && of(notes, keyPoints: showsOverview).isEmpty
    }

    /// The row's words: the item, and its owner after a dash when the speaker has a name — `Send the quote — Minji`.
    public static func text(_ item: NoteItem) -> String {
        guard let owner = item.owner?.trimmingCharacters(in: .whitespacesAndNewlines), !owner.isEmpty else { return item.text }
        return "\(item.text) — \(owner)"
    }

    /// The deadline as it was said, shown after the text; nil when none was.
    public static func due(_ item: NoteItem) -> String? {
        guard let due = item.due?.trimmingCharacters(in: .whitespacesAndNewlines), !due.isEmpty else { return nil }
        return due
    }

    /// Which of the lines around an item is its own, to emphasize: the one holding the item's quote, else the one
    /// that was being said at its time, else the one that began nearest it. Nil when there are no lines.
    public static func emphasized(_ lines: [NoteLine], for item: NoteItem) -> Int? {
        let quote = item.quote.trimmingCharacters(in: .whitespacesAndNewlines)
        if !quote.isEmpty, let quoted = lines.firstIndex(where: { $0.text.localizedCaseInsensitiveContains(quote) }) {
            return quoted
        }
        if let covering = lines.firstIndex(where: { $0.atSec <= item.atSec && item.atSec < max($0.endSec, $0.atSec + 0.001) }) {
            return covering
        }
        return lines.indices.min { abs(lines[$0].atSec - item.atSec) < abs(lines[$1].atSec - item.atSec) }
    }

    /// Who said a line: the name the user gave the speaker, or `Me` for the person who made the recording (docs/08 "Me
    /// and others"); nothing for an unnamed speaker — a label like `S1` names nobody.
    public static func speaker(_ line: NoteLine) -> String? {
        if let name = line.speakerName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty { return name }
        return line.me ? RecKitStrings.localized("Me") : nil
    }
}

#if os(iOS) || os(macOS)
/// docs/08 "Organizing" · docs/09 "Notes view": a recording's notes — the overview on top where it is shown, then the
/// groups ([NoteGroups.of]), each a header and its rows. A row is the item, its owner and its deadline, and its
/// time at the end; a tap opens the transcript around it under the row (one at a time) and a second tap closes it. The
/// time plays the recording from there where [onSeek] is given; while recording it is a plain label. The phone deletes
/// a row with a swipe, the Mac from its context menu, with no question: the core never brings it back.
///
/// Nothing at all when there is nothing to show ([NoteGroups.isEmpty]); the screen around it leaves it out then.
public struct NotesBlock: View {
    let notes: RecordingNotes?
    /// The overview on top, and the key points — the detail's; the recording screen shows the other groups alone.
    let showsOverview: Bool
    /// The time as this screen draws its times.
    let stamp: (Double) -> String
    /// The time is a button that moves the playhead to it — the detail; a label while recording.
    let playable: Bool
    /// How far the recording here can be played: a time past it is off.
    let seekableSec: Double
    /// Nil while the recording cannot be played from a point.
    let onSeek: ((Double) -> Void)?
    /// The transcript's lines around a moment (`notes.linesAround`).
    let lines: (Double) async -> [NoteLine]
    let delete: (NoteItem) -> Void

    @State private var expanded: String?
    @State private var shownLines: [NoteLine] = []
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(
        notes: RecordingNotes?,
        showsOverview: Bool,
        stamp: @escaping (Double) -> String,
        playable: Bool,
        seekableSec: Double,
        onSeek: ((Double) -> Void)?,
        lines: @escaping (Double) async -> [NoteLine],
        delete: @escaping (NoteItem) -> Void
    ) {
        self.notes = notes
        self.showsOverview = showsOverview
        self.stamp = stamp
        self.playable = playable
        self.seekableSec = seekableSec
        self.onSeek = onSeek
        self.lines = lines
        self.delete = delete
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let overview = NoteGroups.overview(notes, shown: showsOverview) {
                Text(verbatim: overview)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentDirection(overview)
                    .accessibilityIdentifier("notes-overview")
            }
            ForEach(NoteGroups.of(notes, keyPoints: showsOverview)) { group in
                SectionHeader(group.title)
                ForEach(group.items, id: \.id) { item in
                    row(item)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // A container of its own, or its identifier lands on every row and hides their `note-<id>`.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("notes")
    }

    private func row(_ item: NoteItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                words(item)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentDirection(item.text)
                time(item)
            }
            // As tall as its words, 8 above and below (2026-10-31): the touch minimum made a list of one-line items read
            // double-spaced. The time's target reaches past its box instead of taking the room.
            .padding(.vertical, Space.s)
            .contentShape(Rectangle())
            .onTapGesture { toggle(item) }
            .pointerCursor()
            if expanded == item.id {
                around(item)
            }
        }
        .modifier(NoteDelete(label: RecKitStrings.localized("Delete")) { delete(item) })
        .accessibilityIdentifier("note-\(item.id)")
    }

    /// The item, its owner after a dash, and the deadline after them in the secondary colour.
    private func words(_ item: NoteItem) -> some View {
        var text = Text(verbatim: NoteGroups.text(item)).foregroundStyle(blueprint.palette.text)
        if let due = NoteGroups.due(item) {
            text = text + Text(verbatim: " · \(due)").foregroundStyle(blueprint.palette.textMuted)
        }
        return text
            .font(blueprint.fonts.bodySmall)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { toggle(item) }
            // docs/09 trend 4: the swipe's static alternative.
            .accessibilityAction(named: Text(verbatim: RecKitStrings.localized("Delete"))) { delete(item) }
    }

    @ViewBuilder
    private func time(_ item: NoteItem) -> some View {
        if playable {
            // The transcript time button's path: it moves the playhead and leaves playing or paused as it was
            // (docs/09 "AI view", 2026-10-28).
            BlueprintButton(stamp(item.atSec), tone: .quiet, mono: true, compact: true, tight: true) { onSeek?(item.atSec) }
                .disabled(onSeek == nil || item.atSec >= seekableSec)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Go to %@", LedgerFormat.clock(Int(item.atSec)))))
        } else {
            Text(verbatim: stamp(item.atSec))
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
        }
    }

    /// The transcript around the item, the line it came from wearing the transcript reader's 2 pt accent bar.
    private func around(_ item: NoteItem) -> some View {
        let emphasized = NoteGroups.emphasized(shownLines, for: item)
        return VStack(alignment: .leading, spacing: Space.s) {
            ForEach(Array(shownLines.enumerated()), id: \.offset) { index, line in
                VStack(alignment: .leading, spacing: Space.xxs) {
                    if let speaker = NoteGroups.speaker(line) {
                        Text(verbatim: speaker)
                            .font(blueprint.fonts.label)
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                    Text(verbatim: line.text)
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.text)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentDirection(line.text)
                }
                .padding(.leading, Space.sm)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(alignment: .leading) {
                    if index == emphasized {
                        Rectangle().fill(blueprint.palette.accent).frame(width: 2)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(.bottom, Space.s)
        .accessibilityIdentifier("note-lines")
    }

    /// One row open at a time: another tap closes it, a tap on another row moves it there.
    private func toggle(_ item: NoteItem) {
        if expanded == item.id {
            expanded = nil
            shownLines = []
            return
        }
        expanded = item.id
        shownLines = []
        Task {
            let found = await lines(item.atSec)
            if expanded == item.id { shownLines = found }
        }
    }
}

/// How a row is deleted: a swipe toward the leading edge on the phone, past which it goes as the finger lifts — the
/// red `Delete` under it shows how far — and the context menu's `Delete` on the Mac. A screen reader has the action
/// by name on the row's words, on both.
private struct NoteDelete: ViewModifier {
    let label: String
    let delete: () -> Void

    #if os(iOS)
    /// How far the row has moved toward its leading edge.
    @State private var pull: CGFloat = 0
    @Environment(\.layoutDirection) private var direction
    @Environment(\.blueprint) private var blueprint

    /// Two touch targets: further than a scroll's sideways wobble, short of a full sweep.
    private static let threshold: CGFloat = minTouch * 2

    func body(content: Content) -> some View {
        content
            // Mirrored right to left, as the leading edge is.
            .offset(x: -pull)
            .background(alignment: .trailing) {
                if pull > 0 {
                    ZStack {
                        Rectangle().fill(blueprint.palette.danger)
                        if pull >= Self.threshold {
                            Text(verbatim: label)
                                .font(blueprint.fonts.label)
                                .tracking(0.6)
                                .foregroundStyle(blueprint.palette.onDanger)
                                .lineLimit(1)
                        }
                    }
                    .frame(width: pull)
                    .accessibilityHidden(true)
                }
            }
            // Beside the scroll, not instead of it: a mostly vertical drag is the page's.
            .simultaneousGesture(
                DragGesture(minimumDistance: 16, coordinateSpace: .global)
                    .onChanged { drag in
                        guard pull > 0 || abs(drag.translation.width) > abs(drag.translation.height) else { return }
                        let leading = direction == .rightToLeft ? drag.translation.width : -drag.translation.width
                        pull = max(0, leading)
                    }
                    .onEnded { _ in
                        if pull >= Self.threshold {
                            delete()
                        }
                        withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) { pull = 0 }
                    }
            )
    }
    #else
    func body(content: Content) -> some View {
        content
            .contextMenu {
                Button(label, role: .destructive, action: delete)
            }
    }
    #endif
}
#endif
