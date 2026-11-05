import Foundation

/// docs/03 "Free storage on the device": the free bytes on the volume that holds a recording, as each Apple
/// platform counts them, for the core's `StorageFloor` to judge.
///
/// The iPhone and the Mac read `volumeAvailableCapacityForImportantUsage` — what the system would make room for
/// when the user asks, purgeable caches included. watchOS does not have that key (`API_UNAVAILABLE(watchos)` in
/// the SDK), so the watch reads `volumeAvailableCapacity`, the space free right now: the stricter figure, which
/// can refuse a start the system could have made room for.
enum FreeStorage {
    /// The start's and the boundary's figure. −1 when the platform cannot say, which refuses and stops nothing.
    static func bytes(at url: URL) -> Int64 {
        #if os(watchOS)
        return freeNow(at: url)
        #else
        // A fresh URL each time: a `URL` caches the resource values it has read, and a figure that does not move
        // is the one thing this must not be.
        let volume = URL(fileURLWithPath: url.path)
        guard let free = try? volume.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage
        else { return -1 }
        return free
        #endif
    }

    /// `volumeAvailableCapacity`, the space free right now, purgeable space not counted: the figure for a write
    /// that has just failed. [bytes] on the iPhone and the Mac can still count gigabytes the system has not purged
    /// yet while the disk refuses the write. −1 when the platform cannot say.
    static func freeNow(at url: URL) -> Int64 {
        // A fresh URL each time, for the reason [bytes] gives.
        let volume = URL(fileURLWithPath: url.path)
        guard let free = try? volume.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity
        else { return -1 }
        return Int64(free)
    }

    /// The domain of `AVAudioFile`'s own errors: the Core Audio `OSStatus` as the code, no underlying error. No SDK
    /// header names it (AVAudioFile.h says only "a description of the error"), so it is spelled as the framework
    /// reports it.
    static let avfAudioErrorDomain = "com.apple.coreaudio.avfaudio"

    /// A write the platform refused for want of space: `ENOSPC`, Foundation's out-of-space, or Core Audio's
    /// `dskFulErr` — anywhere in the chain of underlying errors.
    ///
    /// Not the code `AVAudioFile` gives a full disk on macOS 27: its write fails there with
    /// `kAudioFilePositionError` (−40), and its open with that or `kAudioFileUnspecifiedError`, neither of which
    /// says anything about space. Those are told by the space free right now ([freeNow]), as any failed write is.
    static func isOutOfSpace(_ error: Error?) -> Bool {
        var next = error.map { $0 as NSError }
        while let error = next {
            switch (error.domain, error.code) {
            case (NSPOSIXErrorDomain, Int(ENOSPC)),
                 (NSCocoaErrorDomain, NSFileWriteOutOfSpaceError),
                 (NSOSStatusErrorDomain, -34),
                 (avfAudioErrorDomain, -34),
                 (avfAudioErrorDomain, Int(ENOSPC)):
                return true
            default:
                next = error.userInfo[NSUnderlyingErrorKey] as? NSError
            }
        }
        return false
    }
}
