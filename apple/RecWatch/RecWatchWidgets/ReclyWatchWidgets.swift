import AppIntents
import SwiftUI
import WidgetKit

/// docs/13 "Apple Watch" Entry points: the complication — the state, and a tap that starts a
/// recording (or, while one runs, opens the app).
///
/// The extension links no `RecKit` and no core, for the reason `RecPhoneWidgets` gives: it would
/// carry the whole database with it for the sake of one status line, and the watch has a 75 MB
/// budget to keep. What it reads is the file the app writes into the app group
/// (`WatchStatus`), and what its button does is run an App Intent *in the app*, because the audio
/// session belongs there.
@main
struct ReclyWatchWidgets: WidgetBundle {
    var body: some Widget {
        RecordingComplication()
    }
}

struct RecordingComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "app.recly.watch.complication", provider: StatusProvider()) { entry in
            ComplicationFace(status: entry.status)
        }
        .configurationDisplayName("Recly")
        .description("Shows the recording state; tap to start.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryRectangular])
    }
}

struct StatusEntry: TimelineEntry {
    let date: Date
    let status: WatchStatus
}

/// One entry and `.never`: nothing here changes on a clock, and the app reloads the timeline every
/// time the recorder's state or the queue's depth moves.
struct StatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: .now, status: WatchStatus())
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        completion(entry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        completion(Timeline(entries: [entry()], policy: .never))
    }

    private func entry() -> StatusEntry {
        StatusEntry(date: .now, status: WatchStatusStore.load())
    }
}

struct ComplicationFace: View {
    @Environment(\.widgetFamily) private var family
    let status: WatchStatus

    var body: some View {
        // The tap starts a recording when there is nothing running; while one runs it is the face alone,
        // and a tap on a complication with no button of its own opens the app — it never stops anything
        // (`WatchStatus.startsOnTap`).
        Group {
            if status.startsOnTap {
                Button(intent: StartWatchRecordingIntent()) { face }
                    .buttonStyle(.plain)
            } else {
                face
            }
        }
        .accessibilityLabel(spoken)
        .environment(\.locale, status.appLocale)
        .environment(\.layoutDirection, status.appLocale.language.characterDirection == .rightToLeft ? .rightToLeft : .leftToRight)
    }

    /// docs/09 "Raw aesthetics": the complication says the state in monospace — it is a status code on a
    /// watch face, not a headline. 2026-10-11 (N4): the app's square record mark rather than a system
    /// glyph, and while a recording runs its elapsed time in place of the word, where the family has
    /// room for a line: beside the mark on the rectangular face, as the curved label on the corner and on
    /// a circular face that shows one. A circular face without a label keeps the mark alone.
    @ViewBuilder
    private var face: some View {
        switch family {
        case .accessoryRectangular:
            HStack(spacing: 6) {
                ComplicationMark(mark: status.mark)
                line.font(font(.body))
            }
            .frame(maxWidth: .infinity, alignment: .leading)

        default:
            ComplicationMark(mark: status.mark)
                .widgetLabel {
                    line.font(font(.caption2))
                }
        }
    }

    /// The elapsed time while recording — counted by the system from the recording's start, so a long
    /// recording needs no timeline update (the phone's widgets do the same) — and the state's word
    /// otherwise.
    private var line: Text {
        if let since = status.elapsedSince {
            return Text(timerInterval: since ... Date.distantFuture, countsDown: false)
        }
        return title
    }

    /// Monospace, except for a word in a language written in Hangul, Kana or Han ([WatchStatus.wordsInSans]):
    /// the time is always monospace.
    private func font(_ style: Font.TextStyle) -> Font {
        status.elapsedSince == nil && status.wordsInSans ? .system(style) : .system(style, design: .monospaced)
    }

    /// docs/07 rule 2·3: the extension links no RecKit and cannot read the app's language setting
    /// itself, so the app writes the language it is following into the status file and the face
    /// hands it down as `\.locale` — which is what resolves these keys.
    private var title: Text {
        Text(LocalizedStringKey(status.label))
    }

    /// What VoiceOver says: what the tap does while nothing runs (`Start recording`, with the waiting
    /// state after it), and the recording with its time while one does (`Recording, 0:12`) — the
    /// timer read live rather than a time frozen into the entry.
    private var spoken: Text {
        if let since = status.elapsedSince {
            return title + Text(verbatim: ", ") + Text(timerInterval: since ... Date.distantFuture, countsDown: false)
        }
        if status.mark == .waiting || status.mark == .failed {
            return Text("Start recording") + Text(verbatim: ", ") + title
        }
        return Text("Start recording")
    }
}

/// docs/09 "Shape" · 2026-10-11 (N4): the watch app's own Record button at glyph size — a square node with
/// a small filled square in it — and while recording the filled square in the recording's red, which a
/// tinted face draws in its accent. Waiting keeps the system's square arrow, a failed transfer its square
/// exclamation mark.
struct ComplicationMark: View {
    let mark: WatchStatus.Mark

    var body: some View {
        Group {
            switch mark {
            case .record:
                ZStack {
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.node)
                        .strokeBorder(lineWidth: 2)
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.badge)
                        .frame(width: 7, height: 7)
                }
                .frame(width: WidgetTokens.iconSize, height: WidgetTokens.iconSize)
            case .recording:
                RoundedRectangle(cornerRadius: WidgetTokens.Radius.node)
                    .fill(WidgetTokens.danger)
                    .frame(width: WidgetTokens.iconSize, height: WidgetTokens.iconSize)
                    .widgetAccentable()
            case .waiting:
                Image(systemName: "arrow.up.square")
                    .font(.system(size: WidgetTokens.iconSize, weight: .light))
            case .failed:
                Image(systemName: "exclamationmark.square")
                    .font(.system(size: WidgetTokens.iconSize, weight: .light))
            }
        }
        .accessibilityHidden(true)
    }
}
