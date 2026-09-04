import AVFoundation
import Foundation
import ReclyCore

/// One recording as a chain of `.m4a` segments (ADR-006). An unsegmented three-hour recording is
/// three hours lost to one crash; segments cap the loss at one boundary and keep every part under
/// the 25 MB transcription limit.
///
/// The boundary is lossless because nothing is torn down to cross it. `AVAudioFile` has no "switch
/// to the next file" of its own, so the tap callback that finds the segment full cuts its own
/// buffer at that exact frame, closes the file, opens the next one and writes the remainder into
/// it — all before it returns. Hashing and `addPart` happen afterwards, off the audio thread.
///
/// Two things guard it. [lock] covers the session the tap callback mutates, because `start`, `stop`
/// and the tap all reach it from threads nobody here owns. [control] serialises everything that
/// touches the *input* — the start, the restart a device change asks for, the stop — because two of
/// those running at once is how a recording ends with a live tap and no engine.
///
/// In `meeting` mode there are three tracks and still one of everything else. The microphone is the
/// clock: one `SegmentSplitter`, one frame count, one set of part numbers, and every microphone
/// buffer that reaches a file takes the same number of system frames and the same number of mixed
/// frames with it (docs/03 "같은 시작 시각·세그먼트 경계"). The system stream is put on that timeline
/// by `DriftCompensator` before it ever gets here.
public final class SegmentedRecorder {
    /// ADR-006. Overridable so a smoke test does not have to run for fifteen minutes.
    public static let defaultSegmentSec = 900
    public static let bitrateKbps = 32
    /// docs/03: 16 kHz mono. Unlike Android there is no fallback rate to negotiate — the device's
    /// own rate is whatever it is and `AVAudioConverter` resamples it, so the file always gets the
    /// rate the meta promises.
    public static let sampleRateHz = 16_000

    /// Everything one recording owns. A class, because the tap callback mutates it in place.
    private final class Session {
        let recordingId: String
        let directory: URL
        /// The file's own PCM format — what the converter targets and what `write(from:)` demands.
        let format: AVAudioFormat
        /// One per track, in the order the meta lists them. [mic] is the first of them and the one
        /// the microphone's own frames go into.
        let writers: [TrackWriter]
        /// One boundary for every track: this is what makes the part numbers match.
        var splitter: SegmentSplitter
        var converter: AVAudioConverter?
        /// The tap's format, kept so a second notification about the same device change is a no-op.
        var inputFormat: AVAudioFormat?
        /// The system stream on the microphone's timeline. `nil` in `microphone` mode.
        let drift: DriftCompensator?
        var totalFrames: AVAudioFramePosition = 0
        var gaps: [ReclyCore.Range] = []
        /// docs/03 `silenced`: the interruptions the microphone was taken away by, closed at stop.
        var silence = SilenceMonitor()
        /// docs/09 화면 원칙 6: the levels the shells draw while this recording runs. On the session,
        /// so the strip starts empty with every recording and is gone the moment one ends.
        var live = LiveWaveform()

        init(
            recordingId: String,
            directory: URL,
            writers: [TrackWriter],
            splitter: SegmentSplitter,
            drift: DriftCompensator?
        ) {
            self.recordingId = recordingId
            self.directory = directory
            self.writers = writers
            self.splitter = splitter
            self.drift = drift
            format = writers[0].format
        }

        /// The microphone's writer — the one the recorder's own converter feeds.
        var mic: TrackWriter { writers[0] }
    }

    private let core: ReclyCore_
    private let segmentSec: Int
    private let source: Source
    private let input: AudioInput
    private let systemInput: (any SystemAudioInput)?
    private let driftIntervalSec: Double
    private let onError: (RecorderError) -> Void

    private let lock = NSLock()
    /// The one queue every input operation runs on, in the order it was asked for.
    private let control = DispatchQueue(label: "app.recly.mac.recorder.control")
    private var session: Session?
    /// A session a stop has taken but has not filed yet. The tap is stopped after that hand-over,
    /// and a tap that was still trying to rebuild reports the outage it had open on its way out
    /// (`ProcessTapCapture.stop`) — which would otherwise arrive at [recordOutage] with no session
    /// to write it into, and the recording would be filed without the hole in it. Cleared as the
    /// `gaps` are read, which is after the control queue has drained.
    private var closing: Session?
    /// Boundary registrations, chained end to end: they touch the same `meta.json` the repository
    /// rewrites, and a stop has to know when the last of them has landed.
    private var registrations: Task<Void, Never>?
    /// A restart is queued, or one is running. Either way the next notification about the same
    /// device change has nothing to add.
    private var restartPending = false
    private var restarting = false
    /// The tap is started once per recording, by the first [attach]; the restarts that follow are
    /// the microphone's business and must not take it down. On [control] only.
    private var systemStarted = false

