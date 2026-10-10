#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "Transcript reader": the transcript as paragraphs under the recording. While it plays, the
/// paragraph under the playhead wears a 2 pt accent bar and an accent time, and the list keeps it in
/// the upper third — until the user scrolls, when `Back to playback` takes them back. Speakers are
/// told apart by their label alone; a highlight is a small square after the time; a find (from search, or
/// ⌘F on the Mac) tints every match.
struct TranscriptReader: View {
    let transcript: Transcript
    let groups: [TranscriptGroup]
    let seekableDurationSec: Double
    /// The recording's whole length, which shapes every time drawn here (2026-10-08 §3).
    let lengthSec: Double?
    let canSeek: Bool
    let positionSec: Double
    let playing: Bool
    let highlights: [Double]
    /// The find, when there is one: its query, and the moment the search hit was at.
    let find: TranscriptFind?
    /// nil while the speakers cannot be changed (a transcription is running).
    let speakerActions: SpeakerActions?
    let onSeek: (Double) -> Void
    let onRemoveHighlight: (Double) -> Void
    let onCloseFind: () -> Void

    struct SpeakerActions {
        let rename: (String) -> Void
        /// The segments of the line, and who says them now — nil for a new speaker.
        let change: ([Int], String?) -> Void
    }

    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @State private var following = true
    @State private var matchIndex = 0
    @State private var matches: [TranscriptMatch] = []

