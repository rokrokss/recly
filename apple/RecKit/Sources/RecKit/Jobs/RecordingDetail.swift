import Foundation
import os
import ReclyCore
import SwiftUI

/// docs/08 "결과 파일", deliverable 3: what the `transcribe` step wrote for one recording. The local
/// copy if the step ran on this device, and Drive's if it ran on another — `core.results` decides
/// which, and keeps what it downloads.
///
/// Shared by both Apple shells, because "the same features on the phone and on macOS" is easier to
/// keep true than to re-check (the workflow editor is shared for the same reason).
@MainActor
public final class RecordingDetailModel: ObservableObject, Identifiable {
    @Published public private(set) var loading = true
    @Published public private(set) var transcript: Transcript?
    /// docs/08 "결과 파일": the audio beside the transcript, when this device still has it.
    @Published public private(set) var audio = RecordingPlaylist.Selection.empty
    /// docs/09 화면 원칙 2: the shape of [audio], one peak per 0.25 s window, for the bar to draw a
    /// playhead across. Filled at the very end of [load] — after the trip to Drive, which is what
    /// settles which parts there are to draw — and left empty by a decode that failed. The bar
    /// draws its baseline until then, and a recording is never held up by its picture.
    @Published public private(set) var waveform: [Float] = []
    /// A take still being written to has nothing whole to play yet, so the detail offers nothing.
    @Published public private(set) var writing = false
    /// Whether *any* recording on this device is being written right now — which is not the same
    /// question as [writing], and is the one that decides whether Play may be offered at all. See
    /// [RecordingPlayer]: the recorder owns the audio session while it runs.
    @Published public private(set) var deviceRecording = false
    /// docs/03 ADR-017: how the trip to Drive for the parts the retention sweep took is going.
    @Published public private(set) var driveFetch = DriveFetch.deciding

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

    public let recordingId: String
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

    public init(core: ReclyCore_, recordingId: String, title: String) {
        self.core = core
        self.recordingId = recordingId
        self.title = title
    }