    /// Called with the file a boundary has just closed and released, before anything reads it.
    /// `nil` in the app: it exists because "the container came back unreadable" is the one state no
    /// fake input can produce from outside — that file is written by the real AAC encoder.
    var afterSegmentClosed: ((URL) -> Void)?

    /// The correction the system stream is being resampled by. There is no other way to see the
    /// estimator from outside, and what it is doing is the difference between a `sys` track that
    /// lines up with `mic` after an hour and one that does not.
    var driftRatio: Double? {
        lock.withLock { session?.drift?.ratio }
    }

    /// docs/12 M4-L3 "메뉴바": the output device the system tap is on, for the menu to name while a
    /// meeting is being recorded. `nil` outside meeting mode, and until the tap is up.
    public var capturedOutputDevice: String? {
        systemInput?.outputDeviceName
    }

    public convenience init(
        core: ReclyCore_,
        segmentSec: Int = SegmentedRecorder.defaultSegmentSec,
        source: Source = Source.desktop,
        voiceProcessing: Bool = false,
        onError: @escaping (RecorderError) -> Void
    ) {
        #if os(macOS)
        let systemInput = ProcessTapCapture()
        let input: AudioInput = MicrophoneInput(voiceProcessing: voiceProcessing)
        #else
        let systemInput: (any SystemAudioInput)? = nil
        // docs/13: the phone's and the watch's microphone is the engine plus the session around it —
        // the category the lock screen and the dropped wrist need, and the interruptions a call is.
        let input: AudioInput = IOSAudioInput()
        #endif
        self.init(
            core: core,
            segmentSec: segmentSec,
            source: source,
            input: input,
            systemInput: systemInput,
            onError: onError
        )
    }

    /// The microphone and the process tap are the defaults and the only ones the app uses; a test
    /// supplies its own so the whole path — tap to boundary to registered part, on three tracks —
    /// runs without hardware and without a permission prompt.
    init(
        core: ReclyCore_,
        segmentSec: Int = SegmentedRecorder.defaultSegmentSec,
        source: Source = Source.desktop,
        input: AudioInput,
        systemInput: (any SystemAudioInput)? = nil,
        driftIntervalSec: Double = DriftEstimator.intervalSec,
        onError: @escaping (RecorderError) -> Void
    ) {
        self.core = core
        self.segmentSec = segmentSec
        self.source = source
        self.input = input
        self.systemInput = systemInput
        // Overridable for the same reason [segmentSec] is: a test cannot wait sixty seconds to see
        // the estimator move, and every path that moves it is one a recording depends on.
        self.driftIntervalSec = driftIntervalSec
        self.onError = onError
        input.onConfigurationChange = { [weak self] reason in self?.requestRestart(reason: reason) }
        // docs/03 `silenced`: the microphone taken away and given back. Not a restart — the input
        // brings itself back — so all it leaves is the range in the meta.
        input.onSilence = { [weak self] silenced in self?.recordSilence(silenced) }
        // The tap heals itself; all the recording wants from it is where the hole was.
        systemInput?.onOutage = { [weak self] reason, seconds in self?.recordOutage(reason, seconds) }
    }

    var isRecording: Bool {
        lock.withLock { session != nil }
    }

    /// Audio actually written so far, which is what the menu counts. Frames, not the wall clock:
    /// a device change that cost three seconds should not be three seconds of recording.
    public var recordedSec: Double {
        lock.withLock { session.map { Double($0.totalFrames) / Double(Self.sampleRateHz) } ?? 0 }
    }

    /// docs/09 화면 원칙 6: the tenth-of-a-second peaks of the track a person hears, oldest first,
    /// for the strip beside the clock. Empty when nothing is being recorded, which is what makes a
    /// shell's "draw it while recording" the whole of the condition.
    public func livePeaks() -> [Float] {
        lock.withLock { session?.live.peaks ?? [] }
    }