    var body: some View {
        // Once per pass, not per paragraph: the body is drawn on every playback tick.
        let active = playing ? activeGroup : nil
        VStack(spacing: 0) {
            if find != nil {
                FindBar(
                    index: matchIndex, count: matches.count,
                    previous: { if !matches.isEmpty { matchIndex = (matchIndex + matches.count - 1) % matches.count } },
                    next: { if !matches.isEmpty { matchIndex = (matchIndex + 1) % matches.count } },
                    close: onCloseFind
                )
                HairLine()
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Space.s) {
                        ForEach(groups) { group in
                            row(group, active: group.id == active)
                                .id(group.id)
                        }
                    }
                    .padding(.vertical, Space.s)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .modifier(UserScroll { if playing { following = false } })
                .overlay(alignment: .bottom) {
                    if playing, !following {
                        Button {
                            following = true
                            scroll(proxy, to: activeGroup)
                        } label: {
                            Text(verbatim: RecKitStrings.localized("Back to playback"))
                                .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                                .foregroundStyle(blueprint.palette.accent)
                                .padding(.horizontal, 14)
                                .frame(height: 32)
                                .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.card))
                                .overlay { RoundedRectangle(cornerRadius: Radius.card).strokeBorder(blueprint.palette.accent, lineWidth: blueprint.line) }
                                .frame(minHeight: minTouch)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.bottom, Space.s)
                        .accessibilityIdentifier("back-to-playback")
                    }
                }
                .onChange(of: activeGroup) { _, group in
                    if playing, following { scroll(proxy, to: group) }
                }
                .onChange(of: playing) { _, now in
                    if !now { following = true }
                }
                .onChange(of: matchIndex) { _, index in
                    if matches.indices.contains(index) { scroll(proxy, to: matches[index].group) }
                }
                .task(id: FindKey(query: find?.query, groups: groups.count)) {
                    matches = TranscriptMatch.all(find?.query ?? "", in: groups)
                    // From search: the first match at or after the hit the row showed.
                    let first = find.flatMap { find in
                        matches.firstIndex { match in (groups.first { $0.id == match.group }?.start ?? 0) >= find.atSec - 0.5 }
                    } ?? 0
                    matchIndex = first
                    if matches.indices.contains(first) { scroll(proxy, to: matches[first].group, animated: false) }
                }
            }
        }
        .padding(.top, Space.s)
    }

    private struct FindKey: Hashable {
        let query: String?
        let groups: Int
    }

    private var activeGroup: Int? { TranscriptGroup.active(groups, at: positionSec)?.id }

    private func scroll(_ proxy: ScrollViewProxy, to group: Int?, animated: Bool = true) {
        guard let group else { return }
        // The upper third, so what comes next is in view.
        let anchor = UnitPoint(x: 0, y: 0.3)
        if animated, !blueprint.reduceMotion {
            withAnimation(Motion.standardAnimation(reduceMotion: false)) { proxy.scrollTo(group, anchor: anchor) }
        } else {
            proxy.scrollTo(group, anchor: anchor)
        }
    }

    private func row(_ group: TranscriptGroup, active: Bool) -> some View {
        let stamp = LedgerFormat.stamp(Int(group.start), total: lengthSec)
        let end = groups.indices.contains(group.id + 1) ? groups[group.id + 1].start : .infinity
        let marks = highlights.filter { $0 >= group.start && $0 < end }
        return VStack(alignment: .leading, spacing: Space.xs) {
            HStack(spacing: Space.xs) {
                BlueprintButton(stamp, tone: active ? .accent : .quiet, mono: true) { onSeek(group.start) }
                    .disabled(!canSeek || group.start >= seekableDurationSec)
                    .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Go to %@", LedgerFormat.clock(Int(group.start)))))
                    .accessibilityIdentifier("transcript-time-\(group.id)")
                ForEach(marks, id: \.self) { mark in
                    HighlightMenu(atSec: mark, stamp: LedgerFormat.stamp(Int(mark), total: lengthSec), go: { onSeek(mark) }, remove: { onRemoveHighlight(mark) }) {
                        // docs/09 "Highlights": the highlight square, 6×6 in the accent, right after the time
                        // and centred with it; its target is a whole 44pt square (2026-10-08 §12).
                        Rectangle()
                            .fill(blueprint.palette.accent)
                            .frame(width: 6, height: 6)
                            .frame(width: minTouch, height: minTouch)
                            .contentShape(Rectangle())
                    }
                    .padding(.horizontal, -Space.xs)
                    .accessibilityIdentifier("transcript-highlight")
                }
                if !transcript.speakers.isEmpty, !group.speaker.isEmpty {
                    let label = transcript.label(of: group.speaker)
                    if let actions = speakerActions {
                        SpeakerMenu(
                            transcript: transcript,
                            current: group.speaker,
                            rename: { actions.rename(group.speaker) },
                            change: { actions.change(group.segments, $0) }
                        ) {
                            SpeakerBadge(text: label, mono: label == group.speaker)
                        }
                        .accessibilityIdentifier("speaker-\(group.id)")
                    } else {
                        SpeakerBadge(text: label, mono: label == group.speaker)
                    }
                }
            }
            Text(marked(group))
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .textSelection(.enabled)
                // 2026-10-10 (2.13): what was said, in its own direction, whatever the app's.
                .contentDirection(group.text)
                .accessibilityIdentifier("transcript-text-\(group.id)")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Space.m)
        .overlay(alignment: .leading) {
            if active {
                Rectangle().fill(blueprint.palette.accent).frame(width: 2)
            }
        }
    }

    /// The paragraph with the find's matches on the accent, the current one stronger.
    private func marked(_ group: TranscriptGroup) -> AttributedString {
        var text = AttributedString(group.text)
        let utf16 = group.text.utf16
        for (index, match) in matches.enumerated() where match.group == group.id {
            guard match.offset >= 0, match.length > 0, match.offset + match.length <= utf16.count else { continue }
            let start = String.Index(utf16Offset: match.offset, in: group.text)
            let end = String.Index(utf16Offset: match.offset + match.length, in: group.text)
            guard let from = AttributedString.Index(start, within: text),
                  let to = AttributedString.Index(end, within: text) else { continue }
            text[from ..< to].backgroundColor = blueprint.palette.accent.opacity(index == matchIndex ? 0.36 : 0.16)
        }
        return text
    }
}

/// A scroll the user made, as against one this view made: the phase on the systems that report it,
/// a drag elsewhere.
private struct UserScroll: ViewModifier {
    let began: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18, macOS 15, *) {
            content.onScrollPhaseChange { _, phase in
                if phase == .interacting { began() }
            }
        } else {
            content.simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in began() })
        }
    }
}
#endif

/// docs/10 "Search": what the detail was opened with from a search — the query, and where its row's hit was.
public struct TranscriptFind: Equatable, Sendable {
    public let query: String
    public let atSec: Double

    public init(query: String, atSec: Double) {
        self.query = query
        self.atSec = atSec
    }
}
