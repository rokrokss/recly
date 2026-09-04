import AVFoundation
import Foundation

/// docs/09 화면 원칙 2: the shape of the recording under the player bar's clock — what the detail
/// draws a playhead across, and what a drag on it seeks through.
///
/// Two halves, for the same reason `RecordingPlaylist` is split from the player: [bins] is the
/// arithmetic the drawing is, and can be checked without a file or a screen, while [peaks] is the
/// decode that has to open every part. What the view holds between them is one `[Float]` of peaks
/// on the recording's own timeline, 0…1, one per [windowSec] window.
enum RecordingWaveform {
    /// The default window: 0.25 s. Finer would be more windows
    /// than a bar can be drawn for on any screen this runs on.
    static let windowSec: Double = 0.25

    /// The peaks resampled to exactly the number of bars there is room for, and normalised so the
    /// loudest one fills the row. A recording that was quiet throughout is still drawn as a shape
    /// rather than as a flat line — the bars say where the sound is, not how many decibels it was.
    ///
    /// Nothing to draw (no peaks, or no room) is `[]`, which the view answers with its baseline.
    /// More bars than windows repeats a window across the bars that fall inside it, rather than
    /// leaving a gap or reading off the end.
    static func bins(peaks: [Float], count: Int) -> [Float] {
        guard count > 0, !peaks.isEmpty else { return [] }
        // Each bar is the loudest window under it: a peak that survives downsampling is what makes
        // a waveform readable, where an average would flatten every transient into the same grey.
        var bins = (0..<count).map { index -> Float in
            let start = index * peaks.count / count
            let end = min(peaks.count, max(start + 1, (index + 1) * peaks.count / count))
            return peaks[start..<end].max() ?? 0
        }
        let loudest = bins.max() ?? 0
        guard loudest > 0 else { return bins }
        for index in bins.indices { bins[index] /= loudest }
        return bins
    }

    /// One part could not be decoded, so there is no timeline to draw: half a waveform under a
    /// whole clock would put the shape of the recording at the wrong seconds.
    enum Failure: Error {
        case undecodable(URL)
    }

    /// The parts of one selection decoded end to end, as the loudest sample in each [windowSec]
    /// window. Not on the main actor — this reads every byte of the recording — and cancelled
    /// between parts, because the detail's model starts a new one whenever its audio changes.
    ///
    /// The windows are counted against the durations `meta.json` recorded and not against what
    /// AVFoundation decoded, because those are the seconds the clock and the transcript below are
    /// on: each part contributes exactly `durationSec / windowSec` windows, rounded up, padded with
    /// silence or truncated. So the peaks are `selection.totalSec` long however the files decode.
    static func peaks(
        for selection: RecordingPlaylist.Selection,
        windowSec: Double = windowSec
    ) async throws -> [Float] {
        var peaks: [Float] = []
        for (url, durationSec) in zip(selection.urls, selection.durations) {
            try Task.checkCancellation()
            let windows = Int((durationSec / windowSec).rounded(.up))
            var part = try await self.peaks(of: url, windowSec: windowSec)
            part = Array(part.prefix(windows))
            part.append(contentsOf: repeatElement(0, count: max(0, windows - part.count)))
            peaks.append(contentsOf: part)
        }
        return peaks
    }

    #if os(watchOS)
    /// The watch has no `AVAssetReader` — and no detail page to draw a waveform on either (ADR-002:
    /// it records and hands over, and the phone is where a recording is read). The shared model
    /// compiles here; there is simply nothing for it to decode.
    private static func peaks(of url: URL, windowSec: Double) async throws -> [Float] {
        throw Failure.undecodable(url)
    }
    #else
    /// One part, as linear PCM the reader hands over in whatever chunks it likes: the samples are
    /// counted into windows across the buffer boundaries, so a window is [windowSec] of the part
    /// and not of a buffer.
    private static func peaks(of url: URL, windowSec: Double) async throws -> [Float] {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .audio).first,
              let reader = try? AVAssetReader(asset: asset)
        else { throw Failure.undecodable(url) }
        // 16-bit mono at the file's own sample rate: the peaks are one number per window, so
        // resampling in the reader would be work spent on samples that are about to be maxed away.
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
                AVNumberOfChannelsKey: 1,
            ]
        )
        reader.add(output)
        guard reader.startReading() else { throw Failure.undecodable(url) }

        var peaks: [Float] = []
        var perWindow = 0
        var counted = 0
        var loudest: Float = 0
        while let buffer = output.copyNextSampleBuffer() {
            // Every buffer and not only every part: a part is fifteen minutes (docs/03), which is
            // fifteen minutes of decoding for a page that has already been replaced. The reader is
            // told to stop as well, because it has its own thread ahead of this loop.
            guard !Task.isCancelled else {
                reader.cancelReading()
                throw CancellationError()
            }
            if perWindow == 0 {
                guard let format = CMSampleBufferGetFormatDescription(buffer),
                      let stream = CMAudioFormatDescriptionGetStreamBasicDescription(format)
                else { continue }
                perWindow = max(1, Int((stream.pointee.mSampleRate * windowSec).rounded()))
            }
            guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
            let count = CMBlockBufferGetDataLength(block) / MemoryLayout<Int16>.size
            var samples = [Int16](repeating: 0, count: count)
            guard CMBlockBufferCopyDataBytes(
                block,
                atOffset: 0,
                dataLength: count * MemoryLayout<Int16>.size,
                destination: &samples
            ) == kCMBlockBufferNoErr else { throw Failure.undecodable(url) }
            for sample in samples {
                // `magnitude` and not `abs`: `Int16.min` has no positive of its own.
                loudest = max(loudest, Float(Int32(sample).magnitude) / 32768)
                counted += 1
                if counted == perWindow {
                    peaks.append(loudest)
                    loudest = 0
                    counted = 0
                }
            }
        }
        guard reader.status == .completed else { throw Failure.undecodable(url) }
        // The tail of the part is a window like any other, however short it came out.
        if counted > 0 { peaks.append(loudest) }
        return peaks
    }
    #endif
}
