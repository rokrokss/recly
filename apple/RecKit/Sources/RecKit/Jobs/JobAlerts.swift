import Foundation
import ReclyCore

/// docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the failures a person has to do something about,
/// and the screen that lets them do it. Everything else — 5xx, the network, a 429 the runner is
/// still waiting out — is a retry the app does not call anybody about.
///
/// The reasons are per *job*, not per step: a job the queue has stopped carrying is what the user
/// counts, and the same reason on five jobs is one line and one notification with a count on it.
///
/// The Android shell's `JobAlerts.kt` is the same file; the two are held together by having the
/// same tests over the same rules rather than by sharing code the core does not own.
public enum AlertReason: String, CaseIterable, Sendable {
    case localUnavailable
    case localModel
    case localDiarization
    case needsAuth
    case needsConsent
    case needsSpace
    case missingSecret
    case authRejected
    case quota

    /// docs/07 rule 3: the key, resolved where the banner or the notification draws it.
    ///
    /// Namespaced rather than written as its own English sentence, which is this catalog's usual
    /// shape: docs/10's line for `AUTH_REJECTED` says the same thing as `CoreMessage.AUTH_REJECTED`
    /// down to the full stop, and two keys that differ only in punctuation generate one symbol.
    public var labelKey: String {
        "alert." + rawValue
    }

    public var label: String { RecKitStrings.localized(labelKey) }

    /// docs/09 화면 원칙 2: the badge on the banner row is the state as a code, the same word the
    /// core and the logs use.
    public var code: String {
        switch self {
        case .localUnavailable: return "LOCAL_TRANSCRIPTION_UNAVAILABLE"
        case .localModel: return "LOCAL_MODEL_REQUIRED"
        case .localDiarization: return "LOCAL_DIARIZATION_UNAVAILABLE"
        case .needsConsent: return "NEEDS_CONSENT"
        case .needsAuth: return "NEEDS_AUTH"
        case .needsSpace: return "NEEDS_SPACE"
        case .missingSecret: return "MISSING_SECRET"
        case .authRejected: return "AUTH_REJECTED"
        case .quota: return "QUOTA"
        }
    }

    public var fix: FixSurface {
        switch self {
        case .needsConsent: return .privacy
        case .needsAuth: return .signIn
        case .needsSpace: return .driveStorage
        case .missingSecret, .authRejected: return .secrets
        case .quota, .localUnavailable, .localModel, .localDiarization: return .editor
        }
    }
}

/// Where the fix is. docs/10: "탭하면 고칠 수 있는 화면으로 간다 — 로그인 화면, 시크릿 폼, 워크플로우
/// 편집기. '앱 열기'로 끝내지 않는다." [driveStorage] is the one that leaves the app, because the
/// space is Google's to give back.
public enum FixSurface: CaseIterable, Sendable {
    case privacy
    case signIn
    case driveStorage
    case secrets
    case editor

    /// docs/07 rule 3: the key, resolved where the banner draws its button.
    ///
    /// The surface names itself — "Sign in", "Open Drive storage" — because docs/10's rule is that
    /// the fix is a screen and not "open the app", and a button that does not say which screen it
    /// opens is the same promise with the answer left out. The Windows banner labels its button
    /// with exactly these four words.
    public var labelKey: String {
        switch self {
        case .privacy: return "Review transfers"
        case .signIn: return "Sign in"
        case .driveStorage: return "Open Drive storage"
        case .secrets: return "Check the key"
        case .editor: return "Recording processing"
        }
    }

    public var label: String { RecKitStrings.localized(labelKey) }
}

/// docs/10 "Drive 용량 초과": where "free some up" actually happens.
public let driveStorageURL = URL(string: "https://drive.google.com/settings/storage")!

/// One reason and how many jobs are stuck on it — the banner line, and the notification body.
public struct JobAlert: Identifiable, Equatable, Sendable {
    public let reason: AlertReason
    public let count: Int
    /// docs/10: the `secretRef` the blocking step named, so [FixSurface.secrets] opens the form
    /// already filled in with the key that is missing rather than an empty one. Nil for
    /// `AUTH_REJECTED`, which is a provider refusing a value and never says which key held it.
    public let secret: String?
    /// The step that stopped the job, so the form opens *under that step* in the editor rather
    /// than in the workflow's secret list.
    public let stepId: String?

