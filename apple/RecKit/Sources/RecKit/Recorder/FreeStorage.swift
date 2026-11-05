import Foundation

/// docs/03 "Free storage on the device": the free bytes on the volume that holds a recording, as each Apple
/// platform counts them, for the core's `StorageFloor` to judge.
///
/// The iPhone and the Mac read `volumeAvailableCapacityForImportantUsage` — what the system would make room for
/// when the user asks, purgeable caches included. watchOS does not have that key (`API_UNAVAILABLE(watchos)` in
/// the SDK), so the watch reads `volumeAvailableCapacity`, the space free right now: the stricter figure, which
/// can refuse a start the system could have made room for.
enum FreeStorage {
    /// −1 when the platform cannot say, which refuses and stops nothing.
    static func bytes(at url: URL) -> Int64 {
        // A fresh URL each time: a `URL` caches the resource values it has read, and a figure that does not move
        // is the one thing this must not be.
        let volume = URL(fileURLWithPath: url.path)
        #if os(watchOS)
        guard let free = try? volume.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity
        else { return -1 }
        return Int64(free)
        #else
        guard let free = try? volume.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage
        else { return -1 }
        return free
        #endif
    }

    /// A write the platform refused for want of space: `ENOSPC`, Foundation's out-of-space, or Core Audio's
    /// `dskFulErr` — anywhere in the chain of underlying errors.
    static func isOutOfSpace(_ error: Error?) -> Bool {
        var next = error.map { $0 as NSError }
        while let error = next {
            switch (error.domain, error.code) {
            case (NSPOSIXErrorDomain, Int(ENOSPC)),
                 (NSCocoaErrorDomain, NSFileWriteOutOfSpaceError),
                 (NSOSStatusErrorDomain, -34):
                return true
            default:
                next = error.userInfo[NSUnderlyingErrorKey] as? NSError
            }
        }
        return false
    }
}
