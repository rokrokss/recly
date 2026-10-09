import ReclyCore

/// The words the ChatGPT section and the summary view make out of a [CoreMessage] code.
public enum ChatGptText {
    /// The codes whose sentence is the whole answer, because it says what the user can do (docs/09
    /// "Summary view").
    static let ownSentence: Set<CoreMessage> = [
        .chatgptSignInRequired, .chatgptUsageLimit, .chatgptPlanRequired, .providerRegionRestricted,
    ]

    public static func message(_ reason: String) -> CoreMessage? {
        CoreMessageRef.companion.parse(code: reason)?.message
    }

    /// Whether [reason] is the user's own cancel, which nothing reports.
    public static func cancelled(_ reason: String) -> Bool { message(reason) == .signInCancelled }

    /// The notice of a failed summary: the sentence of a code the user can act on, else nil (the screen
    /// says "Could not summarize").
    public static func sentence(_ reason: String) -> String? {
        guard let message = message(reason), ownSentence.contains(message) else { return nil }
        return CoreMessages.text(reason).sentence
    }

    /// The second line under "Could not sign in to ChatGPT" / "Could not summarize": what the core
    /// attached — the provider's code, the reason — or, for a code with a sentence of its own, that.
    public static func detail(_ reason: String) -> String? {
        guard let ref = CoreMessageRef.companion.parse(code: reason) else { return reason }
        if ownSentence.contains(ref.message) { return CoreMessages.text(reason).sentence }
        return ref.detail ?? ref.arg
    }

    /// A summary's model as the plan names it, else its id.
    public static func modelLabel(_ id: String, connection: ChatGptConnection) -> String {
        (connection as? ChatGptConnection.SignedIn)?.models.first { $0.id == id }?.label ?? id
    }

    /// The line under a summary: `ChatGPT · <model>`, and `· Edited` once the user changed it.
    public static func summaryFooter(_ summary: Summary, connection: ChatGptConnection) -> String {
        let made = RecKitStrings.localized("ChatGPT · %@", modelLabel(summary.model, connection: connection))
        return summary.editedAt == nil ? made : "\(made) · \(RecKitStrings.localized("Edited"))"
    }
}