    public var id: String { reason.rawValue }

    public init(
        reason: AlertReason,
        count: Int,
        secret: String? = nil,
        stepId: String? = nil
    ) {
        self.reason = reason
        self.count = count
        self.secret = secret
        self.stepId = stepId
    }

    /// "3 recordings are waiting." — the count is the whole of what the second line says.
    public var waiting: String {
        RecKitStrings.localized("alert.waiting", String(count))
    }
}

/// One job's side of the fold: why it is stuck, and — for the failures a key holds up — the secret
/// and the step the form has to open on.
public struct AlertSource: Equatable, Sendable {
    public let reason: AlertReason?
    public let secret: String?
    public let stepId: String?

    public init(
        reason: AlertReason?,
        secret: String? = nil,
        stepId: String? = nil
    ) {
        self.reason = reason
        self.secret = secret
        self.stepId = stepId
    }
}

/// One row of the queue as the fold reads it: what `jobs.list()` says about the job, and the step
/// rows that say why it is stuck.
///
/// A shape of its own rather than the core's `Job` because that is what makes the rule testable —
/// building a `Job` needs a whole parsed `Workflow` and two `Instant`s, none of which the fold has
/// an opinion about.
public struct QueuedJob {
    public let status: JobStatus
    public let steps: [StepRun]

    public init(status: JobStatus, steps: [StepRun]) {
        self.status = status
        self.steps = steps
    }
}

public enum JobAlerts {

    /// The reason a job is stuck, or nil when nothing about it is the user's to fix.
    ///
    /// [lastError] is the `step_run.last_error` of the step holding the job up — a `CoreMessage`
    /// code (docs/07 §5), or a sentence an older build wrote, which parses to nothing and so alerts
    /// nothing.
    public static func reason(status: JobStatus, lastError: String?) -> AlertReason? {
        switch status {
        case .needsConsent: return .needsConsent
        case .needsAuth: return .needsAuth
        case .needsSpace: return .needsSpace
        // Only a job the queue has given up on. A step that is still inside its retry budget is
        // `WAITING`, and docs/10 says plainly that those are not worth a notification.
        case .failed: return terminalReason(lastError)
        default: return nil
        }
    }

    private static func terminalReason(_ lastError: String?) -> AlertReason? {
        guard let lastError, let ref = CoreMessageRef.companion.parse(code: lastError) else { return nil }
        switch ref.message {
        case .localTranscriptionUnavailable: return .localUnavailable
        case .localModelRequired: return .localModel
        case .localDiarizationUnavailable: return .localDiarization
        case .missingSecret: return .missingSecret
        case .authRejected: return .authRejected
        case .quota: return .quota
        // docs/10: a spent budget is the user's problem only when what spent it was the provider's
        // quota. Anything else ran out of attempts against something a retry could have fixed.
        case .retryBudgetSpent: return spentOn(ref) == .quota ? .quota : nil
        default: return nil
        }
    }

    /// The code of the failure that spent the last attempt (`Executor` nests it as the argument).
    private static func spentOn(_ ref: CoreMessageRef) -> CoreMessage? {
        guard let arg = ref.arg else { return nil }
        return CoreMessageRef.companion.parse(code: arg)?.message
    }

    /// The step that stopped the job, and failing that the last complaint anything made — the
    /// `last_error` both the list row and [reason(status:lastError:)] read.
    ///
    /// A step with `onError: continue` fails and the job carries on, so the *first* failed step is
    /// not the one that ended the job: the one that did is the last failure with nothing successful
    /// after it. The core keeps no job-level reason — `Job` has no `lastError` column — so it has
    /// to be read back off the step rows in ordinal order.
    public static func blockingError(steps: [StepRun]) -> String? {
        blockingStep(steps: steps)?.lastError
    }

