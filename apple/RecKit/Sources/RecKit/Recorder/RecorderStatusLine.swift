import Foundation

/// docs/09: the one line the three shells put under the record button — what the recorder is doing,
/// and, when it is doing nothing, whatever the shell last had to say.
///
/// The state has the say while a recording is in flight; [note] is what is left to show when there
/// is none. The Mac and the phone have one thing more: a message — on the Mac from a delete or a
/// disconnect, on the phone a start the disconnect refused — which outranks the note for as long
/// as it stands.
///
/// docs/07 rule 3: [note] is a *key* the model kept rather than a sentence it resolved, so the line
/// follows a language change where it stands. It is resolved through [AppStrings] and not through
/// RecKit's own catalog because it is the shell's key — "Waiting" is written in three catalogs
/// because three apps say it — and a key no catalog knows comes back unchanged. The recorder's own
/// state words are RecKit's — `Starting` · `Recording` · `Saving`, the phones' words on every Apple
/// shell — so they are one set of words rather than three.
public enum RecorderStatusLine {

    /// - Parameter message: the shell's [UiMessage] slot, which outranks the note while the recorder is idle.
    public static func text(
        state: RecorderState,
        note: String,
        message: UiMessage? = nil
    ) -> String {
        switch state {
        case .idle:
            if let message { return message.text }
            return AppStrings.localized(note)
        case .starting: return RecKitStrings.localized("Starting")
        case .recording: return RecKitStrings.localized("Recording")
        case .stopping: return RecKitStrings.localized("Saving")
        }
    }
}