    /// Creates the recording row and `meta.json`, then opens the microphone — and, in `meeting`
    /// mode, the system tap. Returns the id the rest of the pipeline uses; the caller keeps it to
    /// `stop` and to enqueue.
    ///
    /// A meeting whose tap cannot be built throws `.systemAudioUnavailable` and leaves nothing
    /// behind, rather than quietly recording one track under a meta that promises three: the shell's
    /// answer is the permission deep link and the offer to record the microphone alone.
    @discardableResult
    public func start(
        workflowId: String?,
        title: String?,
        mode: RecordingMode = .microphone,
        context: Context? = nil
    ) async throws -> String {
        guard !isRecording else { throw RecorderError("already recording") }
        if mode == .meeting, systemInput == nil {
            throw RecorderError("this device cannot capture system audio", kind: .systemAudioUnavailable)
        }
        try await input.authorize()

        let startedAt = core.deps.clock.now()
        // The id's own timestamp is the recording's start, not "whenever this ran".
        let recordingId = Ulid.shared.generate(clock: FixedKotlinClock(startedAt))
        let draft = meta(
            recordingId: recordingId,
            startedAt: startedAt,
            workflowId: workflowId,
            title: title,
            mode: mode,
            context: context
        )
        let base = MetaWriter.shared.baseName(meta: draft)
        let directory = core.deps.dataDir.url
            .appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(base, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try await core.recordings.create(meta: draft, dir: directory.okioPath)

        // Past this point a row and a directory exist: anything that throws has to give both back,
        // or the next start finds a half-open recording and an engine nobody owns.
        do {
            let writers = try Self.tracks(of: mode).map {
                try TrackWriter(track: $0, base: base, directory: directory)
            }
            writers.forEach { writer in
                writer.afterSegmentClosed = { [weak self] url in self?.afterSegmentClosed?(url) }
            }
            let session = Session(
                recordingId: recordingId,
                directory: directory,
                writers: writers,
                splitter: SegmentSplitter(
                    framesPerSegment: AVAudioFrameCount(segmentSec * Self.sampleRateHz)
                ),
                drift: mode == .meeting ? DriftCompensator(
                    target: writers[0].format,
                    intervalSec: driftIntervalSec,
                    startSec: Self.nowSec
                ) : nil
            )
            lock.withLock {
                self.session = session
                self.registrations = nil
            }
            try await onControl { try self.attach(to: session) }
        } catch {
            await abandon(recordingId: recordingId, directory: directory, cause: error)
            throw error as? RecorderError ?? RecorderError("could not open the recording", underlying: error)
        }

        log(.info, "rec.recorder.start", [
            "recordingId": recordingId,
            "sampleRateHz": Self.sampleRateHz,
            "segmentSec": segmentSec,
            "tracks": Self.tracks(of: mode).count,
        ])
        return recordingId
    }

    /// docs/03 "트랙": the desktop's meeting recording is `mic`/`sys`/`mix`; the microphone on its
    /// own is one `mono` track, which is what M4-L2 wrote and what a mobile recording writes.
    static func tracks(of mode: RecordingMode) -> [Track] {
        switch mode {
        case .microphone: return [Track.mono]
        case .meeting: return [Track.mic, Track.sys, Track.mix]
        }
    }

    /// Gives the microphone back, closes the last segment and hands the recording to the core.
    /// `.notRecording` for a second stop — the menu item and a fatal capture error can both land,
    /// and neither finalize nor enqueue may happen twice.
    public func stop(title: String?) async -> StopResult {
        // Taken in one step: after this the tap callback finds no session and returns, so nothing
        // else can be writing while the last segment is closed and hashed. It is also what cancels
        // a restart that is queued but has not run — it will find its session gone.
        let taken: Session? = lock.withLock {
            guard let open = session else { return nil }
            session = nil
            closing = open
            return open
        }
        guard let open = taken else { return .notRecording }

        // The microphone is given back before any of the bookkeeping, so a recording that cannot be
        // filed still stops being a recording — and on the control queue, so it lands *behind* a
        // restart that is halfway through rather than pulling the input out from under it.
        //
        // The tap goes first and its resampler is emptied into the queue, so that the microphone's
        // own tail — which is drained next, and which pulls system frames along with it — has the
        // last of the system audio there to pull.
        _ = try? await onControl {
            self.stopSystemInput()
            open.drift?.drain()
            self.input.stop()
        }
        // No tap can be running now, so the converter is this thread's alone: whatever it is still
        // holding is the last audio of the recording and belongs in the file.
        lock.withLock { drain(open) }
        // Read only now. A restart that was in flight is still writing its `gaps` entry until the
        // queue has drained, and a snapshot taken before that files the recording without the very
        // outage it just covered. The registrations are taken here too, and not with the session,
        // because the drain can cross the last boundary and queue one more.
        let (recordedSec, gaps, silenced, pending) = lock.withLock {
            let pending = registrations
            registrations = nil
            closing = nil
            let at = Double(open.totalFrames) / Double(Self.sampleRateHz)
            // A recording the user stopped while the call that silenced it was still going: the
            // range is open, and closing it here is what puts it in the meta at all.
            open.silence.close(positionSec: at, uptimeSec: Self.nowSec)
            return (at, open.gaps, open.silence.ranges, pending)
        }
        // Releasing the files is what writes the trailing MPEG-4 atoms; until it happens the last
        // part of each track is a container `AVAudioFile(forReading:)` cannot open.
        open.writers.forEach { $0.release() }

        // No boundary registration may land after the finalize: once the row says `finalized`
        // nothing looks at the directory again, and a part arriving late would not be uploaded.
        _ = await pending?.value

        let result = await PartReconciler(core: core).closeOut(
            recordingId: open.recordingId,
            ledgerSec: recordedSec,
            title: title,
            silenced: silenced,
            gaps: gaps
        )
        if case .finalized(let outcome) = result {
            log(.info, "rec.recorder.stop", [
                "recordingId": outcome.recordingId,
                "durationSec": outcome.durationSec,
                "parts": outcome.parts,
                "gaps": gaps.count,
                "silenced": silenced.count,
            ])
        }
        return result
    }

    // MARK: - The audio path

    /// Runs on the input's own thread. Everything it can throw is the recording ending, so it says
    /// so once and lets the shell stop; a boundary that cannot be *filed* is not one of those.
    private func receive(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        defer { lock.unlock() }
        guard let session, let converter = session.converter else { return }
        do {
            guard let converted = try convert(buffer, with: converter, to: session.format) else { return }
            try write(converted, into: session)
            // After the write, so the queue's depth is read at the one phase where it means "how far
            // out of step are the two streams" rather than "how much has piled up since the last
            // microphone buffer" (docs/12 "60초마다 레이트 차 추정").
            session.drift?.observeMic(frames: session.totalFrames, atSec: Self.nowSec)
        } catch {
            report(RecorderError("could not write the segment", underlying: error))
        }
    }

    /// The device's rate and channel count to 16 kHz mono. One converter for the life of the tap:
    /// a resampler that is thrown away every buffer loses its filter state at each seam.
    private func convert(
        _ buffer: AVAudioPCMBuffer,
        with converter: AVAudioConverter,
        to format: AVAudioFormat
    ) throws -> AVAudioPCMBuffer? {
        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }

        var supplied = false
        var failure: NSError?
        // `.noDataNow` on the second ask rather than `.endOfStream`: the converter keeps whatever it
        // could not finish and hands it to the next callback, which is why no frame is lost in the
        // middle of a recording. What it is holding when there *is* no next callback is [drain].
        let status = converter.convert(to: out, error: &failure) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        if let failure { throw failure }
        guard status != .error, out.frameLength > 0 else { return nil }
        return out
    }

