import Foundation
import os
import ReclyCore
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// docs/08 "Result files", deliverable 3: what the `transcribe` step wrote for one recording. The local
/// copy if the step ran on this device, and Drive's if it ran on another — `core.results` decides
/// which, and keeps what it downloads.
///
/// Shared by both Apple shells, because "the same features on the phone and on macOS" is easier to
/// keep true than to re-check (the workflow editor is shared for the same reason).
@MainActor
public final class RecordingDetailModel: ObservableObject, Identifiable {
    @Published public private(set) var loading = true
    @Published public private(set) var transcript: Transcript?
    @Published public private(set) var document: TranscriptDocument?
    @Published public private(set) var availability: TranscriptAvailability = .pending
    /// docs/08 "Result files": the audio beside the transcript, when this device still has it.
    @Published public private(set) var audio = RecordingPlaylist.Selection.empty
    /// docs/09 screen principle 2: the shape of [audio], one peak per 0.25 s window, for the bar to draw a
    /// playhead across. Filled at the very end of [load] — after the trip to Drive, which is what
    /// settles which parts there are to draw — and left empty by a decode that failed. The bar
    /// shows the waveform loader until then ([waveformPending]), and a recording is never held up
    /// by its picture: playback does not need it.
    @Published public private(set) var waveform: [Float] = []
    /// True from the moment there is audio to draw until its peaks are settled — read from the
    /// saved ones, decoded, or given up on. The bar shows the waveform loader meanwhile, never the
    /// flat baseline, which would read as a silent recording.
    @Published public private(set) var waveformPending = true
    /// A take still being written to has nothing whole to play yet, so the detail offers nothing.
    @Published public private(set) var writing = false
    /// Whether *any* recording on this device is being written right now — which is not the same
    /// question as [writing], and is the one that decides whether Play may be offered at all. See
    /// [RecordingPlayer]: the recorder owns the audio session while it runs.
    @Published public private(set) var deviceRecording = false
    /// docs/03 ADR-017: how the trip to Drive for the parts the retention sweep took is going.
    @Published public private(set) var driveFetch = DriveFetch.deciding
    /// How much of that trip is done, 0 to 1, by the bytes of the parts it brings back.
    @Published public private(set) var fetchProgress: Double = 0
    /// docs/09 "Highlights": the moments marked on this recording, ascending — ticks on the waveform, squares
    /// in the text.
    @Published public private(set) var highlights: [Double] = []
    /// docs/09 "Transcript reader": the paragraphs of [transcript], with the segments each is made of.
    @Published public private(set) var groups: [TranscriptGroup] = []
    /// A job of this recording has not settled: the transcript may be rewritten under an edit, so
    /// editing and a second transcription wait (docs/08 "Editing").
    @Published public private(set) var transcriptionBusy = false
    /// docs/10 "Re-transcription": one is running — its line says where.
    @Published public private(set) var retranscribing: Retranscribing?
    /// docs/09 "Playback": the stretches Skip silence jumps, from the waveform's peaks.
    @Published public private(set) var silences: [SilentRange] = []
    /// docs/10 "Search": the find the page was opened with, or that ⌘F started; nil when there is none.
    @Published public var find: TranscriptFind?
    /// An edit — of the transcript or of the summary — on its way to the core: `Saving…`, then ✓ for a
    /// moment.
    @Published public private(set) var saving: ProcessingState = .idle
    /// The current processing settings' transcription mode, for the More menu's reasons.
    @Published public private(set) var transcriptionOff = false
    /// Whether the recording is in its storage — uploaded from here, or another device's row — which a
    /// second transcription reads it from (docs/10 "Re-transcription").
    @Published public private(set) var uploaded = true
    /// Why the last `Transcribe again` did not start — its key — for a moment.
    @Published public private(set) var retranscribeRefusal: String?
    /// 2026-10-08 §7: a job of this recording is held up only because Drive is not connected — what
    /// the page says instead of sending the user back to the list, and the More menu's reason.
    @Published public private(set) var waitingForDrive = false
    /// docs/03: the recording's whole length as its meta has it — what every time on this page is
    /// shaped by (2026-10-08 §3). Nil until it is read, and for a recording still being written.
    @Published public private(set) var metaLengthSec: Double?
    /// docs/08 "Summaries": this recording's meeting notes as the core has them — none, being written,
    /// written, or a failure with the last one kept.
    @Published public private(set) var summary: SummaryState = SummaryState.None.shared
    /// docs/15 §10: the ChatGPT sign-in, for the More menu's reason and the name of a summary's model.
    @Published public private(set) var chatGpt: ChatGptConnection = ChatGptConnection.SignedOut.shared
    /// A Summarize this page asked for has not answered yet.
    @Published public private(set) var summarizing = false
    /// docs/15 "iPhone providers": the destination a summary — or a question — waits on the user's permission for.
    @Published public private(set) var summaryConsent: [TransferTarget] = []
    /// docs/08 "Summaries": the formats `Summarize as…` offers — Settings' list, My format once it has words.
    @Published public private(set) var summaryFormats: [SummaryFormat] = [.auto, .oneOnOne, .lecture, .interview]
    /// docs/08 "Ask": the one question about this recording — none, being answered, answered or failed. Kept by
    /// the core in this process only.
    @Published public private(set) var ask: AskState = AskState.None.shared
    /// docs/08 "Ask": the presets that make sense for this recording, read when the panel opens.
    @Published public private(set) var askPresets: [AskPreset] = []
    /// docs/10 "Search": the page opens on the summary — a search hit whose only match is in it.
    public var opensOnSummary = false

    public enum Retranscribing: Sendable { case external, local }

    /// What the player bar has to say while the parts are on their way back, and after.
    public enum DriveFetch: Equatable, Sendable {
        /// Whether there is a trip to make is not known yet — asking Drive whether it holds the
        /// recording is itself a round trip. The bar keeps its clock and offers no Play until this
        /// is over: what Play would start is not settled while it lasts.
        case deciding
        /// Nothing to fetch, or the fetch is over: what the bar shows is what there is.
        case idle
        case fetching
        case failed
    }

    public var playlist: [URL] { audio.urls }
    public var totalSec: Double { audio.totalSec }
    public var hasAudio: Bool { !audio.isEmpty }

    /// 2026-10-08 §3: the length that picks the shape of every time on this page — `MM:SS` or
    /// `HH:MM:SS` — so they are all one width: the meta's, else the audio's here, else none (and a
    /// time is then shaped by itself).
    public var lengthSec: Double? {
        if let metaLengthSec, metaLengthSec > 0 { return metaLengthSec }
        return totalSec > 0 ? totalSec : nil
    }

    /// One moment of this recording, as this page draws it.
    public func stamp(_ sec: Double) -> String {
        LedgerFormat.stamp(Int(max(0, sec)), total: lengthSec)
    }

    public let recordingId: String
    public let playbackGate: RecordingPlaybackGate?
    /// The name in the header — the row's when the page opened, and whatever a rename made it
    /// after. Published because the rename is answered here rather than by reopening the page: the
    /// ledger behind it catches up on its own through `observeRecordings`.
    @Published public private(set) var title: String
    /// The user's own title, empty where they never gave one — what the rename prompt starts from,
    /// as against the [title] the header shows, which is the ledger's word for a recording with no
    /// name of its own. Read from the record by [load], so it is the recording's own answer rather
    /// than the row's.
    @Published public private(set) var givenTitle = ""

    /// The recording it is about: a sheet presented `item:`-style needs one, and there is never a
    /// second detail open on the same recording.
    public nonisolated var id: String { recordingId }

    private let core: ReclyCore_
    private let logger = Logger(subsystem: CoreBridge.appName, category: "detail")
    /// What `meta.json` says the played track is made of, and the directory its files live in —
    /// kept from the load so the Drive fallback can tell a gap from a whole recording, and can put
    /// the durations back on the clock for the parts it fetches.
    private var playedParts: [Part_] = []
    private var directory: URL?
    private var audioRecord: RecordingRecord?

    /// docs/03 "Storage location": the audio comes back from the app's iCloud folder rather than from Drive.
    public var icloud: Bool { audioRecord?.storage == .icloud }
    /// docs/03 "Storage location": the audio comes back from the local folder picked on this device.
    public var folder: Bool { audioRecord?.storage == .folder }
    /// docs/08 "Summaries": an edited summary stays on this device — the recording's storage is the local
    /// folder, or it is in no storage yet.
    public var summaryStaysHere: Bool { Self.summaryStaysHere(storage: audioRecord?.storage) }

    static func summaryStaysHere(storage: StorageKind?) -> Bool { storage == nil || storage == .folder }

    public init(core: ReclyCore_, recordingId: String, title: String, playbackGate: RecordingPlaybackGate? = nil) {
        self.core = core
        self.recordingId = recordingId
        self.title = title
        self.playbackGate = playbackGate
        chatGpt = core.chatGpt.observe().value
    }

