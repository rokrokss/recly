import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// docs/13 "Display"·I7: what Recly puts outside the app — the Live Activity a recording shows on the
/// Lock Screen and in the Dynamic Island, and the iOS 18 Control that starts one.
///
/// The extension links no core and opens no audio session (docs/13: a widget extension may not
/// start one). Both of its buttons are App Intents that run in the app: the stop is a
/// `LiveActivityIntent`, the start opens the app.
///
/// docs/07 rule 3: the extension is a separate process with no way to read the app's language
/// setting — there is no app group here — so the pill carries it in its content state and every
/// branch that draws words hands it down as `\.locale`.
@main
struct ReclyWidgets: WidgetBundle {
    var body: some Widget {
        RecordingLiveActivityWidget()
        RecordWidget()
        if #available(iOS 18.0, *) {
            StartRecordingControl()
        }
    }
}

struct RecordingLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingActivityAttributes.self) { context in
            HStack(spacing: 16) {
                Label {
                    Text("Recording")
                        .font(.system(.subheadline, weight: .semibold))
                } icon: {
                    // docs/09 "Shape": a filled square, not a circle — the same mark the recording
                    // node on the phone's dashboard wears.
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.badge)
                        .fill(WidgetTokens.danger)
                        .frame(width: 12, height: 12)
                }
                Spacer()
                elapsed(context.state.startedAt)
                    .font(.system(.title3, design: .monospaced))
                stopButton
            }
            .padding()
            // docs/09 "Tokens": the palette's own page black rather than a translucent system one.
            .activityBackgroundTint(WidgetTokens.background.opacity(0.6))
            .environment(\.locale, context.state.appLocale)
            .environment(\.layoutDirection, context.state.appLocale.language.characterDirection == .rightToLeft ? .rightToLeft : .leftToRight)
        } dynamicIsland: { context in
            // `DynamicIsland` is not a view and takes no modifier of its own, so the locale is
            // handed to each region that draws words or numbers — the Lock Screen is not the only
            // place this pill is read (docs/07 rule 3).
            let locale = context.state.appLocale
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    recordMark
                }
                DynamicIslandExpandedRegion(.center) {
                    elapsed(context.state.startedAt)
                        .font(.system(.title2, design: .monospaced))
                        .environment(\.locale, locale)
                        .environment(\.layoutDirection, locale.language.characterDirection == .rightToLeft ? .rightToLeft : .leftToRight)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    stopButton.environment(\.locale, locale)
                        .environment(\.layoutDirection, locale.language.characterDirection == .rightToLeft ? .rightToLeft : .leftToRight)
                }
            } compactLeading: {
                recordMark
            } compactTrailing: {
                elapsed(context.state.startedAt)
                    .font(.system(.caption, design: .monospaced))
                    .environment(\.locale, locale)
                    .environment(\.layoutDirection, locale.language.characterDirection == .rightToLeft ? .rightToLeft : .leftToRight)
            } minimal: {
                recordMark
            }
        }
    }

    /// docs/09 "Shape": the recording mark is a square, everywhere it appears.
    private var recordMark: some View {
        RoundedRectangle(cornerRadius: WidgetTokens.Radius.badge)
            .fill(WidgetTokens.danger)
            .frame(width: 12, height: 12)
    }

    /// Counted by the system from the recording's start, so a three-hour recording needs no update
    /// to keep the number right (and ActivityKit rations updates).
    private func elapsed(_ startedAt: Date) -> some View {
        Text(timerInterval: startedAt ... Date.distantFuture, countsDown: false)
    }

    /// docs/09 "Shape": square and bordered, never a pill — the same button the phone draws.
    private var stopButton: some View {
        Button(intent: StopRecordingIntent()) {
            Text("Stop")
                .font(.system(.subheadline, weight: .medium))
                .foregroundStyle(WidgetTokens.danger)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .overlay {
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.node)
                        .stroke(WidgetTokens.danger, lineWidth: 1)
                }
                // docs/09 "Accessibility": the label is small, what you tap is not. The border keeps its
                // size and grows a 44×44 target around itself — this button is only drawn on the
                // Lock Screen and in the expanded island, where there is room for one; the compact
                // regions draw the mark and the timer and nothing tappable.
                .frame(minWidth: WidgetTokens.minTouch, minHeight: WidgetTokens.minTouch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// docs/13 I7: the iOS 18 Control Center / Lock Screen control. It only opens the app — the audio
/// session belongs there.
@available(iOS 18.0, *)
struct StartRecordingControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "app.recly.control.record") {
            ControlWidgetButton(action: StartRecordingIntent()) {
                // docs/09 "Shape": the recording mark is a square, everywhere it appears — a
                // circle is the one shape this design does not draw.
                Label("Start recording", systemImage: "smallcircle.filled.square")
            }
        }
        .displayName("Recly recording")
        .description("Opens Recly and starts a recording.")
    }
}

