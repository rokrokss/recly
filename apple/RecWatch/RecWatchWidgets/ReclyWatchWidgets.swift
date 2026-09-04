import AppIntents
import SwiftUI
import WidgetKit

/// docs/13 "Apple Watch" 진입점: the complication — the state, and a tap that starts a recording.
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
        // The tap is the recording's own switch: start when there is nothing running, stop when
        // there is (`WatchStatus.startsOnTap`).
        if status.startsOnTap {
            Button(intent: StartWatchRecordingIntent()) { face }
                .buttonStyle(.plain)
        } else {
            Button(intent: StopWatchRecordingIntent()) { face }
                .buttonStyle(.plain)
        }
    }

    /// docs/09 "Raw 미학": the complication says the state in monospace — it is a status code on a
    /// watch face, not a headline.
    @ViewBuilder
    private var face: some View {
        switch family {
        case .accessoryRectangular:
            Label { title } icon: { Image(systemName: status.symbol) }
                .font(.system(.body, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .environment(\.locale, status.appLocale)

        default:
            Image(systemName: status.symbol)
                .font(.system(size: WidgetTokens.iconSize, weight: .light))
                .widgetLabel {
                    title.font(.system(.caption2, design: .monospaced))
                }
                .environment(\.locale, status.appLocale)
        }
    }

    /// docs/07 rule 2·3: the extension links no RecKit and cannot read the app's language setting
    /// itself, so the app writes the language it is following into the status file and the face
    /// hands it down as `\.locale` — which is what resolves these keys.
    private var title: Text {
        status.waiting > 0 && !status.isRecording
            ? Text("Sending \(status.waiting)")
            : Text(LocalizedStringKey(status.label))
    }
}
