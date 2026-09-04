import AVFoundation
import Foundation
import ReclyCore

/// docs/08 "결과 파일": which of the files beside `meta.json` the detail plays back, and in what
/// order. A pure choice over `meta`, so it can be checked without a disk or a player — the shell
/// only hands it the recording's directory and a way to ask whether a file is there.
public enum RecordingPlaylist {
    /// The local audio of one recording, in play order: what to play, and how long each part is.
    /// The durations are the ones `meta.json` recorded, which is what the elapsed clock counts in —
    /// asking AVFoundation would mean loading every file before the first one could start.
    public struct Selection: Equatable, Sendable {
        public let urls: [URL]
        public let durations: [Double]

        public static let empty = Selection(urls: [], durations: [])

        public var isEmpty: Bool { urls.isEmpty }
        public var totalSec: Double { durations.reduce(0, +) }
    }

    /// The one track a person means by "play this": the mix if the recording has one — a meeting's
    /// mic and system audio already summed — and otherwise the single `mono` track a memo is.
    /// `mic`/`sys` are the mix's own ingredients and are never played on their own here.
    public static func playedTrack(tracks: [Track]) -> Track {
        tracks.contains(Track.mix) ? Track.mix : Track.mono
    }

    /// A part whose file is not on this device is dropped rather than played as silence: what the
    /// retention sweep took leaves a playlist with a gap in it (docs/03 ADR-017), which the detail
    /// either fills from Drive ([fetchesFromDrive]) or says in words.
    public static func select(
        tracks: [Track],
        parts: [Part_],
        dir: URL,
        exists: (URL) -> Bool
    ) -> Selection {
        let track = playedTrack(tracks: tracks)
        let kept = parts
            .filter { $0.track == track }
            .sorted { $0.part < $1.part }
            .map { (url: dir.appendingPathComponent($0.file), durationSec: $0.durationSec) }
            .filter { exists($0.url) }
        return Selection(urls: kept.map(\.url), durations: kept.map(\.durationSec))
    }

    /// docs/03 ADR-017: the local parts are a seven-day cache now, so a gap in [select]'s playlist
    /// is not necessarily a gap in the recording — Drive keeps every part it was given, and the
    /// detail fetches back what the sweep took.
    ///
    /// The trip is only made when there is a gap *and* Drive has the parts to fill it with: a
    /// recording that never reached Drive has nothing there to ask for, and "No audio on this
    /// device" is still the true sentence about it.
    ///
    /// - Parameters:
    ///   - local: how many parts of the played track this device still has.
    ///   - track: how many the played track has in `meta.json`.
    ///   - uploaded: whether Drive holds every part (`core.uploaded`).
    public static func fetchesFromDrive(local: Int, track: Int, uploaded: Bool) -> Bool {
        local < track && uploaded
    }

    /// The same playlist, rebuilt out of what `core.audio` came back with. A fetched part is
    /// written into the recording's own directory under the name its row gives it, so the
    /// durations the clock counts in are still `meta.json`'s.
    ///
    /// A fetch that could not bring every part back stops at the gap: the playlist is the parts
    /// from the first one up to the one that stayed missing, and nothing after it. Playing on past
    /// a gap would put every later part on the clock at the wrong time — the transcript below reads
    /// against that clock, so 10:00 would index some other moment of the recording. Keeping the
    /// timeline instead (each part played at its `startOffsetSec`) would mean silence between two
    /// queue items, which `AVQueuePlayer` has nothing to make out of; the contiguous prefix is what
    /// can be played correctly, and the detail says in words that the rest could not be fetched.
    ///
    /// - Parameters:
    ///   - parts: the played track's parts, for the order and the durations.
    ///   - files: the file names `core.audio` handed back.
    public static func fetched(parts: [Part_], files: [String], dir: URL) -> Selection {
        let present = Set(files)
        let kept = parts
            .sorted { $0.part < $1.part }
            .prefix { present.contains($0.file) }
        return Selection(
            urls: kept.map { dir.appendingPathComponent($0.file) },
            durations: kept.map(\.durationSec)
        )
    }
}

/// The detail's playback: the parts of one track, end to end, as the one thing the recording was.
///
/// `AVQueuePlayer` is what makes that one thing out of several files — it advances itself, with no
/// gap to hear between two parts of the same take. Several files is also what makes [seek] more
/// than one line: a second of the recording is a second of one particular part, and the queue may
/// have to be rebuilt around it.
@MainActor
public final class RecordingPlayer: ObservableObject {
    @Published public private(set) var isPlaying = false
    /// Seconds from the start of the *recording*, not of the part being played.
    @Published public private(set) var positionSec: Double = 0

    /// How often the playhead is moved while playing: 30 steps a second, so the bar slides rather
    /// than steps. Finer would be redraws no screen this runs on can show.
    public static let tickSec: Double = 1.0 / 30

    private var selection = RecordingPlaylist.Selection.empty
    private var queue: Queue?

    public init() {}

