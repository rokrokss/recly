#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/08 "Organizing": OpenAI's terms ask for express consent before Recly uses the ChatGPT plan in the background.
/// The question is asked right after a sign-in, and — for someone signed in before it existed — at a recording started
/// from the app's own screen. A start with no screen of its own (Siri, a widget, the Control, the watch) never asks: the
/// core simply organizes nothing until there is an answer.
public enum OrganizeConsent {
    public enum Answer: Equatable, Sendable {
        /// `Continue`: organize.
        case agree
        /// `Sign out`: the existing ChatGPT sign-out.
        case signOut
    }

    /// Whether a start asks first: one from the app's own screen, while the core says the user has not answered
    /// (`notes.needsConsent()`). The recording starts whatever the answer.
    public static func asksAtStart(fromScreen: Bool, needsConsent: Bool) -> Bool {
        fromScreen && needsConsent
    }

    /// docs/15 "iPhone providers": the destination the sheet discloses — the ChatGPT one Continue allows, while it is not
    /// allowed yet (the iPhone), with the same disclosure and training question as the summary's permission dialog.
    /// Allowed already, or with no permission to ask for (the Mac), none: the sheet is the consent alone.
    public static func permission(destinationAllowed: Bool) -> [TransferTarget] {
        destinationAllowed ? [] : [TransferTargets.shared.chatGptSummary()]
    }

    /// What Continue answers with the box as it is: nothing while the disclosed destination waits for the training box
    /// ([TrainingOptOut]), as the permission dialog's own allow button does.
    public static func continued(permission: [TransferTarget], trainingOff: Bool) -> Answer? {
        TrainingOptOut.required(permission) && !trainingOff ? nil : .agree
    }

    /// What an answer does. Continue agrees (`notes.consent()`) and, where the plan needs the user's permission as a
    /// destination — the iPhone ([allowDestination]) — gives it in the same action, since without it nothing is sent.
    /// Sign out signs out of ChatGPT, which takes any consent with it.
    ///
    /// - Returns: false when the agreement could not be written; the core then asks again at the next start.
    @discardableResult
    public static func apply(
        _ answer: Answer,
        consent: () async throws -> Void,
        allowDestination: (() async throws -> Void)?,
        signOut: () -> Void
    ) async -> Bool {
        switch answer {
        case .signOut:
            signOut()
            return true
        case .agree:
            do {
                try await consent()
                try await allowDestination?()
                return true
            } catch {
                return false
            }
        }
    }
}

/// docs/08 "Organizing": the consent sheet — what is sent, when, and that it counts toward the plan — with
/// `Sign out` and `Continue`. The shells present it where their other questions go: the iPhone's in the screen, the
/// Mac's as a panel at a start and over the surface the sign-in was started from. [permission]
/// ([OrganizeConsent.permission]) adds that destination's disclosure — the summary permission dialog's own — and Continue
/// waits for its box.
public struct OrganizeConsentDialog: View {
    private let permission: [TransferTarget]
    private let answer: (OrganizeConsent.Answer) -> Void
    /// The disclosure's "I turned off …" answer.
    @State private var trainingOff = false
    @Environment(\.locale) private var locale

    public init(permission: [TransferTarget], answer: @escaping (OrganizeConsent.Answer) -> Void) {
        self.permission = permission
        self.answer = answer
    }

    public var body: some View {
        let agreed = OrganizeConsent.continued(permission: permission, trainingOff: trainingOff)
        BlueprintDialog(title: loc("Recly organizes while you record")) {
            BlueprintButton(loc("Sign out"), tone: .quiet) { answer(.signOut) }
                .accessibilityIdentifier("organize-sign-out")
            BlueprintButton(loc("Continue"), tone: .primary) { if let agreed { answer(agreed) } }
                .disabled(agreed == nil)
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("organize-continue")
        } content: {
            BlueprintDialogText(loc("While you're signed in to ChatGPT, Recly sends each recording's transcript text to your ChatGPT plan as you record and after you stop, and lists its decisions, to-dos and questions. Audio is never sent. This counts toward your plan."))
                .accessibilityIdentifier("organize-body")
            if !permission.isEmpty {
                TransferDisclosureList(targets: permission, trainingOff: $trainingOff)
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
