import ReclyCore
import RecKit
import SwiftUI

/// docs/09 screen principle 1: the recording screen is a dashboard — a monospace timer, and one square
/// node under it that starts and stops the recording (docs/13 I2).
///
/// The accessibility identifiers are not decoration: `RecPhoneUITests` drives this screen to make
/// the recording the lane's simulator smoke asks for.
struct RecordingView: View {
    @ObservedObject var model: RecordingModel
    @Environment(\.blueprint) private var blueprint
    @State private var title = ""
    /// docs/03: nil is "unknown", which writes nothing at all rather than guessing.
    @State private var participants: Int?
    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale
    /// docs/09 "Accessibility": at the accessibility sizes the dashboard is taller than the phone, and the
    /// record node is the part that falls off the bottom of it.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// How tall the live notes' rows are, which their scroll view grows to and no further. Nil until measured.
    @State private var notesHeight: CGFloat?
    /// docs/08 "Memo": the memo's editor is up over the screen.
    @State private var editingMemo = false

    var body: some View {
        VStack(spacing: 0) {
            // 2026-10-08 §2: the app's name and nothing under it — the device id is in Settings → About,
            // where it is the point.
            ScreenHeader(title: "Recly")
            // docs/05 "Fixed processing settings": the first-run card, at the top of the Record tab, for a
            // phone set to transcribe on device that has no speech model yet.
            if let download = model.modelDownload {
                ModelPromptCard(
                    download: download,
                    dismissed: model.modelPromptDismissed,
                    waiting: model.alerts.contains { $0.reason == .localModel }
                ) {
                    model.modelPromptDismissed = true
                }
            }
            // Only there: a `ScrollView` at every size would cost the dashboard the one thing it is
            // — a screen whose parts are spaced across the phone rather than stacked at the top of
            // it. Inside one, the spacer between the readouts and the record node proposes nothing
            // and collapses, so the order is kept and the node is the end of the scroll.
            GeometryReader { geometry in
                if dynamicTypeSize.isAccessibilitySize || geometry.size.height < 520 {
                    ScrollView { dashboard(inScroll: true) }
                } else {
                    dashboard(inScroll: false)
                }
            }
            // docs/12 M8 · ADR-011: once, before the first recording. The wording is the Mac's, word
            // for word in both languages (`ConsentTextTests` holds the two together).
            //
            // Drawn in the screen rather than presented as a sheet: this screen's one sheet is the
            // naming prompt below, which is a different question asked at the other end of a
            // recording, and a second `.sheet` on the same view simply never opens.
            .blueprintDialogOverlay(isPresented: $model.consentPrompt) {
                ConsentDialog { confirmed, suppress in
                    model.consentAnswered(confirmed: confirmed, suppress: suppress)
                }
            }
            // docs/08 "Organizing": asked once, before a start from this screen, of someone signed in to ChatGPT who
            // never answered it. In the screen for the same reason as the reminder above.
            .blueprintDialogOverlay(isPresented: $model.organizeConsentPrompt) {
                OrganizeConsentDialog(permission: model.chatGpt?.organizePermission ?? OrganizeConsent.permission(destinationAllowed: false)) {
                    model.organizeConsentAnswered($0)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dotGridBackground()
        // docs/08 "Memo": the memo's editor, from the bottom edge. In the screen rather than presented, for the reason
        // the questions above are: the naming sheet below is this screen's one presentation. Done only closes it — it
        // saved as it went — and Stop closes it too.
        .blueprintBottomSheetOverlay(isPresented: editingMemo, onDismiss: { editingMemo = false }) {
            MemoSheet(memo: model.liveMemo, agreed: model.notesAgreed, save: model.saveLiveMemo) { editingMemo = false }
        }
        .onChange(of: model.recordingId) { editingMemo = false }
        // docs/08 "Organizing": the notes of the recording being made, followed while it is.
        .task(id: model.recordingId) { await model.followLiveNotes() }
        .task(id: model.recordingId) { await model.followLiveMemo() }
        .task(id: model.recordingId) { await model.followNotesAgreed() }
        // docs/03: the title is asked for after the recording has ended, and the job is queued once
        // the answer — or the refusal — is in. A sheet rather than an alert, because the same
        // prompt also asks how many people were in the room and an alert holds no picker. Driven by
        // the model's `naming`, which is also what carries the recording it is about — a `Bool` of
        // its own would be a second answer to the same question. docs/03 "Titles" · 2026-10-10 (1.3):
        // the take leaves it only through its two answers, and a bottom sheet has no swipe down.
        .blueprintBottomSheet(isPresented: model.naming != nil) {
            NamingSheet(
                title: $title,
                participants: $participants,
                asksPeople: !(model.processing?.transcriptionOff ?? false),
                autoFill: model.titleAutoFill,
                onSave: { finishNaming(with: title, participants: participants) },
                onCancel: cancelNaming
            )
        }
    }

    /// Everything under the header: the timer, and the record control with the
    /// status line and the permission recovery under it. [inScroll]: the whole of it scrolls (large text, a short
    /// screen), so the notes do not scroll on their own.
    private func dashboard(inScroll: Bool) -> some View {
        VStack(spacing: 0) {
            VStack(spacing: Space.sm) {
                // 2026-10-08 §4: the recording's length stays up while it is saved; `00:00` once idle.
                MonoTimer(model.state == .idle || model.elapsed.isEmpty ? LedgerFormat.elapsed(0) : model.elapsed)
                    .accessibilityIdentifier("elapsed")
                // docs/09 screen principle 6: the same strip the menu bar draws, under the timer — what is
                // being written, while it is being written. Nothing stands in for it when idle:
                // a placeholder waveform is a picture of audio that does not exist.
                if model.isRecording {
                    LiveWaveformView(peaks: model.livePeaks)
                        .padding(.horizontal, Space.m)
                        .accessibilityIdentifier("live-waveform")
                }
                // docs/08 "Memo": under the waveform while recording, empty or not — it is where the memo is written.
                if model.isRecording, model.recordingId != nil {
                    MemoRow(memo: model.liveMemo) { editingMemo = true }
                        .padding(.horizontal, Space.m)
                }
                // docs/08 "Organizing": beneath the waveform, the decisions, to-dos and questions of the recording
                // being made, as each part brings them — nothing while there are none.
                if model.isRecording, !NoteGroups.isEmpty(model.liveNotes, showsOverview: false) {
                    liveNotes(inScroll: inScroll)
                }
            }
            .frame(maxHeight: .infinity)

            VStack(spacing: Space.s) {
                recordNode
                // A space when there is nothing to say, so the line keeps its height and the node
                // above it never moves.
                Text(model.status.isEmpty ? " " : model.status)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    // A refused start or an unfinished save is a sentence, not a word: it wraps
                    // under the node rather than running to the edge.
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, Space.m)
                    .accessibilityIdentifier("status")
                    .accessibilityHidden(model.status.isEmpty)
                    // docs/09 "Disabled, focus and structure": the line is news, so it is announced when it changes.
                    .announcesChanges(of: model.status)
                if model.microphoneDenied {
                    VStack(spacing: Space.s) {
                        // 2026-10-10 (2.10): something to put right in Settings, not a failure — the warning tone.
                        Text("The microphone permission is required.")
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(BadgeTone.warning.ink(blueprint.palette))
                            .multilineTextAlignment(.center)
                        BlueprintButton(loc("Open Settings")) { model.openSettings() }
                            .accessibilityIdentifier("openSettings")
                    }
                    .padding(.horizontal, Space.m)
                }
            }
            .padding(.bottom, Space.l)
        }
    }

    /// The notes block as the recording screen has it: no overview, and the time a plain label — there is no playback
    /// while recording. A swipe deletes a row.
    @ViewBuilder
    private func liveNotes(inScroll: Bool) -> some View {
        let block = NotesBlock(
            notes: model.liveNotes,
            showsOverview: false,
            stamp: { LedgerFormat.stamp(Int($0), total: nil) },
            playable: false,
            seekableSec: 0,
            onSeek: nil,
            lines: model.liveNoteLines(atSec:),
            delete: model.deleteLiveNote
        )
        .padding(.horizontal, Space.m)
        if inScroll {
            block
        } else {
            // A scroll view takes all the height it is offered, which pushed the timer up against the header: no taller
            // than its rows, it leaves the timer centred while they fit, as on Android, and scrolls once they do not.
            ScrollView {
                block.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { notesHeight = $0 }
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: notesHeight)
        }
    }

    // MARK: - The record node

    /// docs/09 "Shape": the square record node (`RecordNode`), shared with the Mac's popover.
    private var recordNode: some View {
        RecordNode(recording: model.isRecording, working: model.state == .starting || model.state == .stopping) {
            if model.canStop { model.stop() } else { model.start() }
        }
        .disabled(!model.isReady)
        .accessibilityIdentifier(model.canStop ? "stop" : "start")
        .accessibilityLabel(model.canStop ? Text("Stop") : Text("Start recording"))
    }

    // MARK: - Naming

    private func finishNaming(with typed: String?, participants count: Int?) {
        guard model.naming != nil else { return }
        let answer = typed
        title = ""
        participants = nil
        Task { await model.finishNaming(with: answer, participants: count) }
    }

    private func cancelNaming() {
        guard model.naming != nil else { return }
        title = ""
        participants = nil
        Task { await model.cancelNaming() }
    }
}

/// docs/12 M8 · ADR-011: a local capture shows the other people in the room nothing at all, so the
/// responsibility for telling them is the user's and the app's job is to remind them — once, before
/// the first recording, and never again once they have said not to. There is no covert mode, and
/// this is not a permission screen.
///
/// Cancel means something: it is a question, and answering "no" leaves the recording unstarted.
private struct ConsentDialog: View {
    /// `(confirmed, suppress)`. The "do not ask again" box applies whichever button was pressed,
    /// as it does on the Mac, where it is `NSAlert`'s own suppression button.
    let answer: (Bool, Bool) -> Void

    @State private var suppress = false
    @Environment(\.openURL) private var openURL
    @Environment(\.locale) private var locale

    var body: some View {
        BlueprintDialog(title: loc("Did you tell the participants about the recording?")) {
            BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) { answer(false, suppress) }
            BlueprintButton(loc("I told them · Start recording"), tone: .primary) {
                answer(true, suppress)
            }
            .accessibilityIdentifier("consent-confirm")
        } content: {
            // docs/research/02 §Consent · law. The reminder and nothing else (2026-10-08 §11): the
            // rules by jurisdiction are behind the link under it, not in the question.
            BlueprintDialogText(loc("consent.body"))
                .accessibilityIdentifier("consent-body")
            // A link and not a third button, for the same reason as on the Mac: the question the
            // dialog is asking is still open.
            BlueprintDialogLink(loc("Recording-consent rules by jurisdiction")) {
                openURL(ConsentDialog.guidance)
            }
            BlueprintCheckRow(loc("Do not ask again"), isOn: $suppress)
                .accessibilityIdentifier("consent-suppress")
        }
    }

    /// Wikipedia's summary of recording-consent law until Recly has a page of its own to point at —
    /// the same URL the Mac opens (`MenuModel.consentGuidanceLink`).
    static let guidance = URL(string: "https://en.wikipedia.org/wiki/Telephone_call_recording_laws")!
}

/// docs/03: the name, and how many people were in the room — the hint `transcribe` trusts over the
/// workflow's own `speakers` (docs/08). "Unknown" is where it starts and it writes nothing.
///
/// A sheet rather than the alert the title alone used to be: an alert holds no picker.
/// docs/08 "Memo": the recording's memo while it records — its field, and `Done`, which only closes it.
private struct MemoSheet: View {
    let memo: String
    let agreed: Bool
    let save: (String) -> Void
    let done: () -> Void

