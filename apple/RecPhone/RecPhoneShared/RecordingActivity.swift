import ActivityKit
import Foundation

/// docs/13 "표시": the Live Activity a running recording shows on the Lock Screen, in the Dynamic
/// Island and in the watch's Smart Stack — the elapsed time and a stop button. It is also what
/// App Review 2.5.14 asks for: a recording the user can see is running.
///
/// Shared by the app, which starts and ends it, and the widget extension, which draws it — so it
/// carries only what a lock screen needs and nothing that would drag the core into an extension.
///
/// [ContentState] is deliberately one date rather than a running clock: the widget counts up from
/// it with `Text(timerInterval:)`, so a three-hour recording needs no update at all — and updates
/// are the thing ActivityKit rations.
struct RecordingActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var startedAt: Date

        /// docs/07 rule 3: the app's language. The extension is a separate process and there is no
        /// app group here, so this is the only way its `Text` can be drawn in the language the user
        /// picked — and it is part of the *state* rather than of the attributes, which are fixed
        /// for the life of an activity, so a language changed mid-recording reaches the pill on the
        /// next update instead of waiting for the next recording. Empty means the device's own.
        var language = ""

        var appLocale: Locale { language.isEmpty ? .current : Locale(identifier: language) }

        init(startedAt: Date, language: String = "") {
            self.startedAt = startedAt
            self.language = language
        }

        /// An activity started by the build before this one has a state with no `language` in it,
        /// and ActivityKit decodes that stored state into *this* type when the app is replaced
        /// under a running recording: a synthesized decoder would throw and the pill would be lost
        /// mid-recording. The key is optional here and defaults to the device's own language; the
        /// encoder stays synthesized, so what is written from now on always carries it.
        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            startedAt = try container.decode(Date.self, forKey: .startedAt)
            language = try container.decodeIfPresent(String.self, forKey: .language) ?? ""
        }
    }

    /// The workflow the user picked when they started, for the pill to name. `nil` is the source's
    /// default (ADR-016), which has no name worth showing.
    var workflowName: String?
}
