import Foundation
import ReclyCore

/// docs/09 화면 원칙 2: a recording's waveform, worked out once it is finalized on this device (or,
/// on the phone, received whole from the watch) and kept beside its parts (`WaveformPeaks`), so even
/// the first time the recording is opened the detail draws it at once instead of decoding every part.
///
/// One recording at a time, at background priority, and only between captures: a capture starting
/// stops the decode in hand, which is picked up again when the capture has ended. It only reads the
/// parts, so the upload and the transcription never wait for it. A recording from another device gets
/// its waveform the first time its audio is fetched and opened.
@MainActor
public final class WaveformPrecompute {
    private let core: ReclyCore_
    private var queue: [String] = []
    private var worker: Task<Void, Never>?

    /// A capture is starting, running or stopping. The shell keeps it in step with its recorder.
    public var capturing = false {
        didSet {
            guard capturing != oldValue else { return }
            if capturing { worker?.cancel() } else { drain() }
        }
    }

    public init(core: ReclyCore_) {
        self.core = core
    }

    public func enqueue(recordingId: String) {
        guard !queue.contains(recordingId) else { return }
        queue.append(recordingId)
        drain()
    }

    private func drain() {
        guard worker == nil, !capturing, let recordingId = queue.first else { return }
        let core = self.core
        worker = Task(priority: .background) { [weak self] in
            let settled = await Self.compute(core: core, recordingId: recordingId)
            guard let self else { return }
            self.worker = nil
            // Stopped for a capture: it stays first in the queue for when the capture has ended.
            if settled { self.queue.removeAll { $0 == recordingId } }
            self.drain()
        }
    }

    /// True once there is nothing left to do for this recording — saved, already saved, or not a
    /// recording that can be decoded here; false when it was stopped part-way.
    nonisolated static func compute(core: ReclyCore_, recordingId: String) async -> Bool {
        guard let record = try? await core.recordings.get(id: recordingId),
              record.meta.status == RecordingStatus.finalized
        else { return true }
        let track = RecordingPlaylist.playedTrack(tracks: record.meta.tracks)
        let played = record.meta.parts.filter { $0.track == track }
        let selection = RecordingPlaylist.select(
            tracks: record.meta.tracks,
            parts: record.meta.parts,
            dir: record.dir.url,
            exists: { FileManager.default.fileExists(atPath: $0.path) }
        )
        // Every part of the played track, or nothing: the detail would not use peaks of a prefix.
        guard !selection.isEmpty, selection.urls.count == played.count else { return true }
        let saved = try? await core.recordings.waveform(recordingId: recordingId)
        if RecordingWaveform.cached(saved?.map(\.floatValue), for: selection) != nil { return true }
        do {
            let peaks = try await RecordingWaveform.peaks(for: selection)
            guard !Task.isCancelled else { return false }
            try await core.recordings.saveWaveform(recordingId: recordingId, peaks: peaks.map { KotlinFloat(float: $0) })
            return true
        } catch {
            return !Task.isCancelled
        }
    }
}
