#if os(macOS)
import Foundation

/// When to offer a recording and when to offer to end one (ADR-011: detect → confirm → record, never a
/// recording nobody asked for and never a stop nobody asked for).
///
/// Pure, and separated from the two monitors for exactly that reason: "mic in use × meeting app ×
/// cooldown" is the part with the rules in it, and the part a test can hold still.
public struct MeetingDetectionRule {
    public enum Prompt: Equatable, Sendable {
        /// "회의 중인가요? 녹음 시작"
        case start

        /// "녹음을 끝낼까요?" — an offer (docs/12 "종료 감지": never an automatic stop).
        case stop
    }

    public struct Signals: Equatable, Sendable {
        /// The default input device is open for some process other than Recly.
        public let micInUse: Bool
        /// The meeting app this would be attributed to, or `nil`.
        public let meetingApp: String?
        /// A *meeting* recording is in flight. A microphone-only memo is not one: its own idle
        /// microphone would otherwise be read as a meeting that has ended.
        public let isRecording: Bool

        public init(micInUse: Bool, meetingApp: String?, isRecording: Bool) {
            self.micInUse = micInUse
            self.meetingApp = meetingApp
            self.isRecording = isRecording
        }
    }

    /// A prompt the user ignored must not come back every two seconds. Ten minutes is long enough
    /// that a declined offer stays declined for the length of a stand-up.
    public static let cooldownSec: TimeInterval = 600

    /// docs/12 "종료 감지": the microphone unused for 60 seconds straight.
    public static let micIdleSec: TimeInterval = 60

    /// False from the moment a prompt is made until the meeting signal goes away again — so one
    /// meeting gets one invitation (docs/20 M4: "Zoom 입장 시 알림 1회"), and so a recording the
    /// user stopped by hand is not immediately offered back to them.
    ///
    /// The microphone going idle mid-meeting re-arms it, which can cost a second prompt once the
    /// cooldown is up; re-arming only when the meeting app quits would be quieter but would miss
    /// back-to-back calls inside a Zoom process that never exits, and a missed meeting is the worse
    /// failure of the two.
    private var armed = true
    private var lastPromptAt: Date?
    private var micIdleSince: Date?
    private var stopPrompted = false

    public init() {}

    public mutating func evaluate(_ signals: Signals, now: Date) -> Prompt? {
        guard signals.isRecording else {
            micIdleSince = nil
            stopPrompted = false
            guard signals.micInUse, signals.meetingApp != nil else {
                armed = true
                return nil
            }
            guard armed else { return nil }
            if let last = lastPromptAt, now.timeIntervalSince(last) < Self.cooldownSec { return nil }
            armed = false
            lastPromptAt = now
            return .start
        }

        // Recording. There is nothing to invite the user to start, and the meeting they stop by
        // hand must not be offered again while they are still in it.
        armed = false
        guard !signals.micInUse else {
            // The microphone coming back is the meeting app taking the device again — a call that
            // is still going, or the next one. Either way the idle clock starts over, and so does
            // the one offer that clock is allowed to make.
            micIdleSince = nil
            stopPrompted = false
            return nil
        }
        let since = micIdleSince ?? now
        micIdleSince = since
        guard !stopPrompted, now.timeIntervalSince(since) >= Self.micIdleSec else { return nil }
        stopPrompted = true
        return .stop
    }
}

/// The two monitors, the rule and a clock, wired together (docs/12 "미팅 감지"). The shell gets one
/// callback out of it and owns everything visible — the notification, the recording.
public final class MeetingDetector {
    /// Both signals are polled here rather than only listened to: the meeting app's window title is
    /// not a notification anyone posts, and the microphone going idle *while Recly holds it* is not
    /// one Core Audio can post either (`MicInUseMonitor`).
    static let tickIntervalSec: Double = 2

    private let mic = MicInUseMonitor()
    private let queue = DispatchQueue(label: "app.recly.mac.detect")
    private let onPrompt: (MeetingDetectionRule.Prompt) -> Void

    private var timer: DispatchSourceTimer?
    private var rule = MeetingDetectionRule()
    private var recording = false

    /// [onPrompt] is called on the main queue — everything the shell does with it is UI.
    public init(onPrompt: @escaping (MeetingDetectionRule.Prompt) -> Void) {
        self.onPrompt = onPrompt
    }

    public func start() {
        mic.start { [weak self] _ in
            // The microphone opening is the moment worth reacting to; waiting up to two seconds for
            // the next tick would put the notification behind the user's own "join" click.
            self?.queue.async { self?.tick() }
        }
        queue.async {
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + Self.tickIntervalSec, repeating: Self.tickIntervalSec)
            timer.setEventHandler { [weak self] in self?.tick() }
            timer.resume()
            self.timer = timer
        }
    }

    public func stop() {
        mic.stop()
        queue.sync {
            timer?.cancel()
            timer = nil
        }
    }

    /// The shell says whether a *meeting* recording is in flight — see `Signals.isRecording`.
    public func recordingChanged(_ isRecording: Bool) {
        queue.async {
            self.recording = isRecording
            self.tick()
        }
    }

    private func tick() {
        let micInUse = mic.inUse
        // The app list and the window titles cost a round trip to the window server, and they only
        // decide anything while a microphone is open and nothing is being recorded yet.
        let app = micInUse && !recording ? MeetingAppMonitor.running() : nil
        let signals = MeetingDetectionRule.Signals(
            micInUse: micInUse, meetingApp: app, isRecording: recording
        )
        guard let prompt = rule.evaluate(signals, now: Date()) else { return }
        let onPrompt = onPrompt
        DispatchQueue.main.async { onPrompt(prompt) }
    }
}
#endif
