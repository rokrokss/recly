#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 screen principle 5 · docs/08 "Titles and speaker names": the title sheet's ChatGPT box, under its fields,
/// with the note that says what is sent, when, and that it counts toward the plan — the user's consent for this one
/// recording. The shell shows it only while ChatGPT is signed in and transcription is on.
public struct FillAfterStopRow: View {
    @Binding private var isOn: Bool
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(isOn: Binding<Bool>) {
        _isOn = isOn
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            BlueprintCheckRow(RecKitStrings.localized("Fill in the title and speaker names with ChatGPT"), isOn: $isOn)
                .accessibilityIdentifier("fill-after-stop")
            Text(verbatim: RecKitStrings.localized("Once transcribed, the transcript goes to ChatGPT for a missing title and the names of speakers called by name. It counts toward your ChatGPT usage."))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The box as the ChatGPT model shows it ([ChatGptSettingsModel.fillTicked]: left ticked, and allowed now), for a
/// sheet that stays up while the app runs — the iPhone's. Ticking it where the destination needs the user's
/// permission asks for it first, in the dialog Summarize asks with (docs/15 "iPhone providers"); declined, the box
/// stays off. Nothing at all while ChatGPT is not signed in.
public struct FillAfterStopChoice: View {
    @ObservedObject private var model: ChatGptSettingsModel
    /// The permission dialog's "I turned off …" answer.
    @State private var trainingOff = false

    public init(model: ChatGptSettingsModel) {
        self.model = model
    }

    public var body: some View {
        if model.fillOffered {
            FillAfterStopRow(isOn: Binding(get: { model.fillTicked }, set: model.setFillAfterStop))
                // A sheet of its own over the title sheet, sized to the disclosure: a swipe down is a Cancel.
                .blueprintDialog(isPresented: Binding(get: { !model.fillConsent.isEmpty }, set: { if !$0 { answer(false) } })) {
                    SummaryConsentDialog(targets: model.fillConsent, trainingOff: $trainingOff, answer: answer)
                }
        }
    }

    private func answer(_ allow: Bool) {
        trainingOff = false
        Task { await model.answerFillConsent(allow: allow) }
    }
}
#endif
