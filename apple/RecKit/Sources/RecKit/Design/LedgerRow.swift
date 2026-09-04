import SwiftUI

/// docs/09 화면 원칙 2: the ledger's four columns, in the widths the mockup sets.
private enum Column {
    static let time: CGFloat = 62
    static let length: CGFloat = 44
    /// Wide enough for `NEEDS_AUTH`, the longest code `LedgerStatus` mints.
    static let status: CGFloat = 96
}

/// The column headings above the ledger. At an accessibility type size the row below stops being
/// four columns (see [LedgerRow]), so there is nothing left for these to head.
public struct LedgerHeader: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.dynamicTypeSize) private var typeSize
    private let time: String
    private let title: String
    private let length: String
    private let status: String

    public init(time: String, title: String, length: String, status: String) {
        self.time = time
        self.title = title
        self.length = length
        self.status = status
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
            HStack(spacing: 10) {
                heading(time).frame(width: Column.time, alignment: .leading)
                heading(title).frame(maxWidth: .infinity, alignment: .leading)
                heading(length).frame(width: Column.length, alignment: .trailing)
                heading(status).frame(width: Column.status, alignment: .center)
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, 6)
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

/// One recording, as a row of the ledger: when (monospace), what, how long, and the state as a code
/// (docs/09 화면 원칙 2). The whole row is one accessibility element — four separate announcements of
/// `08-29`, a title, `42:10` and `DONE` are worse than one sentence — so the caller hands in the
/// sentence, with the state in words rather than as a code.
///
/// A row that opens under itself says so: whether it is open is the element's value, and the tap is
/// offered a second time as a named action, so a screen reader can tell an open row from a closed
/// one without tapping it to find out (docs/09 "접근성", as Android's `LedgerRow` does with
/// `expand`/`collapse`).
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
    private let expanded: Bool?
    private let action: () -> Void
    private let trailing: Trailing

    /// - Parameter expanded: whether the expansion under this row is open, on the shells whose row
    ///   opens one — nil where the tap does something else entirely (the Mac's window opens a
    ///   detail), because a row that announces an expand action nobody can see is worse than one
    ///   that says nothing.
    /// - Parameter trailing: what the row offers *about* the recording, beside the state and on the
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
        expanded: Bool? = nil,
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
        self.expanded = expanded
        self.action = action
        self.trailing = trailing()
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Centred, so the chip lands on the line the badge is on — which is the middle of the
            // row, whether the row is two lines of columns or the stack an accessibility size gets.
            HStack(spacing: 0) {
                if let expanded {
                    button
                        .accessibilityValue(Text(verbatim: openState(expanded)))
                        .accessibilityAction(named: Text(verbatim: toggleLabel(expanded))) {
                            action()
                        }
                } else {
                    button
                }
                trailing
                    .fixedSize()
                    .padding(.trailing, Space.m)
            }
            HairLine()
        }
        .background(blueprint.palette.surface)
    }

    /// The row itself — a real button rather than a tap gesture, so it is announced as something to
    /// press and can be reached from the keyboard.
    private var button: some View {
        Button(action: action) {
            Group {
                if typeSize.isAccessibilitySize { stacked } else { columns }
            }
            .lineLimit(1)
            .padding(.horizontal, Space.m)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: announce))
        .accessibilityAddTraits(.isButton)
    }

    /// What the tap would do, named — the same two sentences Android's row labels its
    /// `expand`/`collapse` actions with.
    private func toggleLabel(_ expanded: Bool) -> String {
        expanded
            ? RecKitStrings.localized("Collapse")
            : RecKitStrings.localized("Expand")
    }

    /// The row's value: what a screen reader says after the sentence, so an open row and a closed
    /// one are not announced identically.
    private func openState(_ expanded: Bool) -> String {
        expanded ? RecKitStrings.localized("Expanded") : RecKitStrings.localized("Collapsed")
    }

    /// Four columns, as docs/09 화면 원칙 2 draws the ledger.
    private var columns: some View {
        HStack(spacing: 10) {
            when.frame(width: Column.time, alignment: .leading)
            what.frame(maxWidth: .infinity, alignment: .leading)
            howLong
                .frame(width: Column.length, alignment: .trailing)
            StatusBadge(status).frame(width: Column.status)
        }
    }

    /// The same four things at an accessibility type size, where four columns across a phone is
    /// four truncations: the row keeps its order and turns ninety degrees.
    private var stacked: some View {
        VStack(alignment: .leading, spacing: 6) {
            what
            HStack(spacing: 12) {
                when
                howLong
                Spacer(minLength: 0)
            }
            // On a line of its own: the code is the state (docs/09 화면 원칙 2), and at these sizes
            // `NEEDS_AUTH` is as wide as the stamp and the length together.
            StatusBadge(status)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var when: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: date)
            Text(verbatim: time)
        }
        .font(blueprint.fonts.monoSmall)
        .foregroundStyle(blueprint.palette.textMuted)
    }

    private var what: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: title)
                .font(blueprint.fonts.rowTitle)
                .foregroundStyle(blueprint.palette.text)
            Text(verbatim: subtitle)
                .font(blueprint.fonts.monoSmall)
                .foregroundStyle(blueprint.palette.textMuted)
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
        expanded: Bool? = nil,
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
            expanded: expanded,
            action: action
        ) { EmptyView() }
    }
}
