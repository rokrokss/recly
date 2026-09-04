import Foundation

/// docs/07 rule 3: a message a model holds on to — a banner, the note an import left behind — as what
/// it *is* rather than as words.
///
/// Prose resolved when the model was written would outlive the language change the screen is meant
/// to answer: the banner is still standing, and it is still in the old language. So the model keeps
/// the key and its arguments, and [text] makes the sentence where the view draws it.
public enum UiMessage: Equatable, Sendable {
    /// A key in RecKit's own catalog, with the arguments the sentence takes. An argument is usually
    /// nothing to translate — a count, a secret name, a status, all [verbatim] — but it may itself
    /// be a message, which is how a core code becomes a sentence *inside* this one rather than a
    /// code left standing in the middle of it.
    case key(String, args: [UiMessage] = [])
    /// A core `last_error` / `CoreMessage` code (docs/07 §5), which [CoreMessages] turns into a
    /// sentence — and which an older build may have written as prose, passed through unchanged.
    case core(String)
    /// Text that has no translation at all: the parser's own list of complaints, a name the user
    /// typed.
    case verbatim(String)

    /// The sentence, in the app's language *now*.
    public var text: String {
        switch self {
        case .key(let key, let args):
            guard !args.isEmpty else { return RecKitStrings.localized(key) }
            return String(
                format: RecKitStrings.localized(key),
                locale: AppLanguage.locale,
                arguments: args.map(\.text) as [CVarArg]
            )
        case .core(let code):
            return CoreMessages.text(code).sentence
        case .verbatim(let text):
            return text
        }
    }

    /// docs/07 rule 4: logs are not localized. What identifies a message in a log is what it was
    /// stored as — the key, or the core's code — never [text], which is whichever language the
    /// device happened to be in when the line was written and changes under a reader.
    public var logCode: String {
        switch self {
        case .key(let key, _): return key
        case .core(let code): return code
        case .verbatim: return "verbatim"
        }
    }
}
