#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/08 "Titles and speaker names": what the title sheet's `Save` asks ChatGPT for this recording — the boxes as they
/// show, the title's only while the title field is empty, since a typed title is never replaced. The shell calls
/// `requestFill(recordingId, title, names)` only when [any].
public struct TitleSheetFill: Equatable, Sendable {
    public let title: Bool
    public let names: Bool

    /// - Parameters:
    ///   - titleTicked: `Fill in the title with ChatGPT` shows ticked.
    ///   - namesTicked: `Fill in speaker names with ChatGPT` shows ticked.
    ///   - typedTitle: the title field as Save found it.
    public init(titleTicked: Bool, namesTicked: Bool, typedTitle: String?) {
        title = titleTicked && Self.titleBoxEnabled(typedTitle: typedTitle)
        names = namesTicked
    }

    public static let none = TitleSheetFill(titleTicked: false, namesTicked: false, typedTitle: nil)

    public var any: Bool { title || names }

    /// The title's box would do nothing while the field has a title in it: it is off then, and keeps how it was left.
    /// Spaces alone are no title — the sheet saves none for them.
    public static func titleBoxEnabled(typedTitle: String?) -> Bool {
        (typedTitle ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// docs/09 screen principle 5 · docs/08 "Titles and speaker names": the title sheet's two ChatGPT boxes, under its
/// fields — `Fill in the title with ChatGPT` and `Fill in speaker names with ChatGPT` — with one note under both that
/// says what is sent, when, and that it counts toward the plan: the user's consent for this one recording and that
/// half. The shell shows them only while ChatGPT is signed in and transcription is on.
public struct FillAfterStopRow: View {
    @Binding private var title: Bool
    @Binding private var names: Bool
    private let typedTitle: String
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    /// - Parameter typedTitle: the sheet's title field — while it has a title, the title's box is off and keeps its tick.
    public init(title: Binding<Bool>, names: Binding<Bool>, typedTitle: String) {
        _title = title
        _names = names
        self.typedTitle = typedTitle
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            BlueprintCheckRow(RecKitStrings.localized("Fill in the title with ChatGPT"), isOn: $title)
                .disabled(!TitleSheetFill.titleBoxEnabled(typedTitle: typedTitle))
                .accessibilityIdentifier("fill-title-after-stop")
            BlueprintCheckRow(RecKitStrings.localized("Fill in speaker names with ChatGPT"), isOn: $names)
                .accessibilityIdentifier("fill-names-after-stop")
            Text(verbatim: RecKitStrings.localized("Once transcribed, the transcript goes to ChatGPT for a missing title and the names of speakers called by name. It counts toward your ChatGPT usage."))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The boxes as the ChatGPT model shows them ([ChatGptSettingsModel.fillTitleTicked], `fillNamesTicked`: left ticked,
/// and allowed now), for a sheet that stays up while the app runs — the iPhone's. Ticking either where the destination
/// needs the user's permission asks for it first, in the dialog Summarize asks with (docs/15 "iPhone providers");
/// declined, the box stays off. Nothing at all while ChatGPT is not signed in.
public struct FillAfterStopChoice: View {
    @ObservedObject private var model: ChatGptSettingsModel
    private let typedTitle: String
    /// The permission dialog's "I turned off …" answer.
    @State private var trainingOff = false

    public init(model: ChatGptSettingsModel, typedTitle: String) {
        self.model = model
        self.typedTitle = typedTitle
    }

    public var body: some View {
        if model.fillOffered {
            FillAfterStopRow(
                title: Binding(get: { model.fillTitleTicked }, set: model.setFillTitleAfterStop),
                names: Binding(get: { model.fillNamesTicked }, set: model.setFillNamesAfterStop),
                typedTitle: typedTitle
            )
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