    /// The tail the converter is still holding. Every `convert` above answers the second ask with
    /// `.noDataNow`, which tells the converter to keep what it could not finish for the callback
    /// that follows — and at a stop, or when the input is torn down for a device change, there is no
    /// callback that follows. One `.endOfStream` ask is what empties it; skipping it loses the
    /// resampler's filter delay at every restart and at the end of every recording.
    ///
    /// Under [lock], with the input already stopped or about to be dropped, so nothing else is
    /// touching the converter. It is spent afterwards, which is why both callers drop it —
    /// [attach] builds a fresh one for the tap that comes next.
    private func drain(_ session: Session) {
        guard let converter = session.converter, session.mic.isOpen else { return }
        // A second of room for what is at most a filter's worth of frames: the one thing this must
        // not do is come up short and lose the very frames it exists to save.
        guard let out = AVAudioPCMBuffer(
            pcmFormat: session.format,
            frameCapacity: AVAudioFrameCount(Self.sampleRateHz)
        ) else { return }

        var failure: NSError?
        let status = converter.convert(to: out, error: &failure) { _, outStatus in
            outStatus.pointee = .endOfStream
            return nil
        }
        guard failure == nil, status != .error, out.frameLength > 0 else { return }
        do {
            try write(out, into: session)
        } catch {
            report(RecorderError("could not write the last of the audio", fatal: false, underlying: error))
        }
    }