    /// Called again whenever the view is handed a different model, so it starts from `loading`
    /// every time rather than from whatever the last call left behind.
    public func load() async {
        loading = true
        driveFetch = .deciding
        do {
            transcript = try await core.results(recordingId: recordingId).transcript
            audio = try await localAudio()
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
        await fetchFromDrive()
        // docs/09 화면 원칙 2: the picture last, and inside the load rather than beside it. Last
        // because the trip to Drive is what settles which parts there are, and a decode of the
        // local prefix would be a picture of a different recording than the one that plays. Inside
        // because the `.task` that runs this load is also what cancels it: the Mac swaps the model
        // behind one view, and reading a whole recording for a bar nobody is looking at any more is
        // work the next pick would be waiting behind.
        waveform = []
        let peaks = try? await RecordingWaveform.peaks(for: audio)
        guard !Task.isCancelled else { return }
        waveform = peaks ?? []
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

    /// The parts of this recording that are still on this device.
    private func localAudio() async throws -> RecordingPlaylist.Selection {
        guard let record = try await core.recordings.get(id: recordingId) else {
            writing = false
            givenTitle = ""
            playedParts = []
            directory = nil
            return .empty
        }
        writing = record.meta.status == RecordingStatus.recording
        givenTitle = record.meta.title ?? ""
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
        do {
            let fetched = try await core.audio(recordingId: recordingId)
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
    /// the window the ledger itself reads is more than enough to find it.
    private func somethingIsBeingRecorded() async throws -> Bool {
        try await core.recordings.list(limit: Recents.count)
            .contains { $0.meta.status == RecordingStatus.recording }
    }
}

/// docs/09 화면 원칙 2 · "간격": the waveform row's own rhythm. A 2pt bar on a 1pt gap, so how many
/// bars there are is however many 3pt columns the row is wide — the shape is the recording's, and
/// the number of bars is the screen's.
private enum Waveform {
    static let bar: CGFloat = 2
    static let step: CGFloat = 3
    /// A bin with no sound in it, so that silence is still part of the timeline.
    static let minBar: CGFloat = 1
    /// docs/09 접근성: what one step of the adjustable action moves, for a scrub with no finger.
    static let stepSec: Double = 5
}

/// docs/09 화면 원칙 2: the detail is a page behind a ledger row rather than a pane in front of it,
/// so the header carries the way back — docs/08's result file, the transcript as the speaker turns
/// it is made of.
public struct RecordingDetailView: View {
    @ObservedObject private var model: RecordingDetailModel
    /// One player for the surface rather than for the model: the Mac keeps a single detail view in
    /// its split pane and swaps the model behind it, and picking another recording has to stop the
    /// one that is playing.
    @StateObject private var player = RecordingPlayer()
    /// Where the finger is while it is on the waveform, and `nil` the rest of the time. The
    /// playhead and the clock follow it rather than the player: the seek happens when the drag
    /// ends, and a bar that only moved then would not be a scrub.
    @State private var scrubSec: Double?
    /// Whether the rename prompt is up. Here rather than in the model: a question cancelled is one
    /// the recording never heard, and the model is what the page has already answered.
    @State private var renaming = false
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: every string on this screen is resolved outside SwiftUI, so reading the
    /// locale is what declares the dependency that redraws it in the new language.
    @Environment(\.locale) private var locale
    /// nil where the page is the whole surface it is in — the Mac's window has nothing to go back
    /// to, and a sheet on the phone does.
    private let onClose: (() -> Void)?

    public init(model: RecordingDetailModel, onClose: (() -> Void)? = nil) {
        self.model = model
        self.onClose = onClose
    }

    public var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: model.title, meta: model.recordingId) {
                // docs/03: the name is the user's to change, from the page that carries it. Not
                // while the recording is still being written — the core refuses a rename then, and
                // an action that could only fail is one the page should not be offering. Not
                // before the load has said which of the two this is, either.
                if !model.loading, !model.writing {
                    BlueprintButton(loc("Rename"), tone: .quiet) { renaming = true }
                        .accessibilityIdentifier("detail-rename")
                }
                if let onClose {
                    // Leaving the page stops what it was playing: the sheet is gone but this view
                    // is not torn down synchronously with it.
                    BlueprintButton(loc("Close"), tone: .quiet) {
                        player.stop()
                        onClose()
                    }
                    .accessibilityIdentifier("detail-close")
                }
            }
            HairLine()
            if !model.loading, !model.writing {
                playerBar
                HairLine()
            }
            if model.loading {
                notice(loc("Loading…"))
            } else if model.transcript == nil {
                notice(loc("Nothing here yet — the transcribe step has not finished."))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: Space.s) {
                        transcriptBody
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.vertical, Space.m)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
            await model.load()
            guard !Task.isCancelled else { return }
            player.load(model.audio)
        }
        // The window closed, the sheet dismissed, the split pane emptied: nothing keeps playing
        // behind a page nobody is looking at.
        .onDisappear { player.stop() }
    }

    /// docs/08 "결과 파일" · docs/09 화면 원칙 2: the recording itself, where this device still has
    /// it. Its shape on top, with the playhead moving across it and a drag on it to move where the
    /// playhead is, and the button and the recording's own clock under that.
    private var playerBar: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            if model.hasAudio {
                waveform
            }
            controls
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .background(blueprint.palette.surface)
    }

    /// docs/09 화면 원칙 2: the recording as a shape, and the one place on this page a second of it
    /// can be pointed at. The drag is on the whole row, so a tap anywhere in it is a seek — and
    /// playback is not interrupted by it, because what a scrub is for is hearing another part of
    /// the same take.
    private var waveform: some View {
        GeometryReader { geometry in
            Canvas { context, size in
                draw(waveform: &context, size: size)
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
        // docs/09 접근성: the same stamp the clock beside it shows, because that is what the
        // playhead is — a drag has no reading of its own to give.
        .accessibilityValue(Text(verbatim: LedgerFormat.elapsed(Int(positionSec))))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: seek(toSec: positionSec + Waveform.stepSec)
            case .decrement: seek(toSec: positionSec - Waveform.stepSec)
            @unknown default: break
            }
        }
        // docs/09 접근성: the same two steps without VoiceOver — the bar is a focus stop and the
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
    }

