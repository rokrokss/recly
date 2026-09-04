import Foundation

/// What the complication draws, as the app last left it (docs/13 "Apple Watch" 진입점: the
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
    /// docs/07 rule 2·3: the language the app is following — the phone's, which the watch has no
    /// setting of its own to disagree with. The extension links no RecKit and cannot read the
    /// setting itself, so it rides in the file with everything else the face is drawn from and the
    /// app rewrites it (and reloads the timelines) when it changes. Empty means the device's own.
    var language = ""

    init(state: State = .idle, startedAt: Date? = nil, waiting: Int = 0, language: String = "") {
        self.state = state
        self.startedAt = startedAt
        self.waiting = waiting
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
        language = try container.decodeIfPresent(String.self, forKey: .language) ?? ""
    }
}

/// The mapping the complication is drawn from — the whole of it, so it can be checked without a
/// watch (`WatchComplicationTests`).
extension WatchStatus {
    var isRecording: Bool { state == .recording }

    /// A recording being made outranks recordings waiting to go: it is the one the tap acts on.
    ///
    /// docs/09 "형태": squares, never circles — the recording mark on every other surface of this
    /// product is a filled square, and a complication is not the place it becomes a dot.
    var symbol: String {
        if isRecording { return "square.fill" }
        return waiting > 0 ? "arrow.up.square" : "mic"
    }

    /// A docs/07 key. The waiting one carries a count, which a key cannot, so the face formats
    /// that branch itself — this names the key it uses.
    var label: String {
        if isRecording { return "Recording" }
        return waiting > 0 ? "Sending %lld" : "Record"
    }

    /// The complication's tap: start when there is nothing running, stop when there is. Both run in
    /// the app — a long audio session started inside a widget extension is unreliable (docs/13).
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
