import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// docs/13 "표시"·I7: what Recly puts outside the app — the Live Activity a recording shows on the
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
                    workflowName(context.attributes.workflowName)
                        .font(.system(.subheadline, weight: .semibold))
                } icon: {
                    // docs/09 "형태": a filled square, not a circle — the same mark the recording
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
            // docs/09 "토큰": the palette's own page black rather than a translucent system one.
            .activityBackgroundTint(WidgetTokens.background.opacity(0.6))
            .environment(\.locale, context.state.appLocale)
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
                }
                DynamicIslandExpandedRegion(.trailing) {
                    stopButton.environment(\.locale, locale)
                }
            } compactLeading: {
                recordMark
            } compactTrailing: {
                elapsed(context.state.startedAt)
                    .font(.system(.caption, design: .monospaced))
                    .environment(\.locale, locale)
            } minimal: {
                recordMark
            }
        }
    }

    /// docs/09 "형태": the recording mark is a square, everywhere it appears.
    private var recordMark: some View {
        RoundedRectangle(cornerRadius: WidgetTokens.Radius.badge)
            .fill(WidgetTokens.danger)
            .frame(width: 12, height: 12)
    }

    /// The user's own text, or the word for a recording started with no workflow picked.
    private func workflowName(_ name: String?) -> Text {
        name.map(Text.init(verbatim:)) ?? Text("Recording")
    }

    /// Counted by the system from the recording's start, so a three-hour recording needs no update
    /// to keep the number right (and ActivityKit rations updates).
    private func elapsed(_ startedAt: Date) -> some View {
        Text(timerInterval: startedAt ... Date.distantFuture, countsDown: false)
    }

    /// docs/09 "형태": square and bordered, never a pill — the same button the phone draws.
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
                // docs/09 "접근성": the label is small, what you tap is not. The border keeps its
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
                // docs/09 "형태": the recording mark is a square, everywhere it appears — a
                // circle is the one shape this design does not draw.
                Label("Start recording", systemImage: "smallcircle.filled.square")
            }
        }
        .displayName("Recly recording")
        .description("Opens Recly and starts a recording.")
    }
}
