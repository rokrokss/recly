import SwiftUI

/// docs/09 screen principle 2: the ledger's three columns, in the widths the mockup sets, and the gap between two of
/// them — Android's `COLUMN_GAP`. The length column is measured instead ([LengthColumn]).
private enum Column {
    static let time: CGFloat = LedgerMetrics.timeColumn
    static let gap: CGFloat = Space.s
}

/// docs/09 "Component measurements" — Ledger row: the time column is as wide as the longest time in the mono font,
/// 62 on Apple. Named, because what sits under the title column of an open row is inset by it.
public enum LedgerMetrics {
    public static let timeColumn: CGFloat = 62
}

/// The length column: as wide as the placeholder `--:--` in the ledger's mono at the type size in use — what
/// any length under the hour takes, as Android measures its own — and wider where the content is, aligned to
/// its trailing edge. A row past the hour (`01:02:33`) takes the room from its own title; a heading wider than
/// the lengths (`Length`) takes it from the header's title column, so neither is cut and the rows keep it.
private struct LengthColumn<Content: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            Text(verbatim: LedgerFormat.noLength)
                .font(blueprint.fonts.monoSmall)
                .hidden()
            content.fixedSize()
        }
    }
}

/// The column headings above the ledger. At an accessibility type size the row below stops being
/// three columns (see [LedgerRow]), so there is nothing left for these to head.
///
/// docs/09 screen principle 2 (2026-11-04): no Status heading — the state is no longer a column but a badge under
/// the title of a row that is not done.
public struct LedgerHeader: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.dynamicTypeSize) private var typeSize
    private let time: String
    private let title: String
    private let length: String

    public init(time: String, title: String, length: String) {
        self.time = time
        self.title = title
        self.length = length
    }

    public var body: some View {
        VStack(spacing: 0) {
            if !typeSize.isAccessibilitySize {
                columns
            }
            HairLine()
        }
    }

    private var columns: some View {
        VStack(spacing: 0) {
            HStack(spacing: Column.gap) {
                heading(time).frame(width: Column.time, alignment: .leading)
                heading(title).frame(maxWidth: .infinity, alignment: .leading)
                LengthColumn { heading(length) }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
            .accessibilityHidden(true)
        }
    }

    private func heading(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.label)
            .tracking(0.6)
            .foregroundStyle(blueprint.palette.textMuted)
            .lineLimit(1)
    }
}

