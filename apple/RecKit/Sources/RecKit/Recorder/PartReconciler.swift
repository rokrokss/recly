import AVFoundation
import Foundation
import ReclyCore

/// What one pass over a recording's directory found.
struct Reconciled {
    /// Segment files with readable audio in them, registered or not.
    let files: Int
    /// Segments this pass renamed out of the way because nothing could read them — see
    /// `PartReconciler.quarantine`. Bytes on disk, but no part: a stop with nothing else in the
    /// directory finalizes on the ledger's duration rather than the files'.
    let quarantined: Int
    /// Parts this pass filed for the first time.
    let registered: Int
    /// Parts whose audio is on disk and whose row still is not — the `.pending` markers left.
    let pending: Int
    let durationSec: Double
    let endedAt: Date
}

/// The directory is the truth about what was recorded; the rows are only what the app managed to
/// write down. This reconciles the two — and it is one type because the stop path and the recovery
/// path must agree exactly: whatever a stop refuses to lose, a later recovery has to find. (The
/// Android `PartReconciler` is the same contract; the two are meant to stay readable side by side.)
final class PartReconciler {
    /// Marks a part whose audio is on disk but whose row is not; a recovery pass clears it.
    static let pendingSuffix = ".pending"
    /// Marks a segment nothing can read — see [quarantine].
    static let corruptSuffix = ".corrupt"

    private let core: ReclyCore_
    private let files = FileManager.default

    init(core: ReclyCore_) {
        self.core = core
    }

    /// Files the engine left, in part order: registers the ones no row knows about, deletes the
    /// empty tail, and clears the marker of every part that made it in.
    ///
    /// A registration that fails again leaves (or re-writes) its marker and the walk continues —
    /// the offset still advances past it, because the audio is there whether or not the row is.
    ///
    /// A meeting recording leaves three files per part, and they are walked as one part: they were
    /// handed the same frames, so they share a `startOffsetSec`, and the offset advances by the
    /// longest of them (docs/03 "같은 시간 구간이면 같은 번호"). Taking the longest rather than any
    /// one track's is what keeps the timeline intact when a single track's tail is unreadable —
    /// that file is quarantined, the other two are filed, and everything after them still lines up.
    func reconcile(recordingId: String) async throws -> Reconciled? {
        guard let record = try await core.recordings.get(id: recordingId) else { return nil }
        let directory = record.dir.url
        let known = Set(record.meta.parts.map(\.file))
        let durations = Dictionary(
            record.meta.parts.map { ($0.file, $0.durationSec) },
            uniquingKeysWith: { first, _ in first }
        )
        var offsetSec = 0.0
        var endedAt = Date(timeIntervalSince1970: 0)
        var found = 0
        var quarantined = 0
        var registered = 0
        var failed = 0

        for group in segmentGroups(in: directory) {
            // The tracks of one part are one slice of the timeline however many files it took, so
            // the offset moves once, after all of them.
            var partSec = 0.0
            for segment in group.files {
                let url = segment.url
                let attributes = try? files.attributesOfItem(atPath: url.path)
                let bytes = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
                let modified = attributes?[.modificationDate] as? Date

                if known.contains(url.lastPathComponent) {
                    partSec = max(partSec, durations[url.lastPathComponent] ?? 0)
                    clearMarker(for: url)
                    found += 1
                    endedAt = max(endedAt, modified ?? endedAt)
                    continue
                }

                // The container is the only thing that knows how long a segment is, and it says one
                // of three things (docs/03 "메타데이터").
                let durationSec: Double
                switch bytes > 0 ? Self.segmentAudio(of: url) : SegmentAudio.empty {
                case .empty:
                    // A segment the engine opened and never got a frame into: no bytes at all, or a
                    // header with nothing behind it. Not a part — and its marker goes too, because a
                    // marker pointing at a file this walk deleted would defer the finalize for good.
                    clearMarker(for: url)
                    try? files.removeItem(at: url)
                    continue

                case .unreadable:
                    // Not a file with audio in it, and not nothing either: it contributes no part
                    // and no duration, and the recording finalizes through the last part that *is*
                    // readable. It is counted apart so the stop path knows the directory was not
                    // empty; a recovery pass that finds nothing else drops the recording.
                    quarantine(url, recordingId: recordingId)
                    quarantined += 1
                    endedAt = max(endedAt, modified ?? endedAt)
                    continue

                case .readable(let seconds):
                    durationSec = seconds
                    found += 1
                    endedAt = max(endedAt, modified ?? endedAt)
                }

                do {
                    let sha256 = try await PartHasher.shared.sha256(fs: fileSystem, path: url.okioPath)
                    try await core.recordings.addPart(
                        recordingId: recordingId,
                        // `Part_`, not `Part`: the Obj-C export renames the meta's part to keep it
                        // apart from the SQLDelight row of the same name.
                        part: Part_(
                            part: Int32(group.part),
                            track: segment.track,
                            file: url.lastPathComponent,
                            bytes: bytes,
                            sha256: sha256,
                            startOffsetSec: offsetSec,
                            durationSec: durationSec
                        )
                    )
                    registered += 1
                    clearMarker(for: url)
                } catch {
                    failed += 1
                    // The marker is a hint for the next pass; if the storage that refused the row
                    // also refuses the marker, the count below still carries the failure.
                    markPending(for: url)
                    log(.warn, "rec.part.pending", ["recordingId": recordingId, "file": url.lastPathComponent])
                }
                partSec = max(partSec, durationSec)
            }
            offsetSec += partSec
        }

        return Reconciled(
            files: found,
            quarantined: quarantined,
            registered: registered,
            pending: max(markers(in: directory).count, failed),
            durationSec: offsetSec,
            endedAt: endedAt
        )
    }