    /// docs/09 "선": straight bars of one width on one gap, no caps and no gradient. Behind the
    /// playhead is the accent and ahead of it the muted colour, both at full opacity: docs/09 접근성
    /// asks 3:1 of a graphic, and the muted token faded out to hint at "not played yet" is under
    /// 2:1 on the surface. The token promotes itself to the body colour in high contrast, so there
    /// is nothing here to special-case.
    ///
    /// Nothing decoded yet (or a decode that failed) is one hairline across the middle: the row
    /// keeps its height and its playhead, so the bar does not change shape when the peaks arrive.
    private func draw(waveform context: inout GraphicsContext, size: CGSize) {
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
            // Silence is a tick rather than nothing, so the row reads as the whole recording.
            let height = max(Waveform.minBar, CGFloat(bin) * size.height)
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

    /// docs/08 "결과 파일": one button and the recording's own clock.
    private var controls: some View {
        HStack(spacing: Space.s) {
            if model.driveFetch == .fetching {
                // docs/03 ADR-017: where the clock is, because it is what the clock is instead of.
                // No Play either — there is nothing whole to play until the parts are back.
                Text(verbatim: loc("Fetching from Drive…"))
                    .font(blueprint.fonts.monoBodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            } else if model.hasAudio {
                // Not while this device is recording: on the phone that session belongs to the
                // recorder (see `RecordingPlayer`), and the Mac says the same thing so that the
                // page does not offer one shell what it refuses the other. Nothing stands in its
                // place — the clock alone says there is something here, later.
                // Nor while the trip to Drive is still being decided: what this page will play is
                // not settled yet, and a Play offered now would be answered by whatever the player
                // last held. The clock stays, so the bar does not change shape when it appears.
                if !model.deviceRecording, model.driveFetch != .deciding {
                    BlueprintButton(
                        player.isPlaying ? loc("Pause") : loc("Play"),
                        tone: .primary
                    ) {
                        if player.isPlaying {
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
                Text(verbatim: "\(LedgerFormat.elapsed(Int(positionSec))) / \(LedgerFormat.elapsed(Int(model.totalSec)))")
                    .font(blueprint.fonts.monoBodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            } else if model.driveFetch == .idle {
                // docs/03: nothing of this recording ever reached Drive, and what was here is gone
                // — so there is nowhere left to play it from. Only once the fetch has been decided
                // against: said while it is still `.deciding` it would be a sentence the next
                // moment takes back.
                Text(verbatim: loc("No audio on this device"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            if model.driveFetch == .failed {
                // Beside the clock when some parts are here and on its own when none are: either
                // way it is what stands between the page and the whole recording.
                Text(verbatim: loc("Could not fetch from Drive"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            Spacer(minLength: 0)
        }
    }

    /// Where the bar says it is: the finger while there is one on the waveform, and the player the
    /// rest of the time.
    private var positionSec: Double { scrubSec ?? player.positionSec }

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

    /// docs/08 `transcript.json`: one block per speaker turn, on the recording's own clock.
    @ViewBuilder
    private var transcriptBody: some View {
        ForEach(turns(model.transcript?.segments ?? []), id: \.start) { turn in
            VStack(alignment: .leading, spacing: 2) {
                // docs/07 rule 4: the stamp and the speaker are codes, not sentences.
                Text(verbatim: "\(LedgerFormat.elapsed(Int(max(0, turn.start)))) \(turn.speaker)")
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                Text(verbatim: turn.text)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private struct Turn {
        let speaker: String
        let start: Double
        var text: String
    }

    /// Consecutive segments of one speaker read as one thing said, as `TranscriptNormalizer.text`
    /// builds the `.txt` beside it.
    private func turns(_ segments: [TranscriptSegment]) -> [Turn] {
        var turns: [Turn] = []
        for segment in segments {
            let text = segment.text.trimmingCharacters(in: .whitespaces)
            if var last = turns.last, last.speaker == segment.speaker {
                last.text += " " + text
                turns[turns.count - 1] = last
            } else {
                turns.append(Turn(speaker: segment.speaker, start: segment.start, text: text))
            }
        }
        return turns
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// The lane's shared user-visible text: what both shells say about a job the `transcribe` step is
/// holding up, and where the detail behind a row is opened from. RecKit's own catalog, because both
/// Apple shells read the same sentences (docs/07 rule 1).
public enum RecordingDetailStrings {
    /// The row's own action, and so the word for what is behind *this* row — not the name of the
    /// surface it opens (the Mac's window, which is titled from its own catalog).
    public static var open: String { RecKitStrings.localized("Details") }
    public static var checkKey: String { RecKitStrings.localized("Check the key") }
}
