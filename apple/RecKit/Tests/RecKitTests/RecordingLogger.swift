import Foundation
import ReclyCore

/// `OSLogLogger` writes to the unified log, which a test cannot read back without `log show` and a
/// guess at how long the write takes. The events are part of the contract here — the relocation
/// says `db.relocate.skipped` when it declines to move a database — so the tests take a logger that
/// keeps them.
final class RecordingLogger: ReclyCore.Logger {
    private(set) var events: [String] = []

    func log(level: LoggerLevel, event: String, fields: [String: Any], error: KotlinThrowable?) {
        events.append(event)
    }
}
