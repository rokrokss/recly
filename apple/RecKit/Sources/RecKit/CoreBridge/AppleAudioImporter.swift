import Foundation
import ReclyCore
#if !os(watchOS)
import AVFoundation

/// docs/03 "Naming rules", `source: import`: a file the user picked — audio, or a video's sound — turned
/// into ADR-006 parts on this device. `AVAssetReader` decodes whatever AVFoundation can open to 16 kHz
/// mono PCM and `AVAssetWriter` encodes it back as AAC-LC at 32 kbps, a new file every [segmentSec]
/// seconds, cut on the exact sample. The core names, hashes and registers the parts once every one
/// is written (`ReclyCore.importAudio`).
///
/// Shared by the phone and the Mac. A file that is not there or cannot be read is `Unreadable`; one
/// that opens with no audio this platform decodes — a document, an image, a protected track — is
/// `Unsupported`.
public final class AppleAudioImporter: NSObject, AudioImporter {
    public override init() {
        super.init()
    }

    public func __transcode(sourcePath: String, outDir: String, segmentSec: Int32) async throws -> any TranscodeResult {
        let source = URL(fileURLWithPath: sourcePath)
        guard FileManager.default.isReadableFile(atPath: source.path) else { return TranscodeResultUnreadable.shared }
        let asset = AVURLAsset(url: source)
        guard let track = try? await asset.loadTracks(withMediaType: .audio).first,
              (try? await asset.load(.hasProtectedContent)) != true
        else { return TranscodeResultUnsupported.shared }
        let reader: AVAssetReader
        do { reader = try AVAssetReader(asset: asset) } catch { return TranscodeResultUnsupported.shared }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: Self.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { return TranscodeResultUnsupported.shared }
        reader.add(output)
        guard reader.startReading() else { return TranscodeResultUnsupported.shared }

        let directory = URL(fileURLWithPath: outDir, isDirectory: true)
        let framesPerPart = Int(segmentSec) * Int(Self.sampleRate)
        var parts: [ImportedPart] = []
        var part: PartWriter?
        do {
            while let sample = output.copyNextSampleBuffer() {
                try Task.checkCancellation()
                var remaining: CMSampleBuffer? = sample
                while let buffer = remaining {
                    let count = CMSampleBufferGetNumSamples(buffer)
                    guard count > 0 else { break }
                    if part == nil {
                        part = try PartWriter(url: directory.appendingPathComponent(String(format: "part-%03d.m4a", parts.count + 1)), start: CMSampleBufferGetPresentationTimeStamp(buffer))
                    }
                    let room = framesPerPart - part!.frames
                    if count <= room {
                        try await part!.append(buffer)
                        remaining = nil
                    } else {
                        // The part ends inside this buffer: its head closes the part, its tail opens the next.
                        try await part!.append(try Self.range(buffer, 0, room))
                        remaining = try Self.range(buffer, room, count - room)
                    }
                    if part!.frames >= framesPerPart {
                        parts.append(try await part!.finish())
                        part = nil
                    }
                }
            }
            if reader.status == .failed { throw reader.error ?? CocoaError(.fileReadCorruptFile) }
            if let open = part, open.frames > 0 { parts.append(try await open.finish()) }
            part = nil
        } catch {
            reader.cancelReading()
            part?.cancel()
            if error is CancellationError { throw error }
            return TranscodeResultUnreadable.shared
        }
        guard !parts.isEmpty else { return TranscodeResultUnsupported.shared }
        return TranscodeResultDone(parts: parts)
    }

    /// docs/03: the rate every part is in.
    static let sampleRate: Double = 16_000

    /// [count] samples of an interleaved PCM buffer from [start] — one sample per frame for 16-bit mono.
    private static func range(_ buffer: CMSampleBuffer, _ start: Int, _ count: Int) throws -> CMSampleBuffer {
        var out: CMSampleBuffer?
        let status = CMSampleBufferCopySampleBufferForRange(
            allocator: kCFAllocatorDefault, sampleBuffer: buffer,
            sampleRange: CFRange(location: start, length: count), sampleBufferOut: &out
        )
        guard status == noErr, let out else { throw CocoaError(.fileReadCorruptFile) }
        return out
    }

    /// One part being encoded: its writer, and how many 16 kHz frames it holds.
    private final class PartWriter {
        let url: URL
        private let writer: AVAssetWriter
        private let input: AVAssetWriterInput
        private(set) var frames = 0

        init(url: URL, start: CMTime) throws {
            self.url = url
            try? FileManager.default.removeItem(at: url)
            writer = try AVAssetWriter(outputURL: url, fileType: .m4a)
            input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: AppleAudioImporter.sampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: SegmentedRecorder.bitrateKbps * 1000,
            ])
            input.expectsMediaDataInRealTime = false
            guard writer.canAdd(input) else { throw CocoaError(.fileWriteUnknown) }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
            // The part's own clock starts where its first sample is, so every part plays from zero.
            writer.startSession(atSourceTime: start)
        }

        func append(_ buffer: CMSampleBuffer) async throws {
            while !input.isReadyForMoreMediaData {
                try await Task.sleep(nanoseconds: 2_000_000)
            }
            guard input.append(buffer) else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
            frames += CMSampleBufferGetNumSamples(buffer)
        }

        func finish() async throws -> ImportedPart {
            input.markAsFinished()
            await writer.finishWriting()
            guard writer.status == .completed else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
            return ImportedPart(file: url.lastPathComponent, durationSec: Double(frames) / AppleAudioImporter.sampleRate)
        }

        func cancel() {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: url)
        }
    }
}
#endif