    @FocusState private var focused: Bool
    @Environment(\.locale) private var locale

    /// As tall as what it holds: the bottom sheet it is on grows to it ([blueprintBottomSheetOverlay]).
    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: RecKitStrings.localized("Notes"))
            HairLine()
            VStack(alignment: .leading, spacing: Space.m) {
                MemoField(memo: memo, agreed: agreed, lines: 5...10, focused: $focused, save: save)
                HStack {
                    Spacer(minLength: 0)
                    BlueprintButton(RecKitStrings.localized("Done"), tone: .primary, action: done)
                        .accessibilityIdentifier("memo-done")
                }
            }
            .padding(Space.m)
            .frame(maxWidth: .infinity, alignment: .top)
        }
        .onAppear { focused = true }
    }
}

private struct NamingSheet: View {
    @Binding var title: String
    @Binding var participants: Int?
    /// 2026-10-10 (3.5): the count is a hint to the transcription, so it is not asked while transcription is off.
    let asksPeople: Bool
    /// docs/08 "Titles and speaker names": a title left empty is filled in when the recording is published.
    let autoFill: Bool
    let onSave: () -> Void
    let onCancel: () -> Void

    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    /// docs/03: 2 · 3 · 4 · 5 · 6+ · unknown. docs/08 caps the hint at 10 speakers, so "6+" asks
    /// for six and lets the provider find more.
    private let choices: [Int?] = [nil, 2, 3, 4, 5, 6]

