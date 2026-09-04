import Foundation
import ReclyCore

/// docs/07 §5: the core says things in keys, the shell says them in words. This is the whole of
/// that translation on Apple — every `step_run.last_error`, every `AuthRequiredException` message
/// and every sign-in reason goes through [CoreMessages.text].
public enum CoreMessages {

    /// One core message, as the screen shows it: a sentence, and under it the diagnostic the core
    /// attached — a response body, a parser complaint — which is never translated.
    public struct Text: Equatable, Sendable {
        public let sentence: String
        public let detail: String?
    }

    /// docs/07 §5 compatibility: a `last_error` an older build wrote is a sentence, not a key, and
    /// is shown exactly as it was stored rather than replaced with a guess.
    public static func text(_ code: String) -> Text {
        guard let ref = CoreMessageRef.companion.parse(code: code) else {
            return Text(sentence: code, detail: nil)
        }
        return Text(sentence: sentence(ref.message, arg: ref.arg), detail: ref.detail)
    }

    public static func sentence(_ message: CoreMessage, arg: String? = nil) -> String {
        let key = key(for: message)
        guard takesArgument(message) else { return RecKitStrings.localized(key) }
        // The argument of this one key is itself a code — the failure that spent the last attempt —
        // so it is translated in turn and nested inside this sentence.
        let inner = message == .retryBudgetSpent ? text(arg ?? "").sentence : (arg ?? "")
        return RecKitStrings.localized(key, inner)
    }

    /// Exhaustive by construction: a new key does not compile until it has a sentence.
    static func key(for message: CoreMessage) -> String {
        switch message {
        case .needsAuth: return "Sign in again to carry on"
        case .driveReauth: return "Google Drive access has to be allowed again"
        case .driveConsentRequired: return "Drive access needs your consent"
        case .driveStorageFull: return "Google Drive is out of space — free some up and try again"
        case .signInCancelled: return "The sign-in was cancelled"
        case .missingSecret: return "This device has no value for the secret ‘%@’"
        case .invalidSecret: return "The value stored for the secret ‘%@’ is not a usable key"
        case .webhookHttp: return "The webhook answered HTTP %@"
        case .folderTemplate: return "Folder template: %@"
        case .retryBudgetSpent: return "Out of retries: %@"
        case .noRunner: return "This app cannot run a ‘%@’ step"
        case .stepMissing: return "This job’s workflow has no step ‘%@’"
        case .unsupportedStep: return "This job needs a newer version of the app: it uses a ‘%@’ step"
        case .stepFailed: return "Failed: %@"
        // docs/08 "오류": what to do about it is the whole sentence, and the provider's own line is
        // the code's detail — shown under it, never inside it.
        case .authRejected: return "The provider rejected the key."
        case .quota: return "The provider is out of quota or is rate-limiting. It will try again."
        case .providerError: return "Something went wrong at the provider. It will try again."
        case .unsupportedAudio: return "The provider would not accept this audio."
        case .noInputTrack: return "This recording has no mono or mix track to transcribe."
        case .resultTimeout: return "The provider did not finish in time. It will submit again."
        case .stale: return "The document changed while this was open"
        case .unsupportedSchema: return "Unsupported schema %@"
        }
    }

    /// The keys whose sentence has a `%@` in it; the rest are looked up without one.
    static func takesArgument(_ message: CoreMessage) -> Bool {
        switch message {
        case .needsAuth, .driveReauth, .driveConsentRequired, .driveStorageFull, .signInCancelled,
             .stale, .authRejected, .quota, .providerError, .unsupportedAudio,
             .noInputTrack, .resultTimeout:
            return false
        default:
            return true
        }
    }
}