/// docs/09 §10: the Home Screen and Lock Screen `Record` widget — a square record node, or while a
/// recording runs its timer and a stop node. Both run the app's own intents: the start opens the app,
/// where the audio session belongs, and the stop runs in the app's process.
struct RecordWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: PhoneStatusStore.widgetKind, provider: RecordTimeline()) { entry in
            RecordWidgetView(status: entry.status)
                .environment(\.locale, entry.status.appLocale)
                .containerBackground(for: .widget) { WidgetTokens.background }
        }
        .configurationDisplayName("Record")
        .description("Start or stop a recording.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

struct RecordEntry: TimelineEntry {
    let date: Date
    let status: PhoneStatus
}

/// One entry, from the file the app last wrote; the app reloads the timeline at every start and stop.
struct RecordTimeline: TimelineProvider {
    func placeholder(in context: Context) -> RecordEntry { RecordEntry(date: .now, status: PhoneStatus()) }

    func getSnapshot(in context: Context, completion: @escaping (RecordEntry) -> Void) {
        completion(RecordEntry(date: .now, status: PhoneStatusStore.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<RecordEntry>) -> Void) {
        completion(Timeline(entries: [RecordEntry(date: .now, status: PhoneStatusStore.load())], policy: .never))
    }
}

struct RecordWidgetView: View {
    let status: PhoneStatus
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            toggle {
                ZStack {
                    AccessoryWidgetBackground()
                    if let startedAt = status.startedAt, status.recording {
                        Text(timerInterval: startedAt ... Date.distantFuture, countsDown: false)
                            .font(.system(.caption2, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .minimumScaleFactor(0.6)
                            .padding(4)
                    } else {
                        Image(systemName: "dot.square")
                            .font(.title2)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(status.recording ? Text("Stop recording") : Text("Start recording"))
        case .accessoryRectangular:
            toggle {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: "Recly").font(.headline)
                    if let startedAt = status.startedAt, status.recording {
                        Text(timerInterval: startedAt ... Date.distantFuture, countsDown: false)
                            .font(.system(.body, design: .monospaced))
                    } else {
                        Text("Record")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(status.recording ? Text("Stop recording") : Text("Start recording"))
        default:
            small
        }
    }

    /// docs/09 "Shape": the square record node of the dashboard; while recording, the clock over a
    /// stop node in the same place.
    private var small: some View {
        VStack(spacing: 10) {
            if let startedAt = status.startedAt, status.recording {
                Text(timerInterval: startedAt ... Date.distantFuture, countsDown: false)
                    .font(.system(.title3, design: .monospaced))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
            } else {
                Text(verbatim: "Recly")
                    .font(.system(.subheadline, weight: .semibold))
                    .foregroundStyle(.white)
            }
            toggle {
                ZStack {
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.node)
                        .fill(status.recording ? WidgetTokens.danger : Color.clear)
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.node)
                        .strokeBorder(WidgetTokens.danger, lineWidth: 3)
                    RoundedRectangle(cornerRadius: WidgetTokens.Radius.badge)
                        .fill(status.recording ? WidgetTokens.background : WidgetTokens.danger)
                        .frame(width: 20, height: 20)
                }
                .frame(width: 64, height: 64)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(status.recording ? Text("Stop recording") : Text("Start recording"))
        }
    }

    /// The stop while recording, the start otherwise — one label for either.
    @ViewBuilder
    private func toggle<Label: View>(@ViewBuilder label: () -> Label) -> some View {
        let content = label()
        if status.recording {
            Button(intent: StopRecordingIntent()) { content }
        } else {
            Button(intent: StartRecordingIntent()) { content }
        }
    }
}
