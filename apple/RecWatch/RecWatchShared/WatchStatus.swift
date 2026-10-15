import Foundation

/// What the complication draws, as the app last left it (docs/13 "Apple Watch" Entry points: the
/// complication's state, and a tap that starts a recording).
///
/// It is a file in the app group rather than anything richer because a widget extension is a
/// separate process that may be woken with the app long gone: there is nobody to ask, and the last
/// thing the app wrote is the whole truth available. The extension links no `RecKit` and no core —
/// the same rule `RecPhoneWidgets` follows — so this type is Foundation only and is compiled into
/// both sides.
struct WatchStatus: Codable, Equatable {
    enum State: String, Codable {
        case idle
        case recording
    }

    var state: State = .idle
    /// When the recording on screen began, so the complication can count up without an update.
    var startedAt: Date?
    /// Recordings the phone has not acked yet (docs/03: a part is deleted only after `ack-meta
    /// ok`). Worth showing: it is the answer to "can I take the watch off yet".
    var waiting = 0
    /// Recordings the phone refused for good, kept on the watch with their audio — "Transfer failed", which
    /// nothing will send again (Wear's badge).
    var failed = 0
    /// docs/07 rule 2·3: the language the app is following — the phone's, which the watch has no
    /// setting of its own to disagree with. The extension links no RecKit and cannot read the
    /// setting itself, so it rides in the file with everything else the face is drawn from and the
    /// app rewrites it (and reloads the timelines) when it changes. Empty means the device's own.
    var language = ""

    init(state: State = .idle, startedAt: Date? = nil, waiting: Int = 0, failed: Int = 0, language: String = "") {
        self.state = state
        self.startedAt = startedAt
        self.waiting = waiting
        self.failed = failed
        self.language = language
    }

    /// A `status.json` written before [language] existed is what the complication finds after the
    /// app is updated, and a synthesized decoder would throw on the missing key — leaving the face
    /// drawn from a blank status until the app next writes one. So the language is optional, and
    /// missing means the device's own; the encoder stays synthesized.
    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = try container.decode(State.self, forKey: .state)
        // Optional, and the synthesized encoder leaves it out entirely while nothing is recording.
        startedAt = try container.decodeIfPresent(Date.self, forKey: .startedAt)
        waiting = try container.decode(Int.self, forKey: .waiting)
        failed = try container.decodeIfPresent(Int.self, forKey: .failed) ?? 0
        language = try container.decodeIfPresent(String.self, forKey: .language) ?? ""
    }
}

/// The mapping the complication is drawn from — the whole of it, so it can be checked without a
/// watch (`WatchComplicationTests`).
extension WatchStatus {
    var isRecording: Bool { state == .recording }

    /// What the face draws. docs/09 "Shape": squares, never circles — and 2026-10-11 (N4) the app's own
    /// record node rather than a system microphone: the square the watch app's Record button is.
    enum Mark: Equatable {
        /// Nothing running: the record node, a square outline with a small filled square in it.
        case record
        /// A recording running: the filled square, in the recording colour where the face allows colour.
        case recording
        /// Recordings the phone has not acked yet (the system's `arrow.up.square`).
        case waiting
        /// Recordings the phone refused for good (the system's `exclamationmark.square`).
        case failed
    }

    /// A recording being made outranks the queue: it is the one the tap is about. A transfer that failed
    /// outranks one still waiting, as on Wear's screen — nothing will retry it.
    var mark: Mark {
        if isRecording { return .recording }
        if failed > 0 { return .failed }
        return waiting > 0 ? .waiting : .record
    }

    /// A docs/07 key. Waiting recordings are said without a count, and as waiting rather than
    /// sending (docs/09 screen principle 7): what the face answers is whether any are left. While a
    /// recording runs the face shows its elapsed time instead ([elapsedSince]), and this is what
    /// VoiceOver says before it.
    var label: String {
        if isRecording { return "Recording" }
        if failed > 0 { return "Transfer failed" }
        return waiting > 0 ? "Waiting" : "Record"
    }

    /// 2026-10-11 (N4): where the running recording's clock counts from — the face draws the elapsed
    /// time in place of a word. Nil while nothing is recording, or for a file that never said when.
    var elapsedSince: Date? { isRecording ? startedAt : nil }

    /// 2026-10-11 (N3): the face's words are drawn in the sans, not the monospace, in a language written
    /// in Hangul, Kana or Han — SF Mono has none of those glyphs, and their fallback in mono's cells
    /// spaces the words apart. Read off the language rather than the words: the extension resolves its
    /// keys in SwiftUI and never holds the string.
    var wordsInSans: Bool {
        ["ko", "ja", "zh"].contains(appLocale.language.languageCode?.identifier ?? "")
    }

    /// The complication's tap: start when there is nothing running — in the app, because a long audio
    /// session started inside a widget extension is unreliable (docs/13). While one is running the tap
    /// only opens the app: a glance at the wrist must never end a recording.
    var startsOnTap: Bool { !isRecording }

    /// What the face is drawn in, as `\.locale`.
    var appLocale: Locale { language.isEmpty ? .current : Locale(identifier: language) }
}

/// Where [WatchStatus] lives. The app group is the only way a widget extension can read what the
/// app wrote; without one the complication would have no state to show at all.
enum WatchStatusStore {
    static let appGroup = "group.app.recly.watch"

    /// `nil` when the app group is not provisioned — the complication then draws the idle face
    /// rather than nothing, and the app carries on recording.
    static var url: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("status.json")
    }

    static func load() -> WatchStatus {
        guard let url, let data = try? Data(contentsOf: url) else { return WatchStatus() }
        return (try? JSONDecoder().decode(WatchStatus.self, from: data)) ?? WatchStatus()
    }

    static func save(_ status: WatchStatus) {
        guard let url, let data = try? JSONEncoder().encode(status) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