    /// The half of a stop that only touches the core, kept out of `SegmentedRecorder` so it can be
    /// tested without a microphone: settle every part that is on disk, then close the meta — or
    /// refuse to, and say so.
    func closeOut(
        recordingId: String,
        ledgerSec: Double,
        title: String?,
        silenced: [ReclyCore.Range] = [],
        gaps: [ReclyCore.Range]
    ) async -> StopResult {
        do {
            // Always walk the directory: a marker is only a hint. A boundary whose `addPart` failed
            // for a storage reason may have failed to write its marker for the same reason, and
            // then the file itself is the only record of that audio. Filed parts are not re-hashed.
            guard let reconciled = try await reconcile(recordingId: recordingId) else {
                return .notRecording
            }
            if reconciled.pending > 0 {
                // Known limitation, the same one M4 accepted for `gaps` (docs/03 "알려진 한계"):
                // neither list survives a deferred stop, because the recovery pass that finalizes
                // later never saw them. Persisting them in a sidecar the recovery reads is a
                // follow-up for both platforms, not this lane's.
                log(.error, "rec.recorder.stopDeferred", [
                    "recordingId": recordingId,
                    "pending": reconciled.pending,
                    // Not written anywhere yet: `finalize` is what carries them, and it is not running.
                    "gaps": gaps.count,
                    "silenced": silenced.count,
                ])
                return .deferred(recordingId: recordingId, pending: reconciled.pending)
            }
            let finalized = try await core.recordings.finalize(
                recordingId: recordingId,
                endedAt: core.deps.clock.now(),
                // What is on disk is the truth; the ledger only backs an empty directory.
                durationSec: reconciled.files + reconciled.quarantined > 0 ? reconciled.durationSec : ledgerSec,
                title: title,
                silenced: silenced,
                gaps: gaps
            )
            return .finalized(
                RecordingOutcome(
                    recordingId: recordingId,
                    durationSec: finalized.meta.durationSec?.doubleValue ?? ledgerSec,
                    parts: finalized.meta.parts.count
                )
            )
        } catch {
            // The row is still open and the audio is still on disk: the next recovery pass finishes
            // it, which is exactly what a deferred stop means.
            log(.error, "rec.recorder.stopFailed", ["recordingId": recordingId, "error": "\(error)"])
            return .deferred(recordingId: recordingId, pending: 0)
        }
    }