    /// Writes the buffer into the open segment of every track, crossing as many 900-second
    /// boundaries as it has to. [buffer] is the microphone; the system and mixed tracks are made
    /// from it and from the frames the drift compensator has waiting, chunk by chunk, so that all
    /// three cross the boundary at the same frame.
    private func write(_ buffer: AVAudioPCMBuffer, into session: Session) throws {
        for chunk in session.splitter.split(frames: buffer.frameLength) {
            guard let piece = Self.slice(buffer, offset: chunk.offset, count: chunk.count) else { return }
            // No open file means a rollover could not open the next one, which was reported as
            // fatal. Stop counting: `totalFrames` is the recording's length, and audio that went
            // nowhere is not part of it.
            guard try writeTracks(piece, of: session) else { return }
            session.totalFrames += AVAudioFramePosition(chunk.count)
            if chunk.closesSegment {
                // `closeSegments` has returned, so the files it closed are released and their
                // trailing atoms are on disk — only now may anything hash or read them.
                register(closeSegments(session), in: session.directory, recordingId: session.recordingId)
            }
        }
    }

    /// The microphone chunk into `mic`, the system frames that sit under it into `sys`, and their
    /// sum at half scale into `mix` (docs/12 "합산 −6 dB 헤드룸"). One track in `microphone` mode,
    /// where the chunk goes into `mono` unchanged.
    ///
    /// False if any of them has no file to write into, and then none of the frames are counted:
    /// three tracks that disagree about how much audio a part holds are worse than a short one.
    private func writeTracks(_ mic: AVAudioPCMBuffer, of session: Session) throws -> Bool {
        guard let drift = session.drift else {
            guard try session.mic.write(mic) else { return false }
            Self.observe(mic, in: session)
            return true
        }
        guard let sys = AVAudioPCMBuffer(pcmFormat: session.format, frameCapacity: mic.frameLength) else {
            return false
        }
        drift.take(frames: mic.frameLength, into: sys)
        guard let mixed = Self.mix(mic, sys), session.writers.allSatisfy(\.isOpen) else { return false }
        for (writer, buffer) in zip(session.writers, [mic, sys, mixed]) {
            guard try writer.write(buffer) else { return false }
        }
        Self.observe(mixed, in: session)
        return true
    }

    /// The frames a writer has just taken, into the strip the shells draw (docs/09 화면 원칙 6) —
    /// after the write rather than before it, so what the strip shows is what the file got. The
    /// track a person hears: `mono` in microphone mode, `mix` in a meeting.
    private static func observe(_ heard: AVAudioPCMBuffer, in session: Session) {
        guard let samples = heard.floatChannelData else { return }
        session.live.add(UnsafeBufferPointer(start: samples[0], count: Int(heard.frameLength)))
    }

    /// `mix` — the file the user's own AI is meant to eat, where the separate tracks are for speaker
    /// diarisation (docs/research/02 §데스크톱 캡처). Half scale each, so two streams that are both
    /// loud add up to full scale rather than clipping.
    ///
    /// The two buffers are the same length by construction: `take` fills exactly as many system
    /// frames as the microphone chunk it goes under, silence included.
    static func mix(_ mic: AVAudioPCMBuffer, _ sys: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard sys.frameLength == mic.frameLength,
              let out = AVAudioPCMBuffer(pcmFormat: mic.format, frameCapacity: mic.frameLength),
              let left = mic.floatChannelData,
              let right = sys.floatChannelData,
              let destination = out.floatChannelData
        else { return nil }
        for index in 0 ..< Int(mic.frameLength) {
            destination[0][index] = (left[0][index] + right[0][index]) * 0.5
        }
        out.frameLength = mic.frameLength
        return out
    }

    /// The segment is full on every track: close them all and open the next one on each. Every
    /// writer gets the same duration because they were handed the same number of frames.
    private func closeSegments(_ session: Session) -> [ClosedSegment] {
        // A chunk only closes a segment by filling it exactly, so this is the length, to the frame.
        let durationSec = Double(session.splitter.framesPerSegment) / Double(Self.sampleRateHz)
        let closed = session.writers.map { $0.closeSegment(durationSec: durationSec) }
        for writer in session.writers {
            do {
                try writer.openNext()
            } catch {
                report(RecorderError(
                    "could not open segment \(writer.openPart) of \(writer.track.name)",
                    underlying: error
                ))
            }
        }
        return closed
    }