    /// Called again whenever the view is handed a different model, so it starts from `loading`
    /// every time rather than from whatever the last call left behind.
    public func load() async {
        loading = true
        driveFetch = .deciding
        waveformPending = true
        do {
            await reloadResults(repair: false)
            audio = localAudio(record: try await core.recordings.get(id: recordingId))
            deviceRecording = try await somethingIsBeingRecorded()
        } catch {
            // A cancelled load is one the view has already replaced; the model it was for is not
            // on screen any more, and reporting it as a finished empty load would be a lie.
            guard !Task.isCancelled else { return }
            logger.error("detail.failed error=\(String(describing: error), privacy: .private)")
        }
        // Out of `loading` before the fetch, because the player bar is where the fetch is said —
        // and the bar stays on `.deciding` until [fetchFromDrive] has decided, so the seconds it
        // spends asking Drive are not seconds in which Play is offered.
        loading = false
        await finishAudioLoad()
    }

    private func finishAudioLoad() async {
        waveformPending = true
        await fetchFromDrive()
        guard !writing, !Task.isCancelled else {
            waveform = []
            waveformPending = false
            return
        }
        // docs/09 screen principle 2: the picture last, and inside the load rather than beside it. Last
        // because the trip to Drive is what settles which parts there are, and a decode of the
        // local prefix would be a picture of a different recording than the one that plays. Inside
        // because the `.task` that runs this load is also what cancels it: the Mac swaps the model
        // behind one view, and reading a whole recording for a bar nobody is looking at any more is
        // work the next pick would be waiting behind.
        //
        // The saved peaks first (`WaveformPeaks`): when they cover exactly this audio, there is
        // nothing to decode and the bar is drawn at once.
        if let saved = RecordingWaveform.cached(
            (try? await core.recordings.waveform(recordingId: recordingId))?.map(\.floatValue),
            for: audio
        ) {
            guard !Task.isCancelled else { return }
            waveform = saved
            waveformPending = false
            silences = Self.silences(saved)
            return
        }
        waveform = []
        let peaks = try? await RecordingWaveform.peaks(for: audio)
        guard !Task.isCancelled else { return }
        waveform = peaks ?? []
        waveformPending = false
        silences = Self.silences(waveform)
        // Kept for the next open, beside the parts: nothing decodes this recording again.
        if let peaks, !peaks.isEmpty {
            try? await core.recordings.saveWaveform(recordingId: recordingId, peaks: peaks.map { KotlinFloat(float: $0) })
        }
    }

    public func reloadResults(repair: Bool = true) async {
        do {
            let result = try await (repair
                ? core.retryResults(recordingId: recordingId)
                : core.results(recordingId: recordingId))
            guard !Task.isCancelled else { return }
            adopt(result.transcript)
            availability = result.availability
        } catch {
            guard !Task.isCancelled else { return }
            availability = .unavailable
        }
    }

    public func followResults() async {
        for await result in core.observeResults(recordingId: recordingId) {
            guard !Task.isCancelled else { return }
            adopt(result.transcript)
            availability = result.availability
        }
    }

    private func adopt(_ next: Transcript?) {
        guard transcript != next else { return }
        transcript = next
        document = next.map { TranscriptDocument(transcript: $0) }
        groups = next.map(TranscriptGroup.make) ?? []
    }

    /// Recording state is independent of result changes and slow audio downloads.
    public func followCapture() async {
        for await _ in core.recordings.observe() {
            guard !Task.isCancelled else { return }
            deviceRecording = (try? await somethingIsBeingRecorded()) ?? deviceRecording
            if let record = try? await core.recordings.get(id: recordingId) {
                highlights = record.meta.highlights.map(\.atSec)
            }
        }
    }

    public func followAudio() async {
        for await record in core.recordings.observeAudio(recordingId: recordingId) {
            guard !Task.isCancelled else { return }
            if record == audioRecord { continue }
            driveFetch = .deciding
            audio = localAudio(record: record)
            await finishAudioLoad()
        }
    }

    public var transcriptMessage: String {
        // 2026-10-08 §7: waiting for Drive is said here with its fix under it, rather than as "check
        // the list".
        if waitingForDrive, availability == .parked || availability == .failed || availability == .pending {
            return RecKitStrings.localized("Waiting for Drive. Connect Drive to upload and transcribe.")
        }
        let key: String
        switch availability {
        case .notRequested: key = "This recording has no transcription step to run."
        case .failed: key = "Transcription could not finish. Check this recording in the list for the next action."
        case .parked: key = "Transcription is waiting. Check this recording in the list for what it needs."
        case .unavailable: key = "Could not load the transcript. Try again."
        case .empty: key = "Transcription finished with no text. Play the recording to check the audio."
        default: key = "Transcription is not finished yet. The result will appear here when ready."
        }
        return RecKitStrings.localized(key)
    }

