import ActivityKit
import Foundation

/// docs/13 "Display": the Live Activity a running recording shows on the Lock Screen, in the Dynamic
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

        /// docs/13 "iPhone" Display: the recording is the paired Apple Watch's — `Recording on the watch` where the
        /// stop button was, because there is nothing on the phone to stop.
        var onWatch = false

        /// docs/07 rule 3: the app's language. The extension is a separate process and there is no
        /// app group here, so this is the only way its `Text` can be drawn in the language the user
        /// picked — and it is part of the *state* rather than of the attributes, which are fixed
        /// for the life of an activity, so a language changed mid-recording reaches the pill on the
        /// next update instead of waiting for the next recording. Empty means the device's own.
        var language = ""

        var appLocale: Locale { language.isEmpty ? .current : Locale(identifier: language) }

        init(startedAt: Date, onWatch: Bool = false, language: String = "") {
            self.startedAt = startedAt
            self.onWatch = onWatch
            self.language = language
        }

        /// An activity started by an earlier build has a state with no `language` in it, or no
        /// `onWatch`, and ActivityKit decodes that stored state into *this* type when the app is
        /// replaced under a running recording: a synthesized decoder would throw and the pill would be
        /// lost mid-recording. The keys are optional here — the device's own language, and the phone's
        /// own recording, which is all an earlier build ever showed; the encoder stays synthesized, so
        /// what is written from now on always carries them.
        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            startedAt = try container.decode(Date.self, forKey: .startedAt)
            onWatch = try container.decodeIfPresent(Bool.self, forKey: .onWatch) ?? false
            language = try container.decodeIfPresent(String.self, forKey: .language) ?? ""
        }
    }
}