    /// A part that cannot reach the database is not a lost part: the audio is on disk and a sidecar
    /// says so, so the next recovery pass files it (docs/03 "크래시 시 마지막 경계까지는 복구 가능").
    ///
    /// One task for the whole boundary, so the tracks of a part are filed in order and a stop that
    /// waits for the last of them waits for all three.
    private func register(_ closed: [ClosedSegment], in directory: URL, recordingId: String) {
        let previous = registrations
        registrations = Task { [core] in
            _ = await previous?.value
            for segment in closed {
                await self.register(segment, in: directory, recordingId: recordingId, core: core)
            }
        }
    }

    private func register(
        _ closed: ClosedSegment,
        in directory: URL,
        recordingId: String,
        core: ReclyCore_
    ) async {
        let url = directory.appendingPathComponent(closed.file)
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            let bytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            guard bytes > 0 else {
                // No audio, so nothing to lose — and no marker, which is the point: a marker
                // whose file the reconciler then deletes would defer the finalize for good.
                return self.report(RecorderError(
                    "segment \(closed.part) closed with nothing in it",
                    fatal: false
                ))
            }
            // A container can only be read once the file has been let go, so reading it here is
            // the check that the boundary really did let go before the hash was taken. The
            // length the part carries stays the ledger's, which is exact to the frame where the
            // container rounds to the encoder's granule.
            //
            // When it cannot be read back at all, registering it anyway would put a part in the
            // meta carrying a length nothing measured and a sha256 of bytes no decoder will
            // accept — uploaded and transcribed as though it were whole, and shifting every
            // offset after it. So it is a registration failure like any other, marker and all,
            // and the reconciler's rule for an unreadable segment settles it: `.corrupt`
            // (docs/03), the marker cleared with it, and the recording finalized through the
            // parts that *are* readable. Whichever pass meets the file first does that — the
            // stop's own `closeOut` if the recording ends here, the next recovery if it does not.
            if PartReconciler.containerDurationSec(of: url) == nil {
                try? Data().write(to: directory.appendingPathComponent(closed.file + PartReconciler.pendingSuffix))
                self.log(.error, "rec.part.unreadable", [
                    "recordingId": recordingId,
                    "file": closed.file,
                ])
                return self.report(RecorderError(
                    "segment \(closed.part) closed unreadable",
                    fatal: false
                ))
            }
            let sha256 = try await PartHasher.shared.sha256(
                fs: OkioFileSystem.companion.SYSTEM,
                path: url.okioPath
            )
            try await core.recordings.addPart(
                recordingId: recordingId,
                part: Part_(
                    part: Int32(closed.part),
                    track: closed.track,
                    file: closed.file,
                    bytes: bytes,
                    sha256: sha256,
                    startOffsetSec: closed.startOffsetSec,
                    durationSec: closed.durationSec
                )
            )
        } catch {
            try? Data().write(to: directory.appendingPathComponent(closed.file + PartReconciler.pendingSuffix))
            self.report(RecorderError(
                "could not register part \(closed.part)",
                fatal: false,
                underlying: error
            ))
        }
    }

    /// Copies [count] frames out of [buffer] so `AVAudioFile.write` — which always writes a whole
    /// buffer from frame zero — can be handed the piece that belongs in this segment.
    private static func slice(
        _ buffer: AVAudioPCMBuffer,
        offset: AVAudioFrameCount,
        count: AVAudioFrameCount
    ) -> AVAudioPCMBuffer? {
        if offset == 0, count == buffer.frameLength { return buffer }
        guard count > 0,
              let piece = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: count),
              let source = buffer.floatChannelData,
              let destination = piece.floatChannelData
        else { return nil }
        for channel in 0 ..< Int(buffer.format.channelCount) {
            destination[channel].update(from: source[channel].advanced(by: Int(offset)), count: Int(count))
        }
        piece.frameLength = count
        return piece
    }

    // MARK: - The input

    /// ADR-006's audio settings, in the one place that opens a segment. `internal` so a test can
    /// open a real one and read it back: the meta promises AAC-LC 16 kHz mono, and nothing else
    /// here would notice if the platform quietly wrote something else.
    static func openSegmentFile(named name: String, in directory: URL) throws -> AVAudioFile {
        try AVAudioFile(
            forWriting: directory.appendingPathComponent(name),
            settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: Self.sampleRateHz,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: Self.bitrateKbps * 1000,
            ],
            // The processing format the tap has to produce, said out loud rather than inferred:
            // `write(from:)` rejects a buffer whose format is not exactly this one.
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )
    }

    /// On the control queue. Publishes the converter before the tap exists, and under the lock,
    /// because the callback reads it the instant the input starts.
    private func attach(to session: Session) throws {
        guard let format = input.format else {
            throw RecorderError("the default input device reports no usable format")
        }
        guard let converter = AVAudioConverter(from: format, to: session.format) else {
            throw RecorderError("no converter from \(format) to 16 kHz mono")
        }
        lock.withLock {
            session.converter = converter
            session.inputFormat = format
        }
        try input.start { [weak self] buffer in
            self?.receive(buffer)
        }
        // The microphone first, then the tap, and only on the first attach — a restart is about the
        // microphone's device, and the tap looks after its own. Starting the tap second is what
        // keeps the system queue from opening with a lead of audio that was played before the
        // recording began, which would sit under the microphone late for the rest of it.
        guard let drift = session.drift, let systemInput, !systemStarted else { return }
        try systemInput.start { buffer in
            drift.append(buffer, atSec: Self.nowSec)
        }
        systemStarted = true
    }

    /// On the control queue, and idempotent: the next recording has to be able to start its own tap.
    private func stopSystemInput() {
        guard systemStarted else { return }
        systemStarted = false
        systemInput?.stop()
    }

    /// An outage the tap covered by itself (docs/12 "tap 재생성"). It is not a restart — the
    /// microphone never stopped — so all it leaves is the hole in the meta's `gaps`.
    private func recordOutage(_ reason: String, _ seconds: TimeInterval) {
        lock.withLock {
            // [closing] is the stop's own session: the tap reports an outage that was still open
            // when it was told to stop, and by then the recording is no longer the current one.
            guard let session = session ?? closing else { return }
            let at = Double(session.totalFrames) / Double(Self.sampleRateHz)
            session.gaps.append(ReclyCore.Range(startSec: max(0, at - seconds), endSec: at, reason: reason))
            // The interval this fell in has a hole in the system frames, and a rate read across it
            // would be the hole rather than the clocks (docs/12 "60초마다 레이트 차 추정"). Started
            // again from here, so the next sixty seconds measure two streams that were both there.
            session.drift?.reanchor(
                micFrames: session.totalFrames, atSec: Self.nowSec, outageSec: seconds
            )
        }
        log(.warn, "rec.recorder.tapRecreated", ["reason": reason, "seconds": seconds])
    }

    /// docs/03 `silenced`: an interruption took the microphone (iOS — a call, Siri) and gave it
    /// back. The engine was stopped meanwhile, so the recording's own position does not move across
    /// it; the range is placed there and stretched by the wall clock the interruption lasted (see
    /// [SilenceMonitor]).
    private func recordSilence(_ silenced: Bool) {
        lock.withLock {
            // [closing] for the same reason [recordOutage] has it: an interruption that ends as the
            // stop is running is still this recording's, and the `silenced` list is read after the
            // control queue has drained.
            guard let session = session ?? closing else { return }
            session.silence.set(
                silenced,
                positionSec: Double(session.totalFrames) / Double(Self.sampleRateHz),
                uptimeSec: Self.nowSec
            )
        }
        log(.warn, "rec.recorder.silenced", ["silenced": silenced])
    }

    private static var nowSec: Double { ProcessInfo.processInfo.systemUptime }

    /// docs/12: the tap is rebuilt when the hardware moves under it — a headset unplugged, the
    /// default input switched in System Settings, a format change — and the audio that was missing
    /// meanwhile goes into the meta's `gaps`.
    ///
    /// Coalescing is decided here, at the notification, not on the queue. Both the HAL listener and
    /// `AVAudioEngineConfigurationChange` fire for one device change, and a second teardown queued
    /// behind the first would take the tap down again the instant it came back — and write a second
    /// `gaps` entry for an outage that had already ended.
    private func requestRestart(reason: String) {
        let taken: Session? = lock.withLock {
            guard let open = session, !restartPending, !restarting else { return nil }
            restartPending = true
            return open
        }
        guard let session = taken else { return }
        control.async { self.restart(session, reason: reason) }
    }

    private func restart(_ session: Session, reason: String) {
        let go: Bool = lock.withLock {
            restartPending = false
            // A stop that landed after this was queued took the session with it, and so would a
            // start that began a different recording. Either way there is nothing to bring back:
            // the `input.stop()` a stop queues behind this is what leaves the tap removed.
            guard self.session === session else { return false }
            // Whichever notification arrived second finds a running input on the format it wanted.
            if input.isRunning, session.inputFormat == input.format { return false }
            // Emptied and then dropped, in the one critical section: a tap callback waiting on this
            // lock must not put another buffer through a converter that has already ended, and the
            // frames it was holding are audio the device really did capture.
            //
            // Dropping it is also what makes an in-flight tap callback return without writing:
            // `input.stop()` waits for that callback, and it is waiting for this lock.
            drain(session)
            session.converter = nil
            restarting = true
            return true
        }
        guard go else { return }
        defer { lock.withLock { restarting = false } }

        let lostAt = Date()
        input.stop()
        do {
            // A stop can land while the input is down. Reattaching then would leave a live tap —
            // and a lit microphone — behind a recording nobody owns any more.
            guard lock.withLock({ self.session === session }) else { return }
            try attach(to: session)
            lock.withLock {
                // Written even if a stop has already taken the session: the outage happened and it
                // is over, and `stop` reads these only once this queue has drained, so it sees it.
                let lostSec = Date().timeIntervalSince(lostAt)
                let at = Double(session.totalFrames) / Double(Self.sampleRateHz)
                session.gaps.append(
                    ReclyCore.Range(startSec: at, endSec: at + lostSec, reason: reason)
                )
                // The microphone paused and the tap did not, so the system frames that arrived
                // meanwhile have no microphone frames under them — an interval spanning this reads
                // them as a stream running fast. The same answer as an outage on the other side:
                // abandon the interval, and put back a correction the window may already have
                // produced (the input is running again by now, so a buffer can have landed).
                session.drift?.reanchor(
                    micFrames: session.totalFrames, atSec: Self.nowSec, outageSec: lostSec
                )
            }
            log(.warn, "rec.recorder.restarted", ["recordingId": session.recordingId, "reason": reason])
        } catch {
            report(RecorderError("the engine did not come back after \(reason)", underlying: error))
        }
    }

    /// Hands [work] to the control queue and waits for it, so an `async` caller queues behind
    /// whatever the notifications already asked for instead of racing it.
    private func onControl<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            control.async { continuation.resume(with: Result { try work() }) }
        }
    }

    // MARK: - Meta and teardown

    /// Gives back the microphone and leaves no half-open recording behind.
    private func abandon(recordingId: String, directory: URL, cause: Error) async {
        lock.withLock {
            session?.writers.forEach { $0.release() }
            session = nil
        }
        _ = try? await onControl {
            self.stopSystemInput()
            self.input.stop()
        }
        // Drops the row and the directory together.
        try? await core.recordings.delete(recordingId: recordingId)
        try? FileManager.default.removeItem(at: directory)
        log(.error, "rec.recorder.startAborted", ["recordingId": recordingId, "error": "\(cause)"])
    }

    private func meta(
        recordingId: String,
        startedAt: KotlinInstant,
        workflowId: String?,
        title: String?,
        mode: RecordingMode,
        context: Context?
    ) -> RecordingMeta {
        RecordingMeta(
            schema: 1,
            recordingId: recordingId,
            source: source,
            platform: core.deps.device.platform,
            deviceId: core.deps.device.deviceId,
            deviceName: core.deps.device.name,
            workflowId: workflowId,
            title: title,
            startedAt: startedAt.isoUtc,
            endedAt: nil,
            durationSec: nil,
            timezone: TimeZone.current.identifier,
            audio: AudioSettings(
                codec: Codec.aacLc,
                container: Container.m4A,
                sampleRateHz: Int32(Self.sampleRateHz),
                channels: 1,
                bitrateKbps: Int32(Self.bitrateKbps),
                segmentSec: Int32(segmentSec)
            ),
            tracks: Self.tracks(of: mode),
            parts: [],
            gaps: [],
            silenced: [],
            context: context,
            status: RecordingStatus.recording
        )
    }

    /// Never from inside the lock's critical section as a synchronous call: the shell's handler is
    /// free to answer a fatal error by calling `stop`, which wants the same lock.
    private func report(_ error: RecorderError) {
        log(error.fatal ? .error : .warn, "rec.recorder.error", ["fatal": error.fatal, "message": error.description])
        let handler = onError
        DispatchQueue.main.async { handler(error) }
    }

    private func log(_ level: LoggerLevel, _ event: String, _ fields: [String: Any]) {
        core.deps.logger.log(level: level, event: event, fields: fields, error: nil)
    }
}
