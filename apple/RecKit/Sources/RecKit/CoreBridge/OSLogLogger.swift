import Foundation
import os
import ReclyCore

/// The core's structured events (docs/20 — `rec.finalize`, `job.step.ok`, …) into the unified log,
/// so `log stream --predicate 'subsystem == "app.recly.mac"'` shows the same names every platform
/// logs — under that platform's own [CoreBridge.appName], `app.recly` on the phone and
/// `app.recly.watch` on the watch.
///
/// Fields are rendered `key=value` and marked public: none of them is user content, and a private
/// interpolation would show up as `<private>` in every capture we take.
public final class OSLogLogger: ReclyCore.Logger {
    private let logger: os.Logger

    public init(subsystem: String = CoreBridge.appName, category: String = "core") {
        logger = os.Logger(subsystem: subsystem, category: category)
    }

    public func log(level: LoggerLevel, event: String, fields: [String: Any], error: KotlinThrowable?) {
        var line = event
        for key in fields.keys.sorted() {
            line += " \(key)=\(fields[key].map { String(describing: $0) } ?? "null")"
        }
        if let error {
            line += " error=\(error.message ?? error.description())"
        }
        if level == LoggerLevel.debug {
            logger.debug("\(line, privacy: .public)")
        } else if level == LoggerLevel.warn {
            logger.warning("\(line, privacy: .public)")
        } else if level == LoggerLevel.error {
            logger.error("\(line, privacy: .public)")
        } else {
            logger.info("\(line, privacy: .public)")
        }
    }
}
