import Foundation

/// docs/01 "Long recordings — watchdog · reminders · battery stop · waiting to resume": when a recording has run for
/// ten minutes with nobody speaking — the one that was forgotten, or walked away from — read off the
/// tenth-of-a-second peaks the live strip draws ([LiveWaveform]). The Android `SilenceReminder` is
/// the same rule with the same constants; the two are meant to stay readable side by side.
///
/// Speech is told from the room by its level against the recording's own floor, so no microphone's
/// gain is assumed:
/// - The floor is the quietest window of the last minute: the room between words, or the steady hum
///   of a fan once it has run for a minute.
/// - A window sounds when its peak is [aboveFloor] (10 dB) over that floor and at least [minSound]
///   (about −55 dBFS) — a floor near zero, behind a noise suppressor, must not make a pin drop a voice.
/// - Speech is [speechWindows] sounding windows (2 s) within the last [recentWindows] (10 s): a cough,
///   a door or a notification chime is shorter and leaves the clock running; a sentence is longer and
///   sets it back to zero.
/// - A window of digital zero is a microphone that was taken away. Nobody could be heard, so it is
///   neither silence nor speech: the clock waits. (An interruption on Apple stops the input instead,
///   and then no windows arrive at all.)
///
/// It runs on the audio thread, under the recorder's lock, so it allocates nothing after `init`.
struct SilenceReminder {
    /// Ten minutes of tenth-of-a-second windows; the notification's text says "10 minutes".
    static let remindAfterWindows = 6_000
    static let windowsPerSecond = 10
    static let floorSeconds = 60
    /// 10 dB as an amplitude ratio (10^(10/20)).
    static let aboveFloor: Float = 3.1623
    /// About −55 dBFS as a peak amplitude (10^(−55/20)).
    static let minSound: Float = 0.0017783
    static let recentWindows = 100
    static let speechWindows = 20

    /// The quietest window of each of the last [floorSeconds] seconds, as a ring.
    private var quietest = [Float](repeating: .greatestFiniteMagnitude, count: SilenceReminder.floorSeconds)
    private var quietestNext = 0
    private var quietestOfMinute = Float.greatestFiniteMagnitude
    private var secondQuietest = Float.greatestFiniteMagnitude
    private var secondWindows = 0

    /// Whether each of the last [recentWindows] windows sounded, as a ring, and how many did.
    private var recent = [Bool](repeating: false, count: SilenceReminder.recentWindows)
    private var recentNext = 0
    private var recentSounding = 0

    /// Windows since speech was last heard, or since the start.
    private var quietWindows = 0

    /// One window's peak, 0–1. True at the window that completes [remindAfterWindows] without
    /// speech — once: the next reminder needs speech first, then ten more quiet minutes.
    mutating func add(_ peak: Float) -> Bool {
        guard peak > 0 else { return false }
        secondQuietest = min(secondQuietest, peak)
        let floor = min(quietestOfMinute, secondQuietest)
        let sounding = peak >= max(Self.minSound, floor * Self.aboveFloor)

        if recent[recentNext] { recentSounding -= 1 }
        recent[recentNext] = sounding
        if sounding { recentSounding += 1 }
        recentNext = (recentNext + 1) % Self.recentWindows

        secondWindows += 1
        if secondWindows == Self.windowsPerSecond {
            quietest[quietestNext] = secondQuietest
            quietestNext = (quietestNext + 1) % Self.floorSeconds
            quietestOfMinute = quietest.min() ?? .greatestFiniteMagnitude
            secondQuietest = .greatestFiniteMagnitude
            secondWindows = 0
        }

        if recentSounding >= Self.speechWindows {
            quietWindows = 0
            return false
        }
        quietWindows += 1
        return quietWindows == Self.remindAfterWindows
    }
}