/// One recording, as a row of the ledger: when (monospace), what, and how long (docs/09 screen principle 2). Under
/// the title, the transcript's first words — or, while the row is not done, its state as a badge in their place
/// (2026-11-04). The whole row is one accessibility element — separate announcements of `08-29`, a title, `42:10`
/// and a state are worse than one sentence — so the caller hands in the sentence, with the state in words, `Done`
/// included. A tap opens the recording's page (2026-10-28).
public struct LedgerRow<Trailing: View>: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.dynamicTypeSize) private var typeSize
    /// docs/07 rule 3: the row's own two sentences are resolved outside SwiftUI, and reading the
    /// locale is what declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale
    private let date: String
    private let time: String
    private let title: String
    private let subtitle: String
    private let length: String
    private let status: LedgerStatus
    private let announce: String
    private let opened: Bool
    private let action: () -> Void
    private let trailing: Trailing

    /// - Parameter opened: the recording is the one open beside the list (the Mac's Details window): the row
    ///   wears the page's tint and a 2pt accent bar at its start, as Windows' does (2026-10-10, A-A9), and a
    ///   screen reader hears it as selected.
    /// - Parameter trailing: what the row offers *about* the recording, beside the length and on the
    ///   same line — a chip, not a button's worth of height. It is a sibling of the row's own
    ///   button and never inside it, because a button with a button in it is one target that
    ///   swallows the other; it is laid out at its own width, so it takes a column rather than a
    ///   share of the row.
    public init(
        date: String,
        time: String,
        title: String,
        subtitle: String,
        length: String,
        status: LedgerStatus,
        announce: String,
        opened: Bool = false,
        action: @escaping () -> Void,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.date = date
        self.time = time
        self.title = title
        self.subtitle = subtitle
        self.length = length
        self.status = status
        self.announce = announce
        self.opened = opened
        self.action = action
        self.trailing = trailing()
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Centred, so the chip lands on the line the length is on — which is the middle of the
            // row, whether the row is two lines of columns or the stack an accessibility size gets.
            HStack(spacing: 0) {
                button
                trailing
                    .fixedSize()
                    .padding(.trailing, Space.m)
            }
            HairLine()
        }
        .background(opened ? blueprint.palette.background : blueprint.palette.surface)
        .overlay(alignment: .leading) {
            if opened { Rectangle().fill(blueprint.palette.accent).frame(width: 2) }
        }
    }

    /// The row itself — a real button rather than a tap gesture, so it is announced as something to
    /// press and can be reached from the keyboard.
    private var button: some View {
        Button(action: action) {
            Group {
                if typeSize.isAccessibilitySize { stacked } else { columns }
            }
            .lineLimit(1)
            // docs/09 "Component measurements" — Ledger row: 12 top and bottom.
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.sm)
            .contentShape(Rectangle())
            .blueprintFocusRing(radius: 0)
        }
        .buttonStyle(.plain)
        .pointerCursor()
        .focusEffectDisabled()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: announce))
        .accessibilityAddTraits(opened ? [.isButton, .isSelected] : .isButton)
        // The element `children: .ignore` makes is a new one, without the button's own press.
        .accessibilityAction { action() }
    }

    /// Three columns, as docs/09 screen principle 2 draws the ledger.
    private var columns: some View {
        HStack(spacing: Column.gap) {
            when.frame(width: Column.time, alignment: .leading)
            what.frame(maxWidth: .infinity, alignment: .leading)
            LengthColumn { howLong }
        }
    }

    /// The same three things at an accessibility type size, where three columns across a phone is
    /// three truncations: the row keeps its order and turns ninety degrees.
    private var stacked: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            what
            HStack(spacing: Space.sm) {
                when
                howLong
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var when: some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            Text(verbatim: date)
            Text(verbatim: time)
        }
        .font(blueprint.fonts.monoSmall)
        .foregroundStyle(blueprint.palette.textMuted)
    }

    private var what: some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            Text(verbatim: title)
                .font(blueprint.fonts.rowTitle)
                .foregroundStyle(blueprint.palette.text)
            // docs/09 screen principle 2 (2026-11-04): a row that is not done wears its badge here, in the first words'
            // place; once it is done the badge goes and the first words come back.
            if status.shown {
                StatusBadge(status, wraps: true)
                    .padding(.top, Space.xxs)
                    .modifier(BadgeFade(status: status))
            } else if !subtitle.isEmpty {
                Text(verbatim: subtitle)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
            }
        }
    }

    private var howLong: some View {
        Text(verbatim: length)
            .font(blueprint.fonts.monoSmall)
            .foregroundStyle(blueprint.palette.text)
    }
}

extension LedgerRow where Trailing == EmptyView {
    /// The ledger as three of the four surfaces draw it: the row and nothing beside it.
    public init(
        date: String,
        time: String,
        title: String,
        subtitle: String,
        length: String,
        status: LedgerStatus,
        announce: String,
        opened: Bool = false,
        action: @escaping () -> Void
    ) {
        self.init(
            date: date,
            time: time,
            title: title,
            subtitle: subtitle,
            length: length,
            status: status,
            announce: announce,
            opened: opened,
            action: action
        ) { EmptyView() }
    }
}

/// docs/09 "Component measurements" — Status badge: it fades 150 ms when its state changes — the words cross-fade and
/// the edge's colour follows — and with reduce motion it simply changes (docs/09 "Motion").
private struct BadgeFade: ViewModifier {
    @Environment(\.blueprint) private var blueprint
    let status: LedgerStatus

    func body(content: Content) -> some View {
        content
            .contentTransition(.opacity)
            .animation(Motion.badgeAnimation(reduceMotion: blueprint.reduceMotion), value: status)
    }
}