    /// The step [blockingError] read the code off, so the secret form can open where the failure
    /// actually is — docs/10's fix screen for a key is the step's own form, not the workflow's
    /// list of keys.
    public static func blockingStep(steps: [StepRun]) -> StepRun? {
        let ordered = steps.sorted { $0.ordinal < $1.ordinal }
        let lastSuccess = ordered.lastIndex { $0.status == .succeeded } ?? -1
        let holdingUp = ordered.enumerated()
            .filter { $0.offset > lastSuccess && Self.holdingUp.contains($0.element.status) }
            .last?
            .element
        // A step that is holding the job up without having said why leaves the last complaint
        // anything made as all there is to report — and then that is the step the fix is about.
        if let holdingUp, holdingUp.lastError != nil { return holdingUp }
        return ordered.last { $0.lastError != nil } ?? holdingUp
    }

    private static let holdingUp: Set<StepStatus> = [.failed, .needsAuth, .needsSpace, .needsConsent]

    /// One job of the queue, folded down to what the banner and the notification need of it.
    public static func source(status: JobStatus, steps: [StepRun]) -> AlertSource {
        let blocking = blockingStep(steps: steps)
        let reason = reason(status: status, lastError: blocking?.lastError)
        return AlertSource(
            reason: reason,
            secret: reason == nil ? nil : secretName(blocking?.lastError),
            stepId: reason == nil ? nil : blocking?.stepId
        )
    }

    /// The whole queue, as [fold] takes it.
    public static func sources(_ jobs: [QueuedJob]) -> [AlertSource] {
        jobs.map { source(status: $0.status, steps: $0.steps) }
    }

    /// The same, read off the core: **every** job it is carrying, not the newest five recordings'.
    ///
    /// docs/10 rule 3 counts the queue, and the ledger is not the queue — it is the newest five
    /// *recordings* with the newest job of each. A job blocked on a missing key three recordings
    /// ago is still blocked, and folding the ledger instead would take its notification down the
    /// moment somebody recorded five more things.
    public static func sources(core: ReclyCore_) async throws -> [AlertSource] {
        let jobs = try await core.jobs.list().map { (id: $0.id, status: $0.status) }
        return try await sources(jobs: jobs) { try await core.jobs.steps(jobId: $0) }
    }

    /// The same fold, over a queue whose step rows still have to be read one job at a time.
    ///
    /// A read that fails takes the whole snapshot with it rather than folding that job as "no
    /// steps": every reason a `FAILED` job carries is read off its steps, so a job whose rows could
    /// not be read would fold to *no reason at all* — indistinguishable from one that has come
    /// unstuck. `publishAlerts` would then withdraw a notification for a job that is still blocked
    /// and empty the banner. Failing instead leaves the last reading standing, and the next runner
    /// pass reads the queue again.
    static func sources(
        jobs: [(id: String, status: JobStatus)],
        steps: (String) async throws -> [StepRun]
    ) async throws -> [AlertSource] {
        var sources: [AlertSource] = []
        for job in jobs {
            sources.append(
                source(status: job.status, steps: try await steps(job.id))
            )
        }
        return sources
    }

    /// The `secretRef` a code names, for the form the fix opens. Only the one that carries one:
    /// `CoreMessageRef.parse` refuses it unless the argument really is a `secretRef` (docs/02),
    /// so what comes back here is a name the settings can look up.
    private static func secretName(_ lastError: String?) -> String? {
        guard let lastError, let ref = CoreMessageRef.companion.parse(code: lastError) else { return nil }
        switch ref.message {
        case .missingSecret: return ref.arg
        default: return nil
        }
    }

    /// The reasons across the whole queue, folded one entry per reason, in [AlertReason] order.
    public static func fold(_ sources: [AlertSource]) -> [JobAlert] {
        AlertReason.allCases.compactMap { reason in
            let affected = sources.filter { $0.reason == reason }
            guard !affected.isEmpty else { return nil }
            // The fix opens on one job's step, so the secret and the step it is entered under come
            // from the *same* source — the first that names a step — rather than from whichever
            // job happened to name each of them first.
            let fixOn = affected.first { $0.stepId != nil } ?? affected.first
            return JobAlert(
                reason: reason,
                count: affected.count,
                secret: fixOn?.secret,
                stepId: fixOn?.stepId
            )
        }
    }
}
