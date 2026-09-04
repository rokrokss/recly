import Foundation
import ReclyCore
#if !os(watchOS)
import AVFoundation
#endif

/// docs/08 "오디오 준비" on Apple: the parts of one track joined into a single `m4a` for the STT
/// provider. The AAC frames are **copied**, never decoded — the segment boundaries are
/// frame-aligned (docs/03), which is what makes the timestamps in `transcript.json` mean anything.
///
/// docs/08 names `AVMutableComposition` + `AVAssetExportSession(presetPassthrough)` for this, and
/// that does not work: a composition track holding two parts has two format descriptions (one per
/// segment, even when the two are byte-identical), and `AVAssetExportPresetPassthrough` is not
/// among the presets AVFoundation offers for such an asset — the export fails with
/// `AVErrorOperationNotSupportedForAsset` (measured, macOS 26). The only preset it does offer for
/// audio is `AppleM4A`, which re-encodes.
///
/// So the frames are moved by hand instead, exactly as Android moves them with
/// `MediaExtractor`/`MediaMuxer`: an `AVAssetReader` per part with no output settings hands over
/// the encoded samples, and an `AVAssetWriterInput` with no output settings writes them straight
/// out. Each part's clock starts at zero, so every sample is shifted by the parts before it.
///
/// Shared by the Mac and the phone. The watch runs no jobs (ADR-002) and has no `AVAssetWriter`,
/// so on watchOS this is the honest refusal.
public final class AppleAudioTools: NSObject, ReclyCore.AudioTools {
    public override init() {
        super.init()
    }

    public enum Failure: Error, LocalizedError {
        case unsupportedPlatform
        case noAudioTrack(String)
        case remux(String)

        public var errorDescription: String? {
            switch self {
            case .unsupportedPlatform:
                return "the watch does not run jobs (ADR-002); the phone transcribes"
            case .noAudioTrack(let name):
                return "'\(name)' has no audio track"
            case .remux(let reason):
                return "the remux failed: \(reason)"
            }
        }
    }

    #if os(watchOS)
    /// The `__` name is the raw Kotlin member: SKIE hides it behind the `async` wrapper callers
    /// use, but a Swift *implementation* of the interface fills in the original.
    public func __concat(parts: [OkioPath], out: OkioPath) async throws {
        throw Failure.unsupportedPlatform
    }
    #else
    public func __concat(parts: [OkioPath], out: OkioPath) async throws {
        // `OkioPath.url` marks its result a directory; a part is a file.
        let urls = parts.map { URL(fileURLWithPath: $0.description(), isDirectory: false) }
        // The tracks are loaded up front because the writer needs the first one's format before it
        // will take an input with no output settings of its own to describe.
        var tracks: [(URL, AVURLAsset, AVAssetTrack)] = []
        for url in urls {
            let asset = AVURLAsset(url: url)
            guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
                throw Failure.noAudioTrack(url.lastPathComponent)
            }
            tracks.append((url, asset, track))
        }
        guard let first = tracks.first else { throw Failure.remux("nothing to join") }
        let formats = try await first.2.load(.formatDescriptions)
        guard let hint = formats.first else { throw Failure.noAudioTrack(first.0.lastPathComponent) }

        let outURL = URL(fileURLWithPath: out.description(), isDirectory: false)
        // The core deletes it first too, but a writer refuses to open over an existing file.
        try? FileManager.default.removeItem(at: outURL)

