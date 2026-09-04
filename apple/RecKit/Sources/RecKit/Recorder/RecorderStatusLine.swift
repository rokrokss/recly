import Foundation

/// docs/09: the one line the three shells put under the record button — what the recorder is doing,
/// and, when it is doing nothing, whatever the shell last had to say.
///
/// The state has the say while a recording is in flight; [note] is what is left to show when there
/// is none. The Mac has one thing more: a message from a delete or a disconnect, which outranks
/// the note for as long as it stands.
///
/// docs/07 rule 3: [note] is a *key* the model kept rather than a sentence it resolved, so the line
/// follows a language change where it stands. It is resolved through [AppStrings] and not through
/// RecKit's own catalog because it is the shell's key — "Waiting" is written in three catalogs
/// because three apps say it — and a key no catalog knows comes back unchanged.
public enum RecorderStatusLine {

    /// - Parameters:
    ///   - count: the argument of the one note that takes one (`Deferred %@`).
    ///   - message: the Mac's [UiMessage] slot, which outranks the note while the recorder is idle.
    public static func text(
        state: RecorderState,
        note: String,
        count: Int? = nil,
        message: UiMessage? = nil
    ) -> String {
        switch state {
        case .idle:
            if let message { return message.text }
            guard let count else { return AppStrings.localized(note) }
            return AppStrings.localized(note, "\(count)")
        case .starting: return AppStrings.localized("Opening")
        case .recording: return AppStrings.localized("Recording")
        case .stopping: return AppStrings.localized("Saving")
        }
    }
}