    /// A different recording (or none): whatever was playing stops first, and the queue is the new
    /// one. Building it is left to [play], so picking a row does not touch the audio session.
    ///
    /// Called again at the press of Play, because [stop] leaves the player holding nothing: the
    /// selection that plays is always the one the bar is showing at that moment.
    public func load(_ selection: RecordingPlaylist.Selection) {
        guard selection != self.selection else { return }
        stop()
        self.selection = selection
    }

    public func play() {
        guard !selection.isEmpty else { return }
        (queue ?? makeQueue()).play()
        isPlaying = true
    }

    public func pause() {
        queue?.pause()
        isPlaying = false
    }

    /// Everything that ends playback ends here: the end of the last part, another recording picked,
    /// the surface closed or going away. Idempotent — a player with nothing going has nothing to
    /// stop, and calling it twice is calling it once.
    ///
    /// The next [play] builds the queue again rather than seeking a drained one, which is also what
    /// makes the end of the recording a return to its start.
    ///
    /// What it was going to play goes too. The Mac keeps one player behind a detail view whose
    /// model is swapped per pick, and the new model's bar can offer Play before its own audio is
    /// settled — a player still holding the last recording's selection would answer that press with
    /// the wrong recording. Nothing plays that the caller has not just [load]ed.
    public func stop() {
        queue?.tearDown()
        queue = nil
        selection = .empty
        positionSec = 0
        isPlaying = false
    }

    /// docs/09 화면 원칙 2: a drag on the waveform, or a step of the adjustable action behind it.
    /// The second is the *recording's*, so the first thing it is turned into is a part and an
    /// offset into it ([target]).
    ///
    /// Three cases, and the difference between them is only how much of the queue has to change. A
    /// queue that already has the target part current is seeked where it stands. One that is
    /// somewhere else in the recording is given the items from the target part onwards, because a
    /// queue player cannot be sent backwards through the items it has already drained — the player
    /// itself is kept, so the audio session is neither handed back nor taken again ([Queue.reload]).
    /// And a player that has never been started (or was [stop]ped) gets its queue built here
    /// without being played — pressing Play after a scrub starts from where the scrub left it,
    /// not from zero.
    public func seek(toSec sec: Double) {
        guard !selection.isEmpty else { return }
        let sec = min(max(0, sec), selection.totalSec)
        let target = Self.target(durations: selection.durations, sec: sec)
        // Ahead of the next tick, which does not come at all while the player is paused: the bar
        // is showing the second the finger let go of.
        positionSec = sec
        guard let queue else {
            makeQueue(from: target.index).seek(toSec: target.offsetSec)
            return
        }
        if selection.urls.count - queue.remaining != target.index {
            queue.reload(urls: urls(from: target.index))
            queue.seek(toSec: target.offsetSec)
            // Emptying the queue drops the rate with it, so a player that was going has to be
            // started again — the session it is already holding makes that no more than a `play()`.
            if isPlaying { queue.play() }
            return
        }
        queue.seek(toSec: target.offsetSec)
    }

    /// Where the recording's own clock is: the parts already played, plus how far into the current
    /// one the player is. Pure, because it is the one piece of this worth checking without a file.
    nonisolated static func position(durations: [Double], finished: Int, itemSec: Double) -> Double {
        let played = durations.prefix(max(0, finished)).reduce(0, +)
        return played + (itemSec.isFinite ? max(0, itemSec) : 0)
    }

    /// The other half of [position], and its inverse: which part a second of the recording is in,
    /// and how far into that part. The end of a part is the next part's 0 rather than that part's
    /// last instant, so a scrub to a boundary plays on instead of stopping there. Past the end
    /// clamps to the end of the last part, which is where [seek]'s own clamp already puts it.
    nonisolated static func target(durations: [Double], sec: Double) -> (index: Int, offsetSec: Double) {
        guard let last = durations.indices.last else { return (0, 0) }
        var offsetSec = max(0, sec)
        for (index, durationSec) in durations.enumerated() {
            if offsetSec < durationSec { return (index, offsetSec) }
            offsetSec -= durationSec
        }
        return (last, durations[last])
    }

    /// The parts from [index] to the end of the recording — what a queue holds after a seek into
    /// the middle of it.
    private func urls(from index: Int) -> [URL] {
        Array(selection.urls[min(max(0, index), selection.urls.count - 1)...])
    }

    /// From [index] onwards rather than always from the start, so [seek] can put the queue at a
    /// part the current one has already gone past.
    ///
    /// [tick]'s arithmetic needs nothing said about that: what is left in a queue built from a
    /// suffix is what is left of the *recording* too, so `urls.count - remaining` is still the
    /// number of parts finished.
    private func makeQueue(from index: Int = 0) -> Queue {
        let queue = Queue(
            urls: urls(from: index),
            tick: { [weak self] itemSec, remaining in
                MainActor.assumeIsolated { self?.tick(itemSec: itemSec, remaining: remaining) }
            },
            ended: { [weak self] in
                MainActor.assumeIsolated { self?.stop() }
            }
        )
        self.queue = queue
        return queue
    }

