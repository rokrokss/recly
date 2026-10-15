import ReclyCore

/// docs/09 screen principle 1: what the line under the record control says once a stop has been saved — the
/// queue's answer in the words of the person who just stopped a recording (Android `EnqueueResult.describe`,
/// the same four sentences on every shell). A recording queued as asked is not news: nil, and the line goes back
/// to waiting.
public enum StopLine {
    public static func message(_ result: any EnqueueResult) -> UiMessage? {
        switch onEnum(of: result) {
        case .noWorkflow: return .key("Review recording settings to continue")
        case .partsPurged: return .key("The audio has already been cleaned up")
        case .skippedShort: return .key("Too short — skipped")
        case .alreadySynced, .alreadyDone: return .key("Already handled")
        case .enqueued: return nil
        }
    }

    /// The enqueue threw: nothing will upload this recording until recovery files it again.
    public static let enqueueFailed = UiMessage.key("Could not create the job")

    /// docs/03 "Titles": `updateTitle` refused — the job had already read the meta, so the name the user typed is
    /// not the recording's. Said over the queue's own answer, which is the less surprising of the two.
    public static let titleTooLate = UiMessage.key("Too late to save the title — the upload has started")

    /// A capture that ended without the user stopping it: queued untitled, with no title asked, and said as the failure it was —
    /// over whatever the queue answered, which is the less important of the two.
    public static let recordingFailed = UiMessage.key("The recording failed")
}