    /// As tall as what it asks: the bottom sheet it is on grows to it ([blueprintBottomSheet]).
    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: loc("Recording title"))
            HairLine()
            fields
        }
    }

    private var fields: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            VStack(alignment: .leading, spacing: Space.xs) {
                BlueprintField(loc("Title"), text: $title, placeholder: TitlePlaceholder.text(autoFill: autoFill))
                    .accessibilityIdentifier("titleField")
            }
            if asksPeople {
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(verbatim: loc("People in the room"))
                        .font(blueprint.fonts.label)
                        .tracking(0.6)
                        .foregroundStyle(blueprint.palette.textMuted)
                    // docs/09 Fluid typography: six chips across a phone is a row at the design's own type
                    // size and several rows at the user's.
                    FlowLayout {
                        ForEach(choices, id: \.self) { choice in
                            BlueprintChip(label(choice), selected: participants == choice) {
                                participants = choice
                            }
                        }
                    }
                    .accessibilityIdentifier("participants")
                }
            }
            // docs/03 "Titles" · 2026-10-08 §5: the second answer throws the recording away, so it
            // says so, in the red of every other irreversible delete. Side by side while both fit;
            // stacked full width when they do not (docs/09 screen principle 8).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Space.s) {
                    Spacer(minLength: 0)
                    answers
                }
                VStack(spacing: Space.s) {
                    answers
                }
                .environment(\.blueprintButtonFillsWidth, true)
            }
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .top)
    }

    @ViewBuilder private var answers: some View {
        BlueprintButton(loc("Discard recording"), tone: .danger, minWidth: minTouch) { onCancel() }
            .accessibilityIdentifier("discardRecording")
        BlueprintButton(loc("Save"), tone: .primary, minWidth: 120) { onSave() }
            .accessibilityIdentifier("saveTitle")
    }

    /// docs/07 rule 4: a count is a number, not a sentence — only "unknown" and "6+" are words.
    private func label(_ choice: Int?) -> String {
        switch choice {
        case .none: return loc("Unknown")
        case .some(6): return loc("6+")
        case .some(let count): return String(count)
        }
    }
}
