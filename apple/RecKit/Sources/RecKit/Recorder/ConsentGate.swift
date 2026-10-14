/// docs/12 "Consent reminder · jurisdiction notices" · ADR-011: the reminder gates the recording — without "I told
/// them" it does not start. The Mac asks before every meeting recording while the reminder is on; this is that
/// decision on its own, outside the panel that asks it, so a test can hold it.
public enum ConsentGate {
    /// The reminder's answer: [confirmed] is `I told them · Start recording`, and [suppress] is `Do not ask again`,
    /// which applies whichever button was pressed.
    public struct Answer: Equatable, Sendable {
        public let confirmed: Bool
        public let suppress: Bool

        public init(confirmed: Bool, suppress: Bool) {
            self.confirmed = confirmed
            self.suppress = suppress
        }
    }

    /// Whether the recording may start. [ask] runs only while the reminder is [due]; a panel ended without an answer
    /// is Cancel. A suppressed reminder is turned off through [turnOff], whichever way the question was answered.
    public static func admits(due: Bool, ask: () -> Answer?, turnOff: () -> Void) -> Bool {
        guard due else { return true }
        guard let answer = ask() else { return false }
        if answer.suppress { turnOff() }
        return answer.confirmed
    }
}