    /// Brings an already-finalized recording's meta up to date after a late part joined it.
    func refinalize(record: RecordingRecord, reconciled: Reconciled) async throws {
        let known = record.meta.endedAt.flatMap { KotlinInstant.companion.parseOrNull(input: $0) }
        let fromFiles = KotlinInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64((reconciled.endedAt.timeIntervalSince1970 * 1000).rounded())
        )
        _ = try await core.recordings.finalize(
            recordingId: record.id,
            endedAt: known.map { $0.compareTo(other: fromFiles) > 0 ? $0 : fromFiles } ?? fromFiles,
            durationSec: reconciled.durationSec,
            title: nil,
            silenced: [],
            gaps: []
        )
    }

    func markers(in directory: URL) -> [URL] {
        contents(of: directory).filter { $0.lastPathComponent.hasSuffix(Self.pendingSuffix) }
    }

    /// One part of one recording: its number and the files of its tracks, in a stable order.
    private struct Group {
        let part: Int
        let files: [(url: URL, track: Track)]
    }

    /// The directory's segment files, in part order and grouped by part. `_pNNN_` comes before the
    /// track name in every file name (docs/03 "이름 규칙"), so sorting by name is already part-major
    /// and the grouping only has to notice where one number ends.
    private func segmentGroups(in directory: URL) -> [Group] {
        let segments = contents(of: directory)
            .compactMap { url -> (url: URL, part: Int, track: Track)? in
                guard let named = Self.segment(of: url.lastPathComponent) else { return nil }
                return (url, named.part, named.track)
            }
            .sorted { $0.url.lastPathComponent < $1.url.lastPathComponent }

        var groups: [Group] = []
        for segment in segments {
            if let last = groups.last, last.part == segment.part {
                groups[groups.count - 1] = Group(
                    part: last.part,
                    files: last.files + [(segment.url, segment.track)]
                )
            } else {
                groups.append(Group(part: segment.part, files: [(segment.url, segment.track)]))
            }
        }
        return groups
    }

    private func contents(of directory: URL) -> [URL] {
        (try? files.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
    }

    /// A segment whose container cannot be read is the tail the process died inside: the trailing
    /// MPEG-4 atoms were never written, so nothing on this machine can say how long it is.
    ///
    /// It is not registered. A part carrying a length nobody measured is worse than a missing one —
    /// it shifts every offset after it and is uploaded and transcribed as though it were whole.
    /// Renaming it out of the way is what lets every later pass finalize through the last part
    /// that *is* readable, instead of stalling on this one forever. A recording with readable parts
    /// keeps it beside them; one with nothing else is dropped, and it goes with the directory.
    ///
    /// Its `.pending` marker goes with it, if it had one: a marker whose file will never be filable
    /// would defer the finalize for good.
    private func quarantine(_ url: URL, recordingId: String) {
        clearMarker(for: url)
        let quarantined = url.appendingPathExtension(String(Self.corruptSuffix.dropFirst()))
        do {
            try files.moveItem(at: url, to: quarantined)
        } catch {
            // The rename is the only thing that keeps the next pass from meeting the same file
            // again; if even that fails the pass still goes on, and so does the recording.
            return log(.error, "rec.part.corrupt", [
                "recordingId": recordingId,
                "file": url.lastPathComponent,
                "quarantined": false,
            ])
        }
        log(.error, "rec.part.corrupt", [
            "recordingId": recordingId,
            "file": url.lastPathComponent,
            "quarantined": true,
        ])
    }

    private func clearMarker(for segment: URL) {
        try? files.removeItem(at: marker(for: segment))
    }

    private func markPending(for segment: URL) {
        try? Data().write(to: marker(for: segment))
    }

    private func marker(for segment: URL) -> URL {
        segment.deletingLastPathComponent()
            .appendingPathComponent(segment.lastPathComponent + Self.pendingSuffix)
    }

    private var fileSystem: OkioFileSystem { OkioFileSystem.companion.SYSTEM }

    private func log(_ level: LoggerLevel, _ event: String, _ fields: [String: Any]) {
        core.deps.logger.log(level: level, event: event, fields: fields, error: nil)
    }

    /// What a segment file on disk turns out to be. The three cases are three different fates, and
    /// telling them apart is the whole of a recovery pass's judgement about one file.
    enum SegmentAudio {
        /// A closed container with this many seconds in it — the only honest length there is.
        case readable(Double)
        /// A container the encoder never got a frame into (or no file worth the name): the segment
        /// the engine had opened and was about to use when the recording ended.
        case empty
        /// No readable container at all — the tail the process died inside, whose trailing MPEG-4
        /// atoms were never written.
        case unreadable
    }

    static func segmentAudio(of url: URL) -> SegmentAudio {
        guard let file = try? AVAudioFile(forReading: url), file.fileFormat.sampleRate > 0 else {
            return .unreadable
        }
        guard file.length > 0 else { return .empty }
        return .readable(Double(file.length) / file.fileFormat.sampleRate)
    }

    /// The container's own duration. `nil` when there is none to be had, whichever of the two
    /// reasons it is — the callers that ask this way only want the number.
    static func containerDurationSec(of url: URL) -> Double? {
        guard case .readable(let seconds) = segmentAudio(of: url) else { return nil }
        return seconds
    }

    static func partNumber(of file: String) -> Int? {
        segment(of: file)?.part
    }

    /// The part number and the track a segment file's name carries (docs/03 "이름 규칙"). It is the
    /// only place either of them exists once a process has died: the row may never have been
    /// written, but the name on disk always says which slice of which track this is.
    static func segment(of file: String) -> (part: Int, track: Track)? {
        guard let match = segmentPattern.firstMatch(
            in: file,
            range: NSRange(file.startIndex..., in: file)
        ),
            let digits = Swift.Range(match.range(at: 1), in: file),
            let part = Int(file[digits]),
            let wire = Swift.Range(match.range(at: 2), in: file),
            let track = trackNames[String(file[wire])]
        else { return nil }
        return (part, track)
    }

    private static let segmentPattern = try! NSRegularExpression(
        pattern: "_p(\\d+)_(\(trackNames.keys.sorted().joined(separator: "|")))\\.m4a$"
    )

    /// The wire names `MetaWriter.partFileName` puts in the file name, read back. Written out rather
    /// than derived, because `Track` comes across the Obj-C bridge as a class whose `name` is the
    /// Kotlin constant (`MONO`), not the serialised one (`mono`).
    private static let trackNames: [String: Track] = [
        "mono": Track.mono,
        "mic": Track.mic,
        "sys": Track.sys,
        "mix": Track.mix,
    ]
}
