import Foundation
import ReclyCore

/// The words the ChatGPT section, the notes and the answers make out of a [CoreMessage] code.
public enum ChatGptText {
    /// The codes whose sentence is the whole answer, because it says what the user can do (docs/09
    /// "AI view").
    static let ownSentence: Set<CoreMessage> = [
        .chatgptSignInRequired, .chatgptUsageLimit, .chatgptPlanRequired, .providerRegionRestricted,
    ]

    public static func message(_ reason: String) -> CoreMessage? {
        CoreMessageRef.companion.parse(code: reason)?.message
    }

    /// Whether [reason] is the user's own cancel, which nothing reports.
    public static func cancelled(_ reason: String) -> Bool { message(reason) == .signInCancelled }

    /// The notice of a failed Organize or answer: the sentence of a code the user can act on, else nil (the screen
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

    /// A model as the plan names it, else its id.
    public static func modelLabel(_ id: String, connection: ChatGptConnection) -> String {
        (connection as? ChatGptConnection.SignedIn)?.models.first { $0.id == id }?.label ?? id
    }

    /// docs/09 "AI view": the line under a recording's notes, `ChatGPT · <model>` — the plan's name for the model they were
    /// made with as the core keeps it (`GPT-5.4`), which outlives the plan's list, else what the list calls it now.
    /// Nothing says the notes were edited.
    public static func notesFooter(_ model: String, connection: ChatGptConnection) -> String {
        RecKitStrings.localized("ChatGPT · %@", modelLabel(model, connection: connection))
    }

    /// docs/09 "Ask": a preset's chip. Translate names the app's language as the App language list does.
    public static func presetLabel(_ preset: AskPreset) -> String {
        switch preset {
        case .followUpEmail: return RecKitStrings.localized("Follow-up email")
        case .actionItems: return RecKitStrings.localized("Action items")
        case .openQuestions: return RecKitStrings.localized("Open questions")
        case .translate:
            return RecKitStrings.localized("Translate to %@", RecKitStrings.localized("language." + AppLanguage.resolvedCode))
        case .mySpeaking: return RecKitStrings.localized("Feedback on how I spoke")
        }
    }

    /// The text of an answer cut at its citations (docs/09 "AI view"): plain runs, and the
    /// `[HH:MM:SS]` ranges `SummaryCitations` finds, each with the second it plays from. The core counts in
    /// UTF-16 code units, as Kotlin strings do; a range that does not fall on the text is left as text.
    public static func runs(_ text: String) -> [CitationRun] {
        let utf16 = text.utf16
        var runs: [CitationRun] = []
        var cursor = 0
        func slice(_ from: Int, _ to: Int) -> String {
            String(text[String.Index(utf16Offset: from, in: text) ..< String.Index(utf16Offset: to, in: text)])
        }
        for citation in SummaryCitations.shared.parse(text: text) {
            let start = Int(citation.offset)
            let end = start + Int(citation.length)
            guard start >= cursor, end <= utf16.count, citation.length > 0 else { continue }
            if start > cursor { runs.append(.text(slice(cursor, start))) }
            runs.append(.citation(slice(start, end), atSec: citation.atSec))
            cursor = end
        }
        if cursor < utf16.count { runs.append(.text(slice(cursor, utf16.count))) }
        return runs
    }

    /// `Play from 00:12:34` — the citation without its brackets.
    public static func playFrom(_ citation: String) -> String {
        RecKitStrings.localized("Play from %@", citation.trimmingCharacters(in: CharacterSet(charactersIn: "[]")))
    }
}

/// One piece of an answer's text: words, or a citation that plays the recording from [atSec].
public enum CitationRun: Equatable, Sendable {
    case text(String)
    case citation(String, atSec: Double)
}