    /// docs/03: the name the user gave this recording, changed from the page it names. The core
    /// writes it here and pushes it to Drive, and a recording another device made is renamed the
    /// same way — the row this page opened from may be one of those.
    ///
    /// The header answers at once rather than waiting for the ledger's own `observeRecordings` to
    /// come round, and an empty answer clears the title back to what a recording with none is
    /// called (`RecentItem.titleLabel`). A rename the core refused — no such recording, or one
    /// still being written — leaves the header saying what it said.
    public func rename(to newTitle: String?) async {
        let typed = newTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        do {
            let renamed = try await core.rename(recordingId: recordingId, title: typed.isEmpty ? nil : typed)
            guard renamed.boolValue else { return }
            givenTitle = typed
            title = typed.isEmpty ? RecKitStrings.localized("Untitled") : typed
        } catch {
            logger.error("detail.rename.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    // MARK: - Highlights, Share, editing and Transcribe again (docs/09 "Detail header and More menu")

    /// The shell's executor, poked when this page queues a job (a re-transcription) so it runs now.
    public var jobsDue: (() -> Void)?

    /// 2026-10-08 §7: the shell's own Connect Drive (the settings' and the banner's), for a recording
    /// waiting for Drive. Nil where the shell offers none, and then the page shows no button.
    public var connectDrive: (() -> Void)?

    /// docs/09 "Highlights": a mark at [sec] — the More menu's, or the desktop's Highlight button.
    public func addHighlight(atSec sec: Double) async {
        await setHighlights(highlights + [sec])
    }

    public func removeHighlight(_ sec: Double) async {
        await setHighlights(highlights.filter { $0 != sec })
    }

    /// Saved at once, here and in the recording's folder; the list as the core kept it comes back.
    private func setHighlights(_ next: [Double]) async {
        guard (try? await core.setHighlights(recordingId: recordingId, atSecs: next.map { KotlinDouble(double: $0) }))?.boolValue == true
        else { return }
        if let record = try? await core.recordings.get(id: recordingId) {
            highlights = record.meta.highlights.map(\.atSec)
        }
    }

    /// docs/08 "Exports": the file for the share sheet, or nil when there is nothing in that format.
    public func export(_ format: ShareFormat) async -> URL? {
        do {
            guard let path = try await core.exportFile(recordingId: recordingId, format: format.export) else { return nil }
            return URL(fileURLWithPath: path)
        } catch {
            logger.error("detail.export.failed error=\(String(describing: error), privacy: .private)")
            return nil
        }
    }

    /// No audio here and none to fetch — the same answer the player bar's `No audio on this device` is.
    public var audioUnavailable: Bool { !hasAudio && driveFetch == .idle }

    /// `Copy all`: the text with its times, as the `.txt` has it.
    public func copyAll() {
        guard let text = document?.plainText else { return }
        Self.copy(text)
    }

    private static func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }

    /// Why `Edit transcript` cannot run now, or nil when it can (docs/09 "Detail header and More menu").
    public var editReason: String? {
        if transcript == nil { return RecKitStrings.localized("No transcript yet") }
        if transcriptionBusy { return busyReason }
        return nil
    }

    /// Why `Transcribe again` cannot run now, or nil when it can (docs/09 "Detail header and More menu").
    public var retranscribeReason: String? {
        if transcriptionOff { return RecKitStrings.localized("Transcription is off in Settings") }
        if transcriptionBusy { return busyReason }
        if !uploaded { return RecKitStrings.localized(waitingForDrive ? "Waiting for Drive" : "Not uploaded yet") }
        return nil
    }

    /// 2026-10-08 §7: what a job of this recording that has not settled is doing — the same items stay
    /// off while it lasts (docs/08 "Editing"), only the words are what it really is. The transcription
    /// comes after the upload in every plan, so a job past the upload is transcribing (or queued to);
    /// one before it is waiting for Drive, or simply not uploaded yet.
    var busyReason: String {
        if waitingForDrive { return RecKitStrings.localized("Waiting for Drive") }
        if !uploaded { return RecKitStrings.localized("Not uploaded yet") }
        return RecKitStrings.localized("Transcribing…")
    }

    /// Why `Rename` cannot run now, or nil when it can: a take still being written has no name to give
    /// yet (2026-10-08 §12 — a disabled item always says why).
    public var renameReason: String? {
        writing ? RecKitStrings.localized("Still recording") : nil
    }

    // MARK: - Summary (docs/08 "Summaries" · docs/09 "Summary view")

    /// Summarize is in the More menu wherever ChatGPT is offered (docs/15 "China mainland App Store").
    public var summaryOffered: Bool { !(chatGpt is ChatGptConnection.Unavailable) }

    /// `Summarize`, or `Summarize again` once there is a summary to replace.
    public var summarizeTitle: String {
        RecKitStrings.localized(Self.hasSummary(summary) ? "Summarize again" : "Summarize")
    }

    /// Why `Summarize` and `Summarize as…` cannot run now, or nil when they can (docs/09 "Detail header and More
    /// menu").
    public var summarizeReason: String? {
        Self.summarizeReason(
            writing: writing,
            hasTranscript: transcript != nil && availability != .empty,
            busy: transcriptionBusy ? busyReason : nil,
            connection: chatGpt,
            summary: summary,
            summarizing: summarizing
        )
    }

    /// The reason's rule, apart from the page. A take still being written has no transcript either, so
    /// it says the more useful of the two first.
    static func summarizeReason(
        writing: Bool, hasTranscript: Bool, busy: String?, connection: ChatGptConnection, summary: SummaryState, summarizing: Bool
    ) -> String? {
        if let reason = askReason(writing: writing, hasTranscript: hasTranscript, busy: busy, connection: connection) { return reason }
        if summarizing || summary is SummaryState.Running { return RecKitStrings.localized("Summarizing…") }
        return nil
    }

    /// Why `Ask about this recording…` cannot run now, or nil when it can: Summarize's reasons, but a summary
    /// being written does not stand in a question's way.
    public var askReason: String? {
        Self.askReason(
            writing: writing,
            hasTranscript: transcript != nil && availability != .empty,
            busy: transcriptionBusy ? busyReason : nil,
            connection: chatGpt
        )
    }

    static func askReason(writing: Bool, hasTranscript: Bool, busy: String?, connection: ChatGptConnection) -> String? {
        if writing { return RecKitStrings.localized("Still recording") }
        if !hasTranscript { return RecKitStrings.localized("No transcript yet") }
        if let busy { return busy }
        if connection is ChatGptConnection.SignedOut || connection is ChatGptConnection.Expired {
            return CoreMessages.sentence(.chatgptSignInRequired)
        }
        return nil
    }

    /// The format of the summary the recording has, for the ✓ in `Summarize as…`; nil when it has none.
    public var summaryFormat: SummaryFormat? { savedSummary?.summaryFormat }

    /// docs/08 "Exports": why the share sheet's `Summary` cannot be had, or nil when it can.
    public var summaryExportReason: String? { Self.summaryExportReason(summary) }

    static func summaryExportReason(_ state: SummaryState) -> String? {
        hasSummary(state) ? nil : RecKitStrings.localized("No summary yet")
    }

    static func hasSummary(_ state: SummaryState) -> Bool { savedSummary(state) != nil }

    /// The summary the recording has: the one on screen, or the one kept under a new run or a failure.
    public var savedSummary: Summary? { Self.savedSummary(summary) }

    static func savedSummary(_ state: SummaryState) -> Summary? {
        switch onEnum(of: state) {
        case .none: return nil
        case .ready(let ready): return ready.summary
        case .running(let running): return running.previous
        case .failed(let failed): return failed.previous
        }
    }

    /// `Edit summary` is in the More menu once the recording has a summary (docs/08 "Summaries").
    public var summaryEditable: Bool { Self.hasSummary(summary) }

    /// Why `Edit summary` cannot run now, or nil when it can: not while a new summary is written over it.
    public var editSummaryReason: String? { Self.editSummaryReason(summary: summary, summarizing: summarizing) }

    static func editSummaryReason(summary: SummaryState, summarizing: Bool) -> String? {
        summarizing || summary is SummaryState.Running ? RecKitStrings.localized("Summarizing…") : nil
    }

    /// docs/09 "Summary view": `Summarize again` over a summary the user edited asks first.
    public var summaryEdited: Bool { Self.savedSummary(summary)?.editedAt != nil }

    /// The Transcript | Summary chips stand while there is a summary, or one is on its way.
    public var hasSummaryView: Bool { summarizing || !(summary is SummaryState.None) }

    public func followSummary() async {
        for await state in core.summaries.observe(recordingId: recordingId) {
            guard !Task.isCancelled else { return }
            summary = state
        }
    }

    public func followChatGpt() async {
        for await connection in core.chatGpt.observe() {
            guard !Task.isCancelled else { return }
            chatGpt = connection
        }
    }

    /// Settings' formats, for `Summarize as…`.
    public func followSummaryPreferences() async {
        for await preferences in core.summaries.observePreferences() {
            guard !Task.isCancelled else { return }
            summaryFormats = preferences.formats
        }
    }

    /// The More menu's Summarize — as [format], or nil for Settings' format. The core runs it on a scope of its
    /// own, so it goes on when the page closes; its progress comes back through [followSummary]. On the iPhone
    /// a destination the user has not allowed yet is asked about first ([summaryConsent]).
    public func summarize(format: SummaryFormat? = nil) async {
        summarizing = true
        defer { summarizing = false }
        do {
            let state = try await core.summaries.summarize(recordingId: recordingId, format: format)
            if case .failed(let failed) = onEnum(of: state), ChatGptText.message(failed.reason) == .transferConsentRequired {
                askConsent { [weak self] in await self?.summarize(format: format) }
            }
        } catch {
            logger.error("shell.chatgpt.summarize.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// What the permission dialog goes on to do once it is allowed: the summary, or the question, it stood in
    /// front of.
    private var afterConsent: (() async -> Void)?

    private func askConsent(then next: @escaping () async -> Void) {
        afterConsent = next
        summaryConsent = [TransferTargets.shared.chatGptSummary()]
    }

    /// The answer to [summaryConsent]: allowed, exactly the destination shown is granted and what waited on it
    /// starts; declined, nothing is sent.
    public func answerSummaryConsent(allow: Bool) async {
        let shown = summaryConsent
        let next = afterConsent
        summaryConsent = []
        afterConsent = nil
        guard allow, !shown.isEmpty else { return }
        do {
            try await core.transferConsents.grant(targets: shown)
        } catch {
            logger.error("shell.chatgpt.summarize.failed error=\(String(describing: error), privacy: .private)")
            return
        }
        await next?()
    }

    // MARK: - Ask (docs/08 "Ask" · docs/09 "Ask")

    public func followAsk() async {
        for await state in core.summaries.observeAsk(recordingId: recordingId) {
            guard !Task.isCancelled else { return }
            ask = state
        }
    }

    /// The panel opened: the presets this recording has.
    public func openAsk() async {
        do {
            askPresets = try await core.summaries.askPresets(recordingId: recordingId)
        } catch {
            logger.error("shell.chatgpt.ask.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// The panel closed: the answer goes with it, and a question still running finishes unseen.
    public func closeAsk() {
        core.summaries.clearAsk(recordingId: recordingId)
    }

    /// One preset, or the user's own question — the question wins. Its progress comes back through
    /// [followAsk]; on the iPhone a destination not allowed yet is asked about first, as for a summary.
    public func ask(preset: AskPreset?, question: String?) async {
        do {
            let state = try await core.summaries.ask(recordingId: recordingId, preset: preset, question: question)
            if case .failed(let failed) = onEnum(of: state), ChatGptText.message(failed.reason) == .transferConsentRequired {
                askConsent { [weak self] in await self?.ask(preset: preset, question: question) }
            }
        } catch {
            logger.error("shell.chatgpt.ask.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// The failure notice's Retry: the same preset or question again.
    public func retryAsk() async {
        guard case .failed(let failed) = onEnum(of: ask) else { return }
        await ask(preset: failed.preset, question: failed.question)
    }

    /// The answer as it is, for its Copy all.
    public func copyAnswer() {
        guard case .ready(let ready) = onEnum(of: ask) else { return }
        Self.copy(ready.answer.text)
    }

    /// The text of the summary on screen, with nothing added.
    public func copySummary() {
        guard case .ready(let ready) = onEnum(of: summary) else { return }
        Self.copy(ready.summary.text)
    }

    /// docs/08 "Summaries": the user's own text over the summary — kept here at once, and carried to the
    /// recording's folder by the core. `Saving…` while it goes, ✓ for a moment after; false when it did not
    /// take (a summary was being written over it).
    @discardableResult
    public func editSummary(_ text: String) async -> Bool {
        guard saving != .processing else { return false }
        saving = .processing
        do {
            let state = try await core.summaries.edit(recordingId: recordingId, text: text)
            if state is SummaryState.Ready {
                summary = state
                showSaved()
                return true
            }
        } catch {
            logger.error("shell.chatgpt.summary.edit.failed error=\(String(describing: error), privacy: .private)")
        }
        saving = .failed
        return false
    }

    /// The jobs of this recording, for the menu's reasons and the `Transcribing again…` line.
    public func followJobs() async {
        await refreshSettings()
        for await all in core.jobs.observe() {
            guard !Task.isCancelled else { return }
            // An upload finishing is a job settling, so the answer is asked again with every change.
            uploaded = (try? await core.uploaded(recordingId: recordingId))?.boolValue ?? uploaded
            let mine = all.filter { $0.recordingId == recordingId }
            let running = mine.filter { !Self.settled.contains($0.status) }
            transcriptionBusy = !running.isEmpty
            waitingForDrive = Self.waitsForDrive(mine)
            retranscribing = running.first { $0.retranscription }.map { job in
                job.workflow?.steps.contains { $0 is Step.LocalTranscribe } == true ? .local : .external
            }
        }
    }

    private static let settled: Set<JobStatus> = [.done, .failed, .skippedShort]

    /// Whether the newest job of the recording — the one the list's row reads — waits for a Drive
    /// connection (`NEEDS_AUTH`, 2026-10-08 §7).
    static func waitsForDrive(_ jobs: [ReclyCore.Job]) -> Bool {
        jobs.max(by: { $0.createdAt.toEpochMilliseconds() < $1.createdAt.toEpochMilliseconds() })?.status == .needsAuth
    }

    @discardableResult
    private func refreshSettings() async -> ProcessingTranscription? {
        guard let state = try? await core.initializeProcessing() else { return nil }
        let transcription = state.document.settings.transcription
        transcriptionOff = transcription.mode == .off
        return transcription
    }

    /// docs/10 "Re-transcription": the confirmation's one line, from the settings as they are now —
    /// `With AssemblyAI · Korean.`, and what it costs when the transcript holds the user's own work.
    public func retranscribeLine() async -> String? {
        guard let transcription = await refreshSettings(), transcription.mode != .off else { return nil }
        let method = transcription.mode == .external
            ? SttProviders.shared.displayName(name: transcription.external?.provider ?? "")
            : RecKitStrings.localized("on-device transcription")
        var line = RecKitStrings.localized("With %1$@ · %2$@.", method, SpeechLanguageName.title(transcription.language))
        if transcript?.hasEdits == true { line += " " + RecKitStrings.localized("Your edits are replaced.") }
        return line
    }

    public func retranscribe() async {
        do {
            let result = try await core.retranscribe(recordingId: recordingId)
            logger.info("detail.retranscribe result=\(String(describing: result), privacy: .public)")
            // Never nothing: what stood in the way, in the More menu's own words.
            switch onEnum(of: result) {
            case .started: jobsDue?()
            case .busy: refuse("Transcribing…")
            case .noAudio: refuse("No audio on this device")
            case .noTranscriptionConfigured: refuse("Transcription is off in Settings")
            case .unsupported: refuse("Not uploaded yet")
            }
        } catch {
            logger.error("detail.retranscribe.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// Kept as the key, so the line follows a language change while it stands.
    private func refuse(_ key: String) {
        retranscribeRefusal = key
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            if self?.retranscribeRefusal == key { self?.retranscribeRefusal = nil }
        }
    }

    /// docs/08 "Editing": [edits] saved as one, here at once and in the recording's folder after.
    /// `Saving…` while it goes, ✓ for a moment after; false when the core refused it.
    @discardableResult
    public func edit(_ edits: [any TranscriptEdit]) async -> Bool {
        guard !edits.isEmpty else { return true }
        // A second edit while one is saving would be written over the transcript the first one replaces.
        guard saving != .processing else { return false }
        saving = .processing
        let edit: any TranscriptEdit = edits.count == 1 ? edits[0] : TranscriptEditBatch(edits: edits)
        do {
            if case .edited(let edited) = onEnum(of: try await core.editTranscript(recordingId: recordingId, edit: edit)) {
                adopt(edited.transcript)
                showSaved()
                return true
            }
        } catch {
            logger.error("detail.edit.failed error=\(String(describing: error), privacy: .private)")
        }
        saving = .failed
        return false
    }

    /// ✓ for a moment after a save.
    private func showSaved() {
        saving = .done
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            if self?.saving == .done { self?.saving = .idle }
        }
    }

    /// docs/09 "Editing and speakers": a speaker's name from the speaker menu; empty takes it away and the id
    /// shows again.
    public func renameSpeaker(_ id: String, to name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        await edit([TranscriptEditRenameSpeaker(speakerId: id, name: trimmed.isEmpty ? nil : trimmed)])
    }

    /// docs/09 "Editing and speakers": every segment of a line to speaker [id], or to a new one — `S{n+1}`,
    /// the core's own rule, which the first segment makes and the rest then name.
    public func changeSpeaker(segments: [Int], to id: String?) async {
        guard let first = segments.first, let transcript else { return }
        let target = id ?? "S\((transcript.speakers.compactMap { $0.id.hasPrefix("S") ? Int($0.id.dropFirst()) : nil }.max() ?? 0) + 1)"
        var edits: [any TranscriptEdit] = [TranscriptEditSetSpeaker(segmentIndex: Int32(first), speakerId: id)]
        edits += segments.dropFirst().map { TranscriptEditSetSpeaker(segmentIndex: Int32($0), speakerId: target) }
        await edit(edits)
    }

    /// The silences of a recording's peaks, which Skip silence jumps.
    private static func silences(_ peaks: [Float]) -> [SilentRange] {
        guard !peaks.isEmpty else { return [] }
        return SilenceRanges.shared.compute(peaks: peaks.map { KotlinFloat(float: $0) }, windowSec: WaveformPeaks.shared.WINDOW_SEC)
    }

    /// The parts of this recording that are still on this device.
    private func localAudio(record: RecordingRecord?) -> RecordingPlaylist.Selection {
        audioRecord = record
        guard let record else {
            writing = false
            metaLengthSec = nil
            givenTitle = ""
            playedParts = []
            directory = nil
            return .empty
        }
        writing = record.meta.status == RecordingStatus.recording
        metaLengthSec = record.meta.durationSec?.doubleValue
        givenTitle = record.meta.title ?? ""
        highlights = record.meta.highlights.map(\.atSec)
        let track = RecordingPlaylist.playedTrack(tracks: record.meta.tracks)
        playedParts = record.meta.parts.filter { $0.track == track }
        directory = record.dir.url
        return RecordingPlaylist.select(
            tracks: record.meta.tracks,
            parts: record.meta.parts,
            dir: record.dir.url,
            exists: { FileManager.default.fileExists(atPath: $0.path) }
        )
    }

    /// docs/03 ADR-017: the parts the retention sweep took, fetched back from Drive so the page can
    /// play the recording it is about. A take still being written to is left alone — it has nothing
    /// whole to play yet, and nothing of it has reached Drive either.
    ///
    /// A failure is a sentence in the player bar and nothing more. What a missing token needs is a
    /// sign-in, and both shells already carry one — a dialog from here would be a second way to say
    /// what is already on screen.
    private func fetchFromDrive() async {
        guard let directory, !writing else {
            driveFetch = .idle
            return
        }
        guard let uploaded = try? await core.uploaded(recordingId: recordingId),
              RecordingPlaylist.fetchesFromDrive(
                  local: audio.urls.count,
                  track: playedParts.count,
                  uploaded: uploaded.boolValue
              )
        else {
            driveFetch = .idle
            return
        }
        driveFetch = .fetching
        fetchProgress = 0
        do {
            let fetched = try await core.audio(recordingId: recordingId, progress: DriveFetchProgress { [weak self] fraction in
                Task { @MainActor in self?.fetchProgress = fraction }
            })
            audio = RecordingPlaylist.fetched(
                parts: playedParts,
                files: fetched.paths.map(\.name),
                dir: directory
            )
            // A part that stayed missing is a gap the playlist stops at, so the trip did not bring
            // the recording back whole — which is the same sentence as a trip that failed outright.
            driveFetch = fetched.missing.isEmpty ? .idle : .failed
        } catch {
            guard !Task.isCancelled else { return }
            logger.error("detail.audio.failed error=\(String(describing: error), privacy: .private)")
            driveFetch = .failed
        }
    }

    /// The recorder writes one recording at a time, and the one it is writing is the newest — so
    /// one page of the ledger is more than enough to find it.
    private func somethingIsBeingRecorded() async throws -> Bool {
        try await core.recordings.list(limit: Recents.page)
            .contains { !$0.remote && $0.meta.deviceId == core.deps.device.deviceId && $0.meta.status == RecordingStatus.recording }
    }
}

/// docs/09 screen principle 2 · "Spacing": the waveform row's own rhythm. A 2pt bar on a 1pt gap, so how many
/// bars there are is however many 3pt columns the row is wide — the shape is the recording's, and
/// the number of bars is the screen's.
private enum Waveform {
    static let bar: CGFloat = 2
    static let step: CGFloat = 3
    /// A bin with no sound in it, so that silence is still part of the timeline.
    static let minBar: CGFloat = 1
    /// docs/09 Accessibility: what one step of the adjustable action moves, for a scrub with no finger.
    static let stepSec: Double = 5
}

/// The core's count of bytes back from Drive, as a fraction. It is called off the main thread.
private final class DriveFetchProgress: NSObject, AudioFetchProgress {
    private let update: @Sendable (Double) -> Void

    init(_ update: @escaping @Sendable (Double) -> Void) { self.update = update }

    func onProgress(doneBytes: Int64, totalBytes: Int64) {
        update(totalBytes > 0 ? Double(doneBytes) / Double(totalBytes) : 0)
    }
}

/// docs/09 screen principle 2: the detail is a page behind a ledger row rather than a pane in front of it,
/// so the header carries the way back — docs/08's result file, the transcript as the speaker turns
/// it is made of.
#if os(iOS) || os(macOS)
public struct RecordingDetailView: View {
    private struct ResultObservationID: Hashable {
        let model: ObjectIdentifier
        let loading: Bool
    }

    @ObservedObject private var model: RecordingDetailModel
    /// One player for the surface rather than for the model: the Mac keeps a single detail view in
    /// its split pane and swaps the model behind it, and picking another recording has to stop the
    /// one that is playing.
    @StateObject private var player: RecordingPlayer
    /// Where the finger is while it is on the waveform, and `nil` the rest of the time. The
    /// playhead and the clock follow it rather than the player: the seek happens when the drag
    /// ends, and a bar that only moved then would not be a scrub.
    @State private var scrubSec: Double?
    /// Whether the rename prompt is up. Here rather than in the model: a question cancelled is one
    /// the recording never heard, and the model is what the page has already answered.
    @State private var renaming = false
    /// Whether this recording's audio came back from Drive while the page was open: its bars then
    /// grow in once, where the loader stood, starting at [growStart].
    @State private var fetched = false
    @State private var growStart: Date?
    /// docs/09 "Share / export" (phones): the Share sheet is up.
    @State private var sharing = false
    /// docs/09 "Editing and speakers": the editor's draft while the page is in edit mode, nil while it is
    /// reading.
    @State private var draft: TranscriptDraft?
    /// docs/09 "Summary view": the summary editor's text while the page edits the summary, nil while it does
    /// not.
    @State private var summaryDraft: SummaryDraft?
    /// `Discard your changes?` is up, and for which editor.
    @State private var discarding: Discarding?
    /// `Replace your edited summary?` is up, for the summary it would start — Settings' format (nil) or the
    /// one picked under `Summarize as…`.
    @State private var replacing: Replacement?
    /// docs/10 "Re-transcription": the confirmation's line while it is up.
    @State private var retranscribeLine: String?
    /// The speaker whose name the dialog is asking for, in reading mode.
    @State private var renamingSpeaker: String?
    @State private var speakerName = ""
    /// docs/09 "Summary view": which of the two the page shows. It opens on the transcript.
    @State private var showingSummary = false
    /// The permission dialog's "I turned off …" answer (iPhone).
    @State private var trainingOff = false
    /// The format the last Summarize on this page asked for, which the failure notice's Retry asks for again.
    @State private var lastFormat: SummaryFormat?
    /// docs/09 "Ask": the panel is up, and the question typed in it.
    @State private var asking = false
    @State private var question = ""
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: every string on this screen is resolved outside SwiftUI, so reading the
    /// locale is what declares the dependency that redraws it in the new language.
    @Environment(\.locale) private var locale
    /// nil where the page is the whole surface it is in — the Mac's window has nothing to go back
    /// to, and a sheet on the phone does.
    private let onClose: (() -> Void)?

    public init(model: RecordingDetailModel, onClose: (() -> Void)? = nil) {
        self.model = model
        _player = StateObject(wrappedValue: RecordingPlayer(gate: model.playbackGate))
        self.onClose = onClose
    }

    public var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: headerTitle, trailingAlignment: .trailing, oneRow: Self.oneRowHeader) {
                HStack(spacing: Space.xs) {
                    if let draft {
                        // docs/09 "Editing and speakers": the desktop's Cancel · Save are in the header; the
                        // phone's sit under the fields, above the keyboard.
                        #if !os(iOS)
                        EditorButtons(changed: draft.changed, saving: model.saving == .processing, cancel: leaveEditor, save: saveDraft)
                        #endif
                    } else if summaryDraft != nil {
                        // docs/09 "Summary view": the summary's editor puts them where the transcript's does.
                        #if !os(iOS)
                        EditorButtons(changed: summaryDraft?.changed == true, saving: model.saving == .processing, cancel: leaveSummaryEditor, save: saveSummary)
                        #endif
                    } else {
                        #if os(iOS)
                        // docs/09 "Share / export": Share opens the sheet of formats. The Mac's is in its
                        // window toolbar.
                        if !model.loading {
                            Button { sharing = true } label: { HeaderIcon(systemName: "square.and.arrow.up") }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text(verbatim: loc("Share")))
                                .accessibilityIdentifier("detail-share")
                        }
                        #endif
                        // docs/09 "Detail header and More menu": Rename, Edit transcript, Transcribe again
                        // and Add highlight.
                        if !model.loading {
                            DetailMoreMenu(
                                model: model,
                                positionSec: positionSec,
                                rename: { renaming = true },
                                edit: {
                                    if let transcript = model.transcript {
                                        showingSummary = false
                                        draft = TranscriptDraft(transcript)
                                    }
                                },
                                transcribeAgain: { Task { retranscribeLine = await model.retranscribeLine() } },
                                summarize: summarizeAgain,
                                editSummary: {
                                    if let summary = model.savedSummary {
                                        showingSummary = true
                                        summaryDraft = SummaryDraft(summary)
                                    }
                                },
                                askAbout: openAsk,
                                addHighlight: { Task { await model.addHighlight(atSec: positionSec) } }
                            )
                        }
                        if let onClose {
                            // Leaving the page stops what it was playing: the sheet is gone but this view
                            // is not torn down synchronously with it. 2026-10-08 §9: an icon in the row
                            // Share and More are in, as quiet as they are, rather than a bordered word.
                            Button {
                                player.stop()
                                onClose()
                            } label: { HeaderIcon(systemName: "xmark") }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Text(verbatim: loc("Close")))
                                .accessibilityIdentifier("detail-close")
                        }
                    }
                }
            }
            HairLine()
            #if !os(iOS)
            if !model.loading, !model.writing {
                playerBar
                HairLine()
            }
            #endif
            // docs/09 "Transcript reader": a transcription of this recording again, while the old text stays
            // readable.
            if let again = model.retranscribing, !editing {
                LoadingText(
                    text: loc(again == .local ? "Transcribing on this device" : "Transcribing again…"),
                    font: blueprint.fonts.sans(TypeSize.small),
                    color: blueprint.palette.textMuted
                )
                .padding(.horizontal, Space.m)
                .padding(.top, Space.s)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("retranscribing")
            } else if model.saving == .processing, !editing {
                LoadingText(text: loc("Saving…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if model.saving == .done, !editing {
                Text(verbatim: BlueprintChip.selectionMark)
                    .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                    .foregroundStyle(blueprint.palette.success)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if let refusal = model.retranscribeRefusal, !editing {
                // docs/10 "Re-transcription": a Transcribe again that did not start says why.
                Text(verbatim: loc(refusal))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("retranscribe-refusal")
            }
            // docs/09 "Summary view": Transcript | Summary, once there is a summary or one was asked for —
            // never over the transcript's editor; over the summary's, Transcript asks before it drops changes.
            if !model.loading, draft == nil, model.hasSummaryView || showingSummary {
                HStack(spacing: Space.s) {
                    BlueprintChip(loc("Transcript"), selected: !showingSummary, action: showTranscript)
                        .accessibilityIdentifier("detail-transcript-tab")
                    BlueprintChip(loc("Summary"), selected: showingSummary) { showingSummary = true }
                        .accessibilityIdentifier("detail-summary-tab")
                }
                .padding(.horizontal, Space.m)
                .padding(.top, Space.s)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if model.loading {
                notice(loc("Loading…"))
            } else if draft == nil, showingSummary, summaryDraft != nil {
                SummaryEditor(
                    text: Binding(get: { summaryDraft?.text ?? "" }, set: { summaryDraft?.text = $0 }),
                    changed: summaryDraft?.changed == true,
                    staysHere: model.summaryStaysHere,
                    saving: model.saving == .processing,
                    cancel: leaveSummaryEditor,
                    save: saveSummary
                )
            } else if draft == nil, showingSummary {
                SummaryPane(model: model, retry: { summarize(lastFormat) }, seekableSec: model.totalSec, onSeek: citationSeek)
            } else if let draft {
                TranscriptEditor(
                    draft: draft,
                    lengthSec: model.lengthSec,
                    canSeek: canSeek,
                    drive: !model.icloud && !model.folder,
                    saving: model.saving == .processing,
                    onSeek: { seek(toSec: $0) },
                    cancel: leaveEditor,
                    save: saveDraft
                )
            } else if model.transcript == nil || model.availability == .empty {
                VStack(spacing: Space.s) {
                    Text(verbatim: model.transcriptMessage)
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("transcript-message")
                    if model.availability == .unavailable {
                        BlueprintButton(loc("Retry")) { Task { await model.reloadResults() } }
                    } else if model.transcript == nil, model.waitingForDrive, let connect = model.connectDrive {
                        // 2026-10-08 §7 · docs/09 screen principle 8: the one way out, where the user is.
                        BlueprintButton(loc("Connect Drive"), action: connect)
                            .accessibilityIdentifier("detail-connect-drive")
                    }
                }
                .padding(Space.l)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let transcript = model.transcript {
                TranscriptReader(
                    transcript: transcript,
                    groups: model.groups,
                    seekableDurationSec: model.totalSec,
                    lengthSec: model.lengthSec,
                    canSeek: canSeek,
                    positionSec: player.positionSec,
                    playing: player.isPlaying,
                    highlights: model.highlights,
                    find: model.find,
                    // One edit at a time: while one is saving, the speaker menus wait for it.
                    speakerActions: model.transcriptionBusy || model.saving == .processing ? nil : TranscriptReader.SpeakerActions(
                        rename: { id in
                            speakerName = transcript.speakers.first { $0.id == id }?.name ?? ""
                            renamingSpeaker = id
                        },
                        change: { segments, id in Task { await model.changeSpeaker(segments: segments, to: id) } }
                    ),
                    onSeek: { seek(toSec: $0) },
                    onRemoveHighlight: { mark in Task { await model.removeHighlight(mark) } },
                    onCloseFind: { model.find = nil }
                )
                .id(model.recordingId)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(iOS)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !model.loading, !model.writing {
                VStack(spacing: 0) { HairLine(); playerBar }
            }
        }
        #endif
        .dotGridBackground()
        // Drawn in the page rather than presented over it, because the page itself is already a
        // sheet on the phone and a view may host only one (see `blueprintDialogOverlay`). The Mac
        // shows the same dialog in its window, so the question looks the same on both.
        .blueprintDialogOverlay(isPresented: $renaming) {
            RenameDialog(title: model.givenTitle) { typed in
                renaming = false
                Task { await model.rename(to: typed) }
            } cancel: {
                renaming = false
            }
        }
        // docs/10 "Re-transcription": what it will be done with, and what it replaces.
        .blueprintDialogOverlay(isPresented: Binding(get: { retranscribeLine != nil }, set: { if !$0 { retranscribeLine = nil } })) {
            BlueprintDialog(title: loc("Transcribe again?")) {
                BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) { retranscribeLine = nil }
                BlueprintButton(loc("Transcribe"), tone: .primary) {
                    retranscribeLine = nil
                    Task { await model.retranscribe() }
                }
                .accessibilityIdentifier("retranscribe-confirm")
            } content: {
                BlueprintDialogText(retranscribeLine ?? "")
            }
        }
        // docs/09 "Editing and speakers" · "Summary view": leaving an editor with changes in it.
        .blueprintDialogOverlay(isPresented: Binding(get: { discarding != nil }, set: { if !$0 { discarding = nil } })) {
            BlueprintDialog(title: loc("Discard your changes?")) {
                BlueprintButton(loc("Keep editing"), tone: .quiet) { discarding = nil }
                BlueprintButton(loc("Discard"), tone: .accent, action: discard)
                    .accessibilityIdentifier("discard-edits")
            } content: {
                BlueprintDialogText(loc(discarding == .transcript
                    ? "Your edits to this transcript will be lost."
                    : "Your edits to this summary will be lost."))
            }
        }
        // docs/09 "Summary view": a new summary would replace the user's own words. Not red: no recording
        // is deleted.
        .blueprintDialogOverlay(isPresented: Binding(get: { replacing != nil }, set: { if !$0 { replacing = nil } })) {
            BlueprintDialog(title: loc("Replace your edited summary?")) {
                BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) { replacing = nil }
                BlueprintButton(loc("Replace"), tone: .primary) {
                    let format = replacing?.format
                    replacing = nil
                    summarize(format)
                }
                .accessibilityIdentifier("summary-replace-confirm")
            } content: {
                BlueprintDialogText(loc("Summarizing again replaces the summary you edited."))
            }
        }
        .blueprintDialogOverlay(isPresented: Binding(get: { renamingSpeaker != nil }, set: { if !$0 { renamingSpeaker = nil } })) {
            SpeakerNameDialog(name: $speakerName) {
                if let id = renamingSpeaker {
                    let name = speakerName
                    Task { await model.renameSpeaker(id, to: name) }
                }
                renamingSpeaker = nil
            } cancel: {
                renamingSpeaker = nil
            }
        }
        #if os(iOS)
        // docs/15 "iPhone providers": the transcript goes to OpenAI only once the user has allowed it — asked
        // here, or in the Ask sheet when the question came from there.
        .blueprintDialogOverlay(isPresented: Binding(get: { !asking && !model.summaryConsent.isEmpty }, set: { _ in })) {
            SummaryConsentDialog(model: model, trainingOff: $trainingOff, answer: answerSummaryConsent)
        }
        .sheet(isPresented: $sharing) { DetailShareSheet(model: model) }
        // docs/09 "Ask": a full-height sheet of its own, presented from a node of its own — the page already
        // presents Share.
        .background {
            Color.clear.sheet(isPresented: $asking, onDismiss: closeAsk) {
                AskFrame(close: { asking = false }) { askPanel }
                    .blueprintDialogOverlay(isPresented: Binding(get: { !model.summaryConsent.isEmpty }, set: { _ in })) {
                        SummaryConsentDialog(model: model, trainingOff: $trainingOff, answer: answerSummaryConsent)
                    }
                    .presentationDetents([.large])
            }
        }
        #else
        // docs/09 "Ask": a card over the detail, as its other questions are.
        .blueprintDialogOverlay(isPresented: $asking) {
            AskFrame(close: { asking = false; closeAsk() }) { askPanel }
        }
        #endif
        // The identity of the *model*, not of the recording: the Mac keeps one view here and hands
        // it a new model on every pick — including a second pick of the row already open — and a
        // plain `.task` runs once for the view, leaving every model after the first on "Loading…".
        .task(id: ObjectIdentifier(model)) {
            // Before the new recording is even read: what the last model was playing is not what
            // this page is about any more — and neither is a name half typed for it. The Mac
            // swaps the model behind this view when another row is picked, and a prompt left open
            // across that swap would put one recording's new name on another.
            player.stop()
            renaming = false
            draft = nil
            summaryDraft = nil
            // docs/10 "Search": a hit found only in the summary opens on it.
            showingSummary = model.opensOnSummary
            lastFormat = nil
            asking = false
            question = ""
            await model.load()
            guard !Task.isCancelled else { return }
            player.load(model.audio)
            await model.followAudio()
        }
        .task(id: ObjectIdentifier(model)) { await model.followCapture() }
        .task(id: ObjectIdentifier(model)) { await model.followJobs() }
        .task(id: ObjectIdentifier(model)) { await model.followSummary() }
        .task(id: ObjectIdentifier(model)) { await model.followChatGpt() }
        .task(id: ObjectIdentifier(model)) { await model.followSummaryPreferences() }
        .task(id: ObjectIdentifier(model)) { await model.followAsk() }
        .onChange(of: model.silences, initial: true) { _, silences in player.silences = silences }
        .onChange(of: model.deviceRecording) { _, active in if active { player.stop() } }
        .onChange(of: model.audio) { _, audio in player.load(audio) }
        // Results can arrive while the audio or waveform is still loading. Start after the
        // initial result read, independently of that slower work, without replacing the player.
        .task(id: ResultObservationID(model: ObjectIdentifier(model), loading: model.loading)) {
            if !model.loading { await model.followResults() }
        }
        .task {
            while !Task.isCancelled {
                player.refreshStatus()
                try? await Task.sleep(for: .milliseconds(200))
            }
        }
        // The window closed, the sheet dismissed, the split pane emptied: nothing keeps playing
        // behind a page nobody is looking at.
        .onDisappear { player.stop() }
    }

    /// docs/08 "Result files" · docs/09 screen principle 2: the recording itself, where this device still has
    /// it. Its shape on top, with the playhead moving across it and a drag on it to move where the
    /// playhead is, and the button and the recording's own clock under that.
    private var playerBar: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            if model.hasAudio, !(model.waveformPending && model.waveform.isEmpty) {
                waveform
            } else if model.hasAudio {
                // docs/09 "Motion": the peaks are still being read or decoded — the same loader the
                // Drive fetch shows, and not the baseline, which would be a silent recording.
                // Playback does not need the peaks, so the rest of the bar is as it always is.
                waveformLoader(label: loc("Loading waveform…"))
            } else if model.driveFetch == .fetching {
                // While the parts are coming back from Drive the row is already there, loading, so
                // the bars arrive in place rather than the bar growing a row when they do. The
                // fetch's own progress carries what a screen reader hears.
                waveformLoader(label: nil)
            }
            controls
            #if os(iOS)
            if model.driveFetch == .failed {
                Text(verbatim: model.folder
                    ? loc("Could not read from the local folder")
                    : loc(model.icloud ? "Could not fetch from iCloud" : "Could not fetch from Drive"))
                    .font(blueprint.fonts.bodySmall).foregroundStyle(blueprint.palette.textMuted)
            }
            #endif
            if player.failed {
                Text(verbatim: loc("Could not play this recording. Try playing it again."))
                    .font(blueprint.fonts.bodySmall).foregroundStyle(blueprint.palette.danger)
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .background(blueprint.palette.surface)
        .onChange(of: model.recordingId) { fetched = false; growStart = nil }
        .onChange(of: model.driveFetch) { _, fetch in if fetch == .fetching { fetched = true } }
        .onChange(of: model.waveform.isEmpty) { _, empty in
            if !empty, fetched, !blueprint.reduceMotion { growStart = .now }
        }
        // The rise is 0.75 s; after it the timeline stops asking for frames.
        .task(id: growStart) {
            guard growStart != nil else { return }
            try? await Task.sleep(for: .seconds(Self.growSec))
            growStart = nil
        }
    }

    /// docs/09 screen principle 2: the recording as a shape, and the one place on this page a second of it
    /// can be pointed at. The drag is on the whole row, so a tap anywhere in it is a seek — and
    /// playback is not interrupted by it, because what a scrub is for is hearing another part of
    /// the same take.
    private var waveform: some View {
        GeometryReader { geometry in
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: growStart == nil)) { timeline in
                Canvas { context, size in
                    let reveal = growStart.map { min(1, timeline.date.timeIntervalSince($0) / Self.growSec) } ?? 1
                    draw(waveform: &context, size: size, reveal: reveal)
                }
            }
            // The bars are 2pt of a 3pt column, so without this the gaps between them are not the
            // row and a drag that starts in one goes nowhere.
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { scrubSec = second(atX: $0.location.x, width: geometry.size.width) }
                    .onEnded {
                        seek(toSec: second(atX: $0.location.x, width: geometry.size.width))
                        scrubSec = nil
                    }
            )
        }
        .frame(height: minTouch)
        .accessibilityElement()
        .accessibilityIdentifier("waveform")
        .accessibilityLabel(Text(verbatim: loc("Position")))
        // docs/09 Accessibility: the same stamp the clock beside it shows, because that is what the
        // playhead is — a drag has no reading of its own to give.
        .accessibilityValue(Text(verbatim: LedgerFormat.clock(Int(positionSec))))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: seek(toSec: positionSec + Waveform.stepSec)
            case .decrement: seek(toSec: positionSec - Waveform.stepSec)
            @unknown default: break
            }
        }
        // docs/09 Accessibility: the same two steps without VoiceOver — the bar is a focus stop and the
        // arrows move the playhead by [Waveform.stepSec], because a scrub that only a drag can do
        // is a control a keyboard cannot reach. The focus ring is the system's own.
        //
        // The watch has no key to press: `onKeyPress` is unavailable there, and the crown that
        // would stand in for it is the scroll view's.
        .focusable()
        #if !os(watchOS)
        .onKeyPress(.leftArrow) {
            seek(toSec: positionSec - Waveform.stepSec)
            return .handled
        }
        .onKeyPress(.rightArrow) {
            seek(toSec: positionSec + Waveform.stepSec)
            return .handled
        }
        #endif
        // docs/09 "Highlights": a tap within 12 pt of a tick opens its menu — Go to, Remove — and a screen
        // reader finds each tick as an element of its own with the same two actions.
        .overlay {
            GeometryReader { geometry in
                ForEach(model.highlights, id: \.self) { mark in
                    HighlightMenu(atSec: mark, stamp: model.stamp(mark), go: { seek(toSec: mark) }, remove: { Task { await model.removeHighlight(mark) } }) {
                        Color.clear
                            .frame(width: 24, height: geometry.size.height)
                            .contentShape(Rectangle())
                    }
                    .position(
                        x: model.totalSec > 0 ? geometry.size.width * mark / model.totalSec : 0,
                        y: geometry.size.height / 2
                    )
                    .accessibilityIdentifier("highlight-tick")
                }
            }
        }
    }

    /// docs/09 "Motion": the waveform row while the recording comes back from Drive, or while its peaks
    /// are decoded, with no words — short ghost ticks where the bars will be, and a hard-edged band
    /// of ten that steps across them left to right, one bar a frame at 30 fps. It does not rise and
    /// fall or flow the way a playing or recording waveform does, and it leaves nothing filled
    /// behind it the way a playhead does. With reduce motion the band stays off: for a Drive fetch
    /// the bar says it in words ([controls]); for a decode the still ticks are all there is.
    /// [label] is what a screen reader hears — nil where the fetch's progress already says it.
    private func waveformLoader(label: String?) -> some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: blueprint.reduceMotion)) { timeline in
            Canvas { context, size in
                let count = Int(size.width / Waveform.step)
                let frame = Int(timeline.date.timeIntervalSinceReferenceDate * 30)
                let head = blueprint.reduceMotion ? -1 : frame % (count + Self.loaderBand)
                let tick = size.height * Self.loaderTick
                for index in 0..<max(0, count) {
                    let lit = index > head - Self.loaderBand && index <= head
                    context.fill(
                        Path(CGRect(x: CGFloat(index) * Waveform.step, y: (size.height - tick) / 2, width: Waveform.bar, height: tick)),
                        with: .color(lit ? blueprint.palette.textMuted : blueprint.palette.grid)
                    )
                }
            }
        }
        .frame(height: minTouch)
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: label ?? ""))
        .accessibilityHidden(label == nil)
    }

    /// docs/09: how far the trip to Drive is, in the place and the shape of the Play button it
    /// becomes — the button's own outline, filling with the button's own colour, so that when it is
    /// full it is the button. It is sized by a hidden Play button, so nothing moves when Play takes
    /// its place, and it says the percentage inside it — accent on the empty part, the button's own
    /// ink on the filled part — so at 0% it never reads as an empty or disabled button.
    private var fetchProgress: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.node)
        let fraction = min(max(model.fetchProgress, 0), 1)
        let percent = Text(verbatim: "\(Int((fraction * 100).rounded(.down)))%")
            .font(blueprint.fonts.monoBodySmall)
            .lineLimit(1)
        return BlueprintButton(loc("Play"), tone: .primary, minWidth: playbackButtonMinWidth) {}
            .hidden()
            .overlay {
                GeometryReader { geometry in
                    let filled = geometry.size.width * fraction
                    ZStack(alignment: .leading) {
                        blueprint.palette.accent.frame(width: filled)
                        percent
                            .foregroundStyle(blueprint.palette.accent)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        percent
                            .foregroundStyle(blueprint.palette.onAccent)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .mask(alignment: .leading) { Rectangle().frame(width: filled) }
                    }
                }
                .clipShape(shape)
                .overlay(shape.strokeBorder(blueprint.palette.accent, lineWidth: blueprint.line))
            }
            .animation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion), value: model.fetchProgress)
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: model.folder
                ? loc("Reading from the local folder…")
                : loc(model.icloud ? "Fetching from iCloud…" : "Fetching from Drive…")))
            .accessibilityValue(Text(verbatim: "\(Int(fraction * 100))%"))
    }

    /// Only with reduce motion, where the waveform row has stopped saying it — in the bar's own
    /// sentence type, as "Could not fetch from Drive" is.
    @ViewBuilder private var fetchingWords: some View {
        if blueprint.reduceMotion {
            Text(verbatim: model.folder
                ? loc("Reading from the local folder…")
                : loc(model.icloud ? "Fetching from iCloud…" : "Fetching from Drive…"))
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
        }
    }

    /// 2026-10-08 §9: the phone's header is one row — title, Share, More and Close — with the title
    /// giving way. The Mac's pane keeps the header that wraps its buttons under a long title.
    #if os(iOS)
    private static let oneRowHeader = true
    #else
    private static let oneRowHeader = false
    #endif

    /// docs/09: a tenth of the row's width at a time, slow enough to read as work and not as sound.
    private static let loaderBand = 10
    /// The ghost ticks' height, as a share of the row.
    private static let loaderTick: CGFloat = 0.3
    /// The bars' rise when a recording arrives from Drive, the last bar starting at 60%.
    private static let growSec: Double = 0.75
    private static let growSpread: Double = 0.6

    /// docs/09 "Lines": straight bars of one width on one gap, no caps and no gradient. Behind the
    /// playhead is the accent and ahead of it the muted colour, both at full opacity: docs/09 Accessibility
    /// asks 3:1 of a graphic, and the muted token faded out to hint at "not played yet" is under
    /// 2:1 on the surface. The token promotes itself to the body colour in high contrast, so there
    /// is nothing here to special-case.
    ///
    /// Nothing decoded yet (or a decode that failed) is one hairline across the middle: the row
    /// keeps its height and its playhead, so the bar does not change shape when the peaks arrive.
    private func draw(waveform context: inout GraphicsContext, size: CGSize, reveal: Double = 1) {
        let playhead = model.totalSec > 0 ? size.width * positionSec / model.totalSec : 0
        let bins = RecordingWaveform.bins(
            peaks: model.waveform,
            count: Int(size.width / Waveform.step)
        )
        if bins.isEmpty {
            context.fill(
                Path(CGRect(
                    x: 0,
                    y: (size.height - blueprint.line) / 2,
                    width: size.width,
                    height: blueprint.line
                )),
                with: .color(blueprint.palette.grid)
            )
        }
        for (index, bin) in bins.enumerated() {
            let x = CGFloat(index) * Waveform.step
            // Left first: each bar starts a little after the one before it and rises in 40% of the time.
            let start = Self.growSpread * Double(index) / Double(max(1, bins.count - 1))
            let k = min(max((reveal - start) / (1 - Self.growSpread), 0), 1)
            let rise = 1 - pow(1 - k, 3)
            // Silence is a tick rather than nothing, so the row reads as the whole recording.
            let height = max(Waveform.minBar, CGFloat(bin) * size.height * rise)
            context.fill(
                Path(CGRect(
                    x: x,
                    y: (size.height - height) / 2,
                    width: Waveform.bar,
                    height: height
                )),
                with: .color(x <= playhead ? blueprint.palette.accent : blueprint.palette.textMuted)
            )
        }
        // docs/09 "Highlights": each highlight a 2 pt accent line over the whole height with a 6×6 filled
        // square at the top, above the bars and under the playhead.
        for mark in model.highlights where model.totalSec > 0 {
            let x = min(max(0, size.width * mark / model.totalSec), size.width - 2)
            context.fill(Path(CGRect(x: x, y: 0, width: 2, height: size.height)), with: .color(blueprint.palette.accent))
            context.fill(Path(CGRect(x: x, y: 0, width: 6, height: 6)), with: .color(blueprint.palette.accent))
        }
        context.fill(
            Path(CGRect(
                x: min(max(0, playhead), size.width - blueprint.line),
                y: 0,
                width: blueprint.line,
                height: size.height
            )),
            with: .color(blueprint.palette.accent)
        )
    }

    private var playbackButtonMinWidth: CGFloat? {
        #if os(iOS)
        playButtonMinWidth
        #else
        nil
        #endif
    }

    /// docs/08 "Result files": one button and the recording's own clock.
    private var controls: some View {
        HStack(spacing: Space.s) {
            if model.driveFetch == .fetching {
                // docs/03 ADR-017: the button's place holds how far the trip is.
                #if os(iOS)
                fetchingWords
                Spacer(minLength: Space.s)
                fetchProgress
                #else
                fetchProgress
                fetchingWords
                #endif
            } else if model.hasAudio {
                #if os(iOS)
                Text(verbatim: "\(model.stamp(positionSec)) / \(model.stamp(model.totalSec))")
                    .font(blueprint.fonts.monoBodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                Spacer(minLength: Space.s)
                // docs/09 "Playback": the speed between the clock and Play.
                PlaybackSpeedChip(player: player)
                #endif
                // Not while this device is recording: on the phone that session belongs to the
                // recorder (see `RecordingPlayer`), and the Mac says the same thing so that the
                // page does not offer one shell what it refuses the other. Nothing stands in its
                // place — the clock alone says there is something here, later.
                // Nor while the trip to Drive is still being decided: what this page will play is
                // not settled yet, and a Play offered now would be answered by whatever the player
                // last held. The clock stays, so the bar does not change shape when it appears.
                if !model.deviceRecording, !player.captureBlocked, model.driveFetch != .deciding {
                    BlueprintButton(
                        player.active ? loc("Pause") : loc("Play"),
                        tone: .primary,
                        minWidth: playbackButtonMinWidth
                    ) {
                        if player.active {
                            player.pause()
                        } else {
                            // This model's audio, at the press: the player holds nothing between
                            // one recording and the next (see `RecordingPlayer.stop`).
                            player.load(model.audio)
                            player.play()
                        }
                    }
                    .accessibilityIdentifier("play-pause")
                }
                // docs/07 rule 4: a clock is a stamp, not a sentence.
                #if !os(iOS)
                Text(verbatim: "\(model.stamp(positionSec)) / \(model.stamp(model.totalSec))")
                    .font(blueprint.fonts.monoBodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                // docs/09 "Playback" · "Highlights" (desktop): the speed, and a mark at the playhead.
                PlaybackSpeedChip(player: player)
                BlueprintButton(loc("Highlight"), tone: .quiet) {
                    Task { await model.addHighlight(atSec: positionSec) }
                }
                .accessibilityIdentifier("detail-highlight")
                #endif
            } else if model.driveFetch == .idle {
                // docs/03: nothing of this recording ever reached Drive, and what was here is gone
                // — so there is nowhere left to play it from. Only once the fetch has been decided
                // against: said while it is still `.deciding` it would be a sentence the next
                // moment takes back.
                Text(verbatim: loc("No audio on this device"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            #if !os(iOS)
            if model.driveFetch == .failed {
                // Beside the clock when some parts are here and on its own when none are: either
                // way it is what stands between the page and the whole recording.
                Text(verbatim: model.folder
                    ? loc("Could not read from the local folder")
                    : loc(model.icloud ? "Could not fetch from iCloud" : "Could not fetch from Drive"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            Spacer(minLength: 0)
            #endif
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Where the bar says it is: the finger while there is one on the waveform, and the player the
    /// rest of the time.
    private var positionSec: Double { scrubSec ?? player.positionSec }

    private var canSeek: Bool {
        !model.deviceRecording && !model.writing && model.hasAudio && model.driveFetch != .deciding && model.driveFetch != .fetching
    }

    /// docs/09 "Summary view": the More menu's Summarize and Summarize as…, and the summary's own Retry — the
    /// page turns to the summary while the core writes it.
    private func summarize(_ format: SummaryFormat?) {
        showingSummary = true
        lastFormat = format
        Task { await model.summarize(format: format) }
    }

    /// The More menu's Summarize: over a summary the user edited, the question first.
    private func summarizeAgain(_ format: SummaryFormat?) {
        if model.summaryEdited { replacing = Replacement(format: format) } else { summarize(format) }
    }

    /// What `Replace your edited summary?` goes on to start.
    private struct Replacement: Equatable {
        let format: SummaryFormat?
    }

    /// docs/09 "Summary view": a citation plays the recording from its second, as a transcript time button does —
    /// nil, so the citations are plain text, while the recording cannot be played from a point.
    private var citationSeek: ((Double) -> Void)? {
        canSeek ? { seek(toSec: $0) } : nil
    }

    // MARK: Ask (docs/09 "Ask")

    private func openAsk() {
        asking = true
        Task { await model.openAsk() }
    }

    /// Closing drops the answer; a question still running finishes, and reopening shows it running.
    private func closeAsk() {
        model.closeAsk()
    }

    private var askPanel: some View {
        AskPanel(
            state: model.ask,
            presets: model.askPresets,
            modelLabel: { ChatGptText.modelLabel($0, connection: model.chatGpt) },
            question: $question,
            ask: { preset, typed in Task { await model.ask(preset: preset, question: typed) } },
            retry: { Task { await model.retryAsk() } },
            copy: model.copyAnswer,
            seekableSec: model.totalSec,
            onSeek: citationSeek
        )
    }

    private func answerSummaryConsent(allow: Bool) {
        trainingOff = false
        Task {
            await model.answerSummaryConsent(allow: allow)
            // Declined with nothing to show: the page goes back to what it was showing.
            if !allow, !model.hasSummaryView { showingSummary = false }
        }
    }

    private var headerTitle: String {
        if draft != nil { return loc("Edit transcript") }
        if summaryDraft != nil { return loc("Edit summary") }
        return model.title
    }

    /// One of the two editors is up, and the lines that belong to reading stay off.
    private var editing: Bool { draft != nil || summaryDraft != nil }

    /// Which editor `Discard your changes?` is about — and, from the Transcript chip, where the page goes.
    private enum Discarding { case transcript, summary, summaryForTranscript }

    /// Cancel or Done: straight back to reading when nothing changed, else the question first.
    private func leaveEditor() {
        if draft?.changed == true { discarding = .transcript } else { draft = nil }
    }

    private func leaveSummaryEditor() {
        if summaryDraft?.changed == true { discarding = .summary } else { summaryDraft = nil }
    }

    /// The Transcript chip: from the summary's editor, the same question Cancel asks when there are changes.
    private func showTranscript() {
        if summaryDraft?.changed == true {
            discarding = .summaryForTranscript
        } else {
            summaryDraft = nil
            showingSummary = false
        }
    }

    private func discard() {
        switch discarding {
        case .transcript: draft = nil
        case .summary: summaryDraft = nil
        case .summaryForTranscript:
            summaryDraft = nil
            showingSummary = false
        case nil: break
        }
        discarding = nil
    }

    private func saveDraft() {
        guard let draft else { return }
        Task { if await model.edit(draft.edits) { self.draft = nil } }
    }

    /// Save → `Saving…` → ✓ under the header, back on the summary.
    private func saveSummary() {
        guard let summaryDraft else { return }
        Task { if await model.editSummary(summaryDraft.text) { self.summaryDraft = nil } }
    }

    /// Where in the recording a point of the row is. The row is the whole recording end to end, so
    /// this is the one piece of arithmetic the scrub is.
    private func second(atX x: CGFloat, width: CGFloat) -> Double {
        guard width > 0 else { return 0 }
        return min(max(0, Double(x / width)), 1) * model.totalSec
    }

    /// The player may be holding nothing at all (see `RecordingPlayer.stop`), so it is handed this
    /// model's audio first — the same thing the press of Play does.
    private func seek(toSec sec: Double) {
        player.load(model.audio)
        player.seek(toSec: sec)
    }

    /// The whole page, when there is one line to say and nothing to read.
    private func notice(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.bodySmall)
            .foregroundStyle(blueprint.palette.textMuted)
            .multilineTextAlignment(.center)
            .padding(Space.l)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

#endif

/// The lane's shared user-visible text: what both shells say about a job the `transcribe` step is
/// holding up, and where the detail behind a row is opened from. RecKit's own catalog, because both
/// Apple shells read the same sentences (docs/07 rule 1).
public enum RecordingDetailStrings {
    /// The row's own action, and so the word for what is behind *this* row — not the name of the
    /// surface it opens (the Mac's window, which is titled from its own catalog).
    public static var open: String { RecKitStrings.localized("Details") }
    public static var checkKey: String { RecKitStrings.localized("Check the key") }
}

/// The phone's Play button, and the ledger row's Details button that opens the page it is on: the
/// row's most used action, so it is not left the narrowest (its Korean label, "상세", is two
/// letters). Android's `PlayMinWidth` and `DetailMinWidth` are the same 120.
public let playButtonMinWidth: CGFloat = 120
