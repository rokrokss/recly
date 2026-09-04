import Foundation
import ReclyCore

// The Obj-C export hands `ByteArray` over as an opaque Kotlin object with element accessors and no
// bulk constructor. Only `SecureStore` values cross this boundary in M4, and those are tokens and
// signing keys — tens of bytes — so element-at-a-time is fine.
extension Data {
    var kotlinByteArray: KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}

extension KotlinByteArray {
    var data: Data {
        var bytes = Data(count: Int(size))
        for index in 0 ..< Int(size) {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return bytes
    }
}

// okio is how the core names files (`CoreDeps.dataDir`, `RecordingRecord.dir`, `PartHasher`) and
// `URL` is how everything above it does. The two conversions live here so no caller has to know
// that `description()`, and not Swift's `description`, is the exported one.
extension OkioPath {
    var url: URL { URL(fileURLWithPath: description(), isDirectory: true) }
}

extension URL {
    var okioPath: OkioPath { OkioPath.companion.toPath(path, normalize: true) }
}

/// `kotlin.time.Clock` — not the core's own `Clock`, and the only thing `Ulid.generate` accepts.
/// A recording's id carries the moment the recording started, not the moment the id was minted,
/// so what is handed over is one fixed instant.
final class FixedKotlinClock: KotlinClock {
    private let instant: KotlinInstant

    init(_ instant: KotlinInstant) {
        self.instant = instant
    }

    func now() -> KotlinInstant { instant }
}

/// `2026-08-26T01:00:00.000Z` — the shape docs/01 fixes for every timestamp the core stores, and
/// the one `MetaWriter.baseName` parses back. The core's own formatter is `internal`, so each
/// shell carries its own copy of the format (the Android one is `recording/Time.kt`).
extension KotlinInstant {
    /// The same moment as `Date`, for the scheduling arithmetic (`nextRunAt`) and the menu's dates.
    var date: Date { Date(timeIntervalSince1970: Double(toEpochMilliseconds()) / 1000) }

    var isoUtc: String { date.isoUtc }
}

extension Date {
    /// The same shape, for the timestamps that never were a `KotlinInstant` — the recording's
    /// `startedAt` and the workflow document's `updatedAt`.
    var isoUtc: String { Self.isoUtcFormatter.string(from: self) }

    private static let isoUtcFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return formatter
    }()
}