    private func tick(itemSec: Double, remaining: Int) {
        // What is left in the queue is the current item and the ones after it.
        positionSec = Self.position(
            durations: selection.durations,
            finished: selection.urls.count - remaining,
            itemSec: itemSec
        )
    }
}

/// The AVFoundation half of [RecordingPlayer] — the player, the two observers, and the audio
/// session it borrowed — in an object that is *not* on the main actor.
///
/// That is the point of it: `deinit` is nonisolated and cannot call a `@MainActor` method, so a
/// player that goes away without a `stop()` (a window closed while a part is playing) would
/// otherwise leave a periodic observer, a notification registration and an active session behind.
/// Here the last release tears all three down wherever it happens.
private final class Queue {
    private let player: AVQueuePlayer
    private let ended: () -> Void
    private var ticker: Any?
    private var end: NSObjectProtocol?
    #if os(iOS)
    /// Whether *this* player is the one that put the session into playback, and so the one to hand
    /// it back. See [activateSession].
    private var borrowedSession = false
    #endif

    init(urls: [URL], tick: @escaping (Double, Int) -> Void, ended: @escaping () -> Void) {
        let items = urls.map { AVPlayerItem(url: $0) }
        let player = AVQueuePlayer(items: items)
        player.actionAtItemEnd = .advance
        self.player = player
        self.ended = ended
        // A frame's worth: the clock beside the bar only counts whole seconds, but the playhead on
        // the waveform is drawn at this position, and at four steps a second it jumps rather than
        // moves (docs/09 "모션": what moves, moves).
        ticker = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: RecordingPlayer.tickSec, preferredTimescale: 600),
            queue: .main
        ) { [weak player] time in
            tick(time.seconds, player?.items().count ?? 0)
        }
        observeEnd(of: items.last)
    }

    deinit { tearDown() }

    /// The current item and the ones after it — what [RecordingPlayer.tick] counts the finished
    /// parts from, asked for outside a tick because [RecordingPlayer.seek] needs it at the press.
    var remaining: Int { player.items().count }

    /// The same player at another part of the recording (see [RecordingPlayer.seek]), because a
    /// queue player cannot be sent back to an item it has already drained.
    ///
    /// The *items* and nothing else: tearing this down and building another would hand the audio
    /// session back with `notifyOthersOnDeactivation` and take it again a moment later, which is
    /// heard as whatever else was playing coming back for that instant. The ticker goes on
    /// reporting through it, so the clock does not blink either.
    func reload(urls: [URL]) {
        player.removeAllItems()
        let items = urls.map { AVPlayerItem(url: $0) }
        for item in items { player.insert(item, after: nil) }
        observeEnd(of: items.last)
    }

    /// Seconds into the *current item*, not into the recording. Both tolerances are zero because
    /// the transcript below is indexed against this clock: landing on the nearest keyframe would
    /// put the playhead somewhere other than where the finger was.
    func seek(toSec sec: Double) {
        player.seek(
            to: CMTime(seconds: sec, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func play() {
        activateSession()
        player.play()
    }

    func pause() {
        player.pause()
        releaseSession()
    }

    /// The queue drains as it plays, so the end of the whole recording is the end of its last part
    /// — after which `AVQueuePlayer` has nothing current and would report no time at all. The last
    /// part is a different item after a [reload], and only one of them is ever watched.
    private func observeEnd(of item: AVPlayerItem?) {
        if let end { NotificationCenter.default.removeObserver(end) }
        end = item.map { last in
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: last,
                queue: .main
            ) { [ended] _ in ended() }
        }
    }

    /// Idempotent, and safe from `deinit`: nothing here is main-actor state.
    func tearDown() {
        if let ticker { player.removeTimeObserver(ticker) }
        ticker = nil
        if let end { NotificationCenter.default.removeObserver(end) }
        end = nil
        player.pause()
        releaseSession()
    }

    #if os(iOS)
    /// docs/09: playback is the point of pressing Play, so the session says so — without a category
    /// of `.playback` the phone plays nothing while it is on silent.
    ///
    /// Except when the recorder already owns the session. `.playAndRecord` is what a recording in
    /// progress runs in, and playback is allowed inside it — but *setting* a category of `.playback`
    /// on top of it takes the input away and ends the recording. So the category is only ever
    /// changed when it is something else, and the session is only ever handed back by the player
    /// that took it ([borrowedSession]), never by one that merely played inside someone else's.
    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        guard session.category != .playAndRecord else { return }
        if session.category != .playback {
            try? session.setCategory(.playback)
        }
        try? session.setActive(true)
        borrowedSession = true
    }

    /// Handed back as soon as there is nothing to play, so audio the user paused for this can come
    /// back on its own. Handed back only if it is still ours: a recording that started while this
    /// was playing (a Shortcut, the watch) has since taken the session as `.playAndRecord`, and
    /// deactivating it then would cut that recording off — so the category is checked again here,
    /// not only the flag.
    private func releaseSession() {
        guard borrowedSession else { return }
        borrowedSession = false
        let session = AVAudioSession.sharedInstance()
        guard session.category == .playback else { return }
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }
    #else
    private func activateSession() {}
    private func releaseSession() {}
    #endif
}