        let writer = try AVAssetWriter(outputURL: outURL, fileType: .m4a)
        // No output settings: the samples are written in the encoding they arrived in, and the
        // hint is what tells the container what that encoding is.
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: hint)
        input.expectsMediaDataInRealTime = false
        guard writer.canAdd(input) else { throw Failure.remux("the writer will not take an audio track") }
        writer.add(input)
        guard writer.startWriting() else {
            throw Failure.remux(writer.error?.localizedDescription ?? "the writer would not start")
        }

        var cursor = CMTime.zero
        var started = false
        do {
            for (url, asset, track) in tracks {
                let reader = try AVAssetReader(asset: asset)
                let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
                guard reader.canAdd(output) else { throw Failure.remux("cannot read '\(url.lastPathComponent)'") }
                reader.add(output)
                guard reader.startReading() else {
                    throw Failure.remux(reader.error?.localizedDescription ?? "the reader would not start")
                }

                // What the part actually presents. The reader hands over whole AAC frames, so the
                // last one or two of them are the encoder's padding — audio the part's own edit
                // list trims and that nobody recorded. Keeping them would push every later part a
                // fifth of a second down the joined time axis (measured), and `transcript.json`
                // maps its timestamps back through `parts[].startOffsetSec`.
                let presented = try await asset.load(.duration)
                // Each part's clock starts at its own zero, so the first sample kept is laid down
                // at [cursor] and the rest follow it.
                var offset: CMTime?
                while let sample = output.copyNextSampleBuffer() {
                    // A reader also emits marker buffers — end of a segment, a format change —
                    // which carry no media and are not the writer's to keep.
                    guard CMSampleBufferGetDataBuffer(sample) != nil,
                          CMSampleBufferGetNumSamples(sample) > 0
                    else { continue }
                    let pts = CMSampleBufferGetPresentationTimeStamp(sample)
                    let last = CMTimeCompare(CMTimeAdd(pts, CMSampleBufferGetDuration(sample)), presented) > 0
                    guard let kept = try Self.clip(sample, to: presented) else {
                        reader.cancelReading()
                        break
                    }
                    let shift = offset ?? CMTimeSubtract(cursor, pts)
                    offset = shift
                    let shifted = try Self.rewrap(kept, as: hint, offset: shift)
                    if !started {
                        writer.startSession(atSourceTime: cursor)
                        started = true
                    }
                    while !input.isReadyForMoreMediaData {
                        try await Task.sleep(nanoseconds: readyPollNanos)
                    }
                    guard input.append(shifted) else {
                        throw Failure.remux(writer.error?.localizedDescription ?? "a sample was refused")
                    }
                    cursor = CMTimeAdd(
                        CMSampleBufferGetPresentationTimeStamp(shifted),
                        CMSampleBufferGetDuration(shifted)
                    )
                    if last {
                        reader.cancelReading()
                        break
                    }
                }
                if reader.status == .failed {
                    throw Failure.remux(reader.error?.localizedDescription ?? "reading '\(url.lastPathComponent)'")
                }
            }
        } catch {
            // A half-written container is worse than none: the step would transcribe part of the
            // recording and report a full one.
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: outURL)
            throw error
        }

        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: outURL)
            throw Failure.remux(writer.error?.localizedDescription ?? "status \(writer.status.rawValue)")
        }
    }

    /// As much of [sample] as falls inside [presented], or nil when none of it does.
    ///
    /// Whole frames only — frames are what is being copied — rounded to the nearest one, so the
    /// part contributes its own length to within half a frame either way.
    private static func clip(_ sample: CMSampleBuffer, to presented: CMTime) throws -> CMSampleBuffer? {
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        let duration = CMSampleBufferGetDuration(sample)
        if CMTimeCompare(CMTimeAdd(pts, duration), presented) <= 0 { return sample }
        let count = CMSampleBufferGetNumSamples(sample)
        let inside = CMTimeSubtract(presented, pts)
        guard count > 0, CMTimeGetSeconds(duration) > 0, CMTimeCompare(inside, .zero) > 0 else { return nil }
        let keep = Int((CMTimeGetSeconds(inside) / CMTimeGetSeconds(duration) * Double(count)).rounded())
        guard keep > 0 else { return nil }
        guard keep < count else { return sample }
        var clipped: CMSampleBuffer?
        let status = CMSampleBufferCopySampleBufferForRange(
            allocator: kCFAllocatorDefault,
            sampleBuffer: sample,
            sampleRange: CFRange(location: 0, length: keep),
            sampleBufferOut: &clipped
        )
        guard status == noErr, let clipped else {
            throw Failure.remux("could not clip a buffer to \(presented.seconds)s (OSStatus \(status))")
        }
        return clipped
    }

    /// The same encoded samples, [offset] later on the joined time axis and described by the
    /// writer's own format.
    ///
    /// The format has to be replaced, not just carried over: two parts written by the same encoder
    /// with the same settings still arrive with format descriptions AVFoundation does not consider
    /// equal (they differ in the verbatim sample description the container was read from), and a
    /// passthrough input that is handed a second one finishes with `AVErrorUnknown` (measured).
    /// The audio is the same — same ASBD, same magic cookie — so the writer's description is the
    /// right one for every part.
    private static func rewrap(
        _ sample: CMSampleBuffer,
        as format: CMFormatDescription,
        offset: CMTime
    ) throws -> CMSampleBuffer {
        guard let data = CMSampleBufferGetDataBuffer(sample) else {
            throw Failure.remux("a sample arrived with no data")
        }
        var timingCount: CMItemCount = 0
        CMSampleBufferGetSampleTimingInfoArray(sample, entryCount: 0, arrayToFill: nil, entriesNeededOut: &timingCount)
        var timings = [CMSampleTimingInfo](repeating: CMSampleTimingInfo(), count: timingCount)
        CMSampleBufferGetSampleTimingInfoArray(
            sample,
            entryCount: timingCount,
            arrayToFill: &timings,
            entriesNeededOut: &timingCount
        )
        for index in 0 ..< timingCount {
            timings[index].presentationTimeStamp = CMTimeAdd(timings[index].presentationTimeStamp, offset)
            if timings[index].decodeTimeStamp.isValid {
                timings[index].decodeTimeStamp = CMTimeAdd(timings[index].decodeTimeStamp, offset)
            }
        }
        var sizeCount: CMItemCount = 0
        CMSampleBufferGetSampleSizeArray(sample, entryCount: 0, arrayToFill: nil, entriesNeededOut: &sizeCount)
        var sizes = [Int](repeating: 0, count: sizeCount)
        CMSampleBufferGetSampleSizeArray(sample, entryCount: sizeCount, arrayToFill: &sizes, entriesNeededOut: &sizeCount)

        var rewrapped: CMSampleBuffer?
        let status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: data,
            formatDescription: format,
            sampleCount: CMSampleBufferGetNumSamples(sample),
            sampleTimingEntryCount: timingCount,
            sampleTimingArray: &timings,
            sampleSizeEntryCount: sizeCount,
            sampleSizeArray: &sizes,
            sampleBufferOut: &rewrapped
        )
        guard status == noErr, let rewrapped else {
            throw Failure.remux("could not move a sample to \(offset.seconds)s (OSStatus \(status))")
        }
        return rewrapped
    }

    /// The writer's input is a queue; when it is full this is how long to wait before asking again.
    private let readyPollNanos: UInt64 = 2_000_000
    #endif
}
