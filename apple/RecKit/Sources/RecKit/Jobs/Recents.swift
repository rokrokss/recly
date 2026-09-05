import Foundation
import ReclyCore

/// One recent recording as a shell lists it (docs/12 "menu bar" · docs/13 I3: the state, opening it
/// in Drive, and a retry when it has stopped).
public struct RecentItem: Identifiable, Sendable {
    public let id: String
    public let jobId: String?
    /// The user's own title, empty when they never gave one.
    public let title: String
    public let startedAt: String
    /// A docs/07 key for what the list says about it; [stateLabel] is the word.
    public let state: String
    /// docs/09 화면 원칙 2: the ledger's length column. Nil while the recording is still running —
    /// the meta only learns its length when it is finalized.
    public let durationSec: Double?

    /// Read where it is drawn, so a language change reaches a list that is already on screen — and
    /// so [nextRunAt] is counted down from the moment the row is drawn rather than from the moment
    /// the list was loaded.
    ///
    /// docs/08 "폴링 · 상태": while a provider is transcribing there is no "when" to give, only how
    /// long it has been — and "재시도 대기" would be a different thing to say. A job waiting out a
    /// backoff *does* have a when, and says it: "재시도 대기" on its own reads like "stuck".
    public var stateLabel: String {
        if let waitingMinutes {
            return RecKitStrings.localized(
                "Waiting for the transcription result — %@ min elapsed", String(waitingMinutes)
            )
        }
        if let nextRunAt, state == "Retry pending" {
            return RecKitStrings.localized(
                "Retry pending · %@", Self.remaining(until: nextRunAt, from: Date())
            )
        }
        return RecKitStrings.localized(state)
    }

    /// How much of the backoff is left, in the one unit worth reading. The Android ledger's rule
    /// (`JobsScreen.remaining`) down to its truncation: 90 seconds is "in 1 min", and a time the
    /// queue has not caught up with yet is "soon" rather than a negative number.
    static func remaining(until: Date, from now: Date) -> String {
        let seconds = Int(until.timeIntervalSince(now))
        switch seconds {
        case ..<1: return RecKitStrings.localized("soon")
        case ..<60: return RecKitStrings.localized("in %@ s", String(seconds))
        case ..<3600: return RecKitStrings.localized("in %@ min", String(seconds / 60))
        default: return RecKitStrings.localized("in %@ h", String(seconds / 3600))
        }
    }

    /// The user's own title, or the word for a recording that has none. Never looked up as a
    /// key: a recording someone named "Done" is not a state.
    public var titleLabel: String {
        title.isEmpty ? RecKitStrings.localized("Untitled") : title
    }
    /// The recording's Drive folder: the `drive.upload` step's link, or the folder the row knows
    /// on its own — an adopted recording was read out of that folder (docs/03).
    public let link: URL?
    /// The workflow the job runs, so a key that was refused can be fixed where it is defined.
    public let workflowId: String?
    /// docs/08 "폴링 · 상태": how long the transcription has been in flight, when it is. Nil for
    /// every other state, which has a word of its own.
    public let waitingMinutes: Int?

    /// When the queue comes back to a job that is waiting out a backoff, as the job row keeps it.
    /// Nil for every other state, and for a `WAITING` job the store never dated — [stateLabel] then
    /// says "재시도 대기" and no more, which is all there is to say about it.
    public let nextRunAt: Date?

    /// docs/07 §5: the code the core last wrote for this job — a `CoreMessage` key, or a sentence
    /// an older build stored. Nil while nothing has gone wrong: a job that is simply waiting has
    /// nothing to explain.
    public let lastError: String?

    /// docs/09 화면 원칙 2: the ledger's badge. docs/08 "폴링 · 상태": a job parked while a provider
    /// transcribes is waiting on someone else, not on a retry timer, so it is its own code rather
    /// than the `RETRY` its `WAITING` status would give it — the code the desktop already shows
    /// (`windows/.../Ledger.LedgerStates`).
    public var badge: LedgerStatus {
        waitingMinutes == nil
            ? LedgerStatus.forRecent(state: state)
            : LedgerStatus(code: "TRANSCRIBING", tone: .accent)
    }

    /// The same thing as words, read where it is drawn — so a list already on screen follows a
    /// language change (docs/07 rule 3).
    public var reason: CoreMessages.Text? { lastError.map(CoreMessages.text) }

    /// docs/08 "오류": whether the thing to do about this failure is to look at the key, which is
    /// what decides whether "check the key" is worth offering.
    public var needsKey: Bool { StepReport.shared.needsKey(lastError: lastError) }

    /// Whether a retry is a thing to offer at all: a job that has stopped — `FAILED`, `NEEDS_AUTH`,
    /// `NEEDS_SPACE` — and, since docs/09 화면 원칙 2 (2026-09-04), a `WAITING` one too. That job does
    /// come back on its own `next_run_at`, but a user looking at `RETRY` is looking at it *because*
    /// they are not waiting any longer, and asking now is the only thing the row could offer.
    ///
    /// The one wait that is not that: a provider transcribing ([waitingMinutes]). Nothing this
    /// device could ask for again would make the result arrive sooner. A `PENDING` or `RUNNING` job
    /// is moving, and a recording with no job has nothing to retry.
    ///
    /// Read off [state] rather than the job's status because that is what [Recents.load] keeps —
    /// the same keys [Recents.stateLabel] writes.
    public var canRetry: Bool {
        jobId != nil && waitingMinutes == nil && Self.retryable.contains(state)
    }

    private static let retryable: Set<String> =
        ["Failed", "Sign-in needed", "No space in Drive", "Retry pending"]

    /// docs/09 화면 원칙 2: whether "delete" is a thing to offer. A recording being written to right
    /// now is not one to delete — the core refuses it anyway, and offering the button would be
    /// offering a refusal — and neither is one still arriving from the watch. A recording another
    /// device is uploading is not this device's to delete at all: the folder would go out from
    /// under that upload (docs/03 "다른 기기의 녹음").
    public var canDelete: Bool { !Self.undeletable.contains(state) }

    private static let undeletable: Set<String> = [
        "Recording", "Uploading", "Receiving from the watch", "Uploading on another device",
    ]

    /// docs/03: whether another device recorded this and this one only adopted the Drive folder —
    /// which is what the delete question has to know, because there is no local half to keep.
    ///
    /// Kept as the recording's own flag rather than read off [state]: an adopted recording reads as
    /// finished like any other, so its state says nothing about where it was recorded.
    public let remote: Bool

    /// docs/10: why this job is the user's to fix, or nil when it is not — the banner line, and the
    /// notification. Read off the job's own status and the step that stopped it, in [Recents.load],
    /// because a state *label* cannot tell `NEEDS_SPACE` from any other parked job.
    public let alert: AlertReason?

    public init(
        id: String,
        jobId: String?,
        title: String,
        startedAt: String,
        state: String,
        link: URL?,
        lastError: String?,
        workflowId: String? = nil,
        waitingMinutes: Int? = nil,
        nextRunAt: Date? = nil,
        durationSec: Double? = nil,
        remote: Bool = false,
        alert: AlertReason? = nil
    ) {
        self.id = id
        self.jobId = jobId
        self.title = title
        self.startedAt = startedAt
        self.state = state
        self.link = link
        self.lastError = lastError
        self.workflowId = workflowId
        self.waitingMinutes = waitingMinutes
        self.nextRunAt = nextRunAt
        self.durationSec = durationSec
        self.remote = remote
        self.alert = alert
    }
}

/// The join the core does not do: jobs are one table, the recordings they are about another, and
/// the Drive link is in a third. Both Apple shells draw the same rows, so they read them the same
/// way.
public enum Recents {
    /// docs/12 "메뉴바": what one reading of the desktop ledger adds. The ledger starts with a page
    /// and asks for another each time the last row it has is scrolled into view.
    public static let page: Int32 = 20

    public static func load(core: ReclyCore_, limit: Int32 = Recents.page) async throws -> [RecentItem] {
        let jobs = try await core.jobs.list()
        let byRecording = Dictionary(grouping: jobs, by: \.recordingId)
        let now = core.deps.clock.now()
        var items: [RecentItem] = []
        for record in try await core.recordings.list(limit: limit) {
            // One job per (recording, workflow); the newest is the one the user last asked for.
            let job = byRecording[record.id]?
                .max { $0.createdAt.toEpochMilliseconds() < $1.createdAt.toEpochMilliseconds() }
            var steps: [StepRun] = []
            if let job { steps = (try? await core.jobs.steps(jobId: job.id)) ?? [] }
            // docs/08 "폴링 · 상태": while a provider is transcribing there is no "when" to give,
            // only how long it has been — and "재시도 대기" would be a different thing to say.
            let waiting = job?.status == .waiting
                ? StepReport.shared.waitingMinutes(steps: steps, now: now)?.intValue
                : nil
            // A snapshot this build cannot read is the whole reason the job stopped, and the steps
            // it left behind say nothing about it (docs/10 "잡 스냅샷").
            let error = job?.snapshotError ?? lastError(steps)
            items.append(
                RecentItem(
                    id: record.id,
                    jobId: job?.id,
                    title: record.meta.title ?? "",
                    startedAt: record.meta.startedAt,
                    state: stateLabel(record: record, job: job),
                    link: driveLink(steps) ?? record.driveFolderUrl.flatMap(URL.init(string:)),
                    lastError: error,
                    workflowId: job?.workflowId,
                    waitingMinutes: waiting,
                    nextRunAt: job?.nextRunAt.map {
                        Date(timeIntervalSince1970: Double($0.toEpochMilliseconds()) / 1000)
                    },
                    durationSec: record.meta.durationSec?.doubleValue,
                    remote: record.remote,
                    alert: job.flatMap { JobAlerts.reason(status: $0.status, lastError: error) }
                )
            )
        }
        return items
    }

    static func stateLabel(record: RecordingRecord, job: Job_?) -> String {
        // docs/03 "워치 → 폰 전송 계약": the placeholder row this phone opened for a transfer that is
        // still arriving. It carries the recording's own `RECORDING` status, so it has to be asked
        // about before that — otherwise the row reads as this phone recording, which it is not.
        if record.receiving { return "Receiving from the watch" }
        // docs/03 "다른 기기의 녹음": the folder is on Drive with no `meta.json` in it yet, so the
        // other device is still uploading. `RECORDING` again, and again not this device's.
        if record.remoteUploading { return "Uploading on another device" }
        if record.meta.status == .recording { return "Recording" }
        // docs/03: another device recorded it and uploaded it; this one adopted the Drive folder and
        // has no job for it — but the recording itself is finished, and that is all the row has to
        // say. "No workflow" would read like something this device failed to do, and a state of its
        // own would be a permanent label for what is simply a finished recording.
        //
        // Unless that device has said it is not done: the marker beside the folder names the steps
        // still to run, and a transcription in flight is worth waiting for. A `webhook` left to run
        // is not — it changes nothing the user came here to read — so the row is simply `DONE`.
        if record.remote {
            return record.remotePending.contains("transcribe")
                ? "Transcribing on another device"
                : "Done"
        }
        guard let job else { return "No workflow" }
        switch job.status {
        case .pending: return "Waiting"
        case .running: return "Uploading"
        case .waiting: return "Retry pending"
        case .done: return "Done"
        case .failed: return "Failed"
        case .needsAuth: return "Sign-in needed"
        // docs/10 "Drive 용량 초과": parked rather than failed, and nothing retries it on its own —
        // the row's own state, so the list can offer the storage page instead of a retry that
        // would come back with the same 403.
        case .needsSpace: return "No space in Drive"
        case .skippedShort: return "Too short"
        }
    }

    /// docs/09 화면 원칙 2: what the ledger's header says about the list under it — "14 · 2 waiting ·
    /// 1 failed" is one glance, where the count on its own is a number nobody has a use for.
    ///
    /// The Android ledger's counting rule (`JobsScreen.waiting` · `JobsScreen.failing`): a
    /// recording no workflow has picked up is waiting like a queued job is, and one that was too
    /// short counts with the failures because it is a recording that produced nothing.
    public static func summary(_ items: [RecentItem]) -> String {
        UiMessage.key(
            "%1$@ · %2$@ waiting · %3$@ failed",
            args: [
                .verbatim(String(items.count)),
                .verbatim(String(items.filter { waiting.contains($0.state) }.count)),
                .verbatim(String(items.filter { failing.contains($0.state) }.count)),
            ]
        ).text
    }

    /// docs/09 화면 원칙 2 (2026-09-04): a recording still arriving from the watch, and one another
    /// device is still uploading, are both work the user is waiting on — the header counts them the
    /// way it counts a queued job. One another device is transcribing is not: the recording itself
    /// is in, and the header's number is about recordings.
    private static let waiting: Set<String> = [
        "Waiting", "Retry pending", "No workflow",
        "Receiving from the watch", "Uploading on another device",
    ]

    private static let failing: Set<String> =
        ["Failed", "Sign-in needed", "No space in Drive", "Too short"]

    /// Whether a job is running right now, off the same `"Uploading"` key the ledger badge reads.
    ///
    /// The ledger is the newest five recordings, so a job still running on one older than those is
    /// not seen here — the same scope the ledger itself has, and the dashboard says no more than
    /// the rows under it do.
    public static func uploading(_ items: [RecentItem]) -> Bool {
        items.contains { $0.state == "Uploading" }
    }

    /// docs/03 "워치 → 폰 전송 계약" · docs/09 화면 원칙 1: whether this phone is taking a recording off
    /// its watch right now, read off the ledger the way [uploading] reads a running job — the row
    /// is the only place the transfer is visible, and the State node above the list would otherwise
    /// say `IDLE` while the phone is busy.
    ///
    /// The desktop never asks: nothing is ever received on a Mac.
    public static func receiving(_ items: [RecentItem]) -> Bool {
        items.contains { $0.state == "Receiving from the watch" }
    }

    /// The last thing a step complained about, as the core wrote it — a `CoreMessage` code
    /// (docs/07 §5), or a sentence an older build stored. It is kept as it stands and turned into
    /// words by [RecentItem.reason], where the screen reads it.
    ///
    /// The step that is holding the job up comes first, and failing that the last complaint
    /// anything made: a `drive.upload` that succeeded after a retry has nothing to say about a
    /// `transcribe` that is now refusing the key.
    ///
    /// [JobAlerts.blockingError] is the whole of the rule, because `onError: continue` lets a job
    /// run past a failed step: the *first* failure is then not the one that ended the job, and
    /// reporting it would name a webhook nobody has to fix in place of the missing key.
    static func lastError(_ steps: [StepRun]) -> String? {
        JobAlerts.blockingError(steps: steps)
    }

    /// The core reads the field, because Swift must not touch `StepRun.output` at all: the
    /// `JsonObject` crosses as an `NSDictionary` and reading it here force-bridges every value to
    /// `JsonElement` — which a `drive.upload` output's `files` array is not, and the process dies in
    /// `swift_dynamicCastFailure` (`Recly-2026-09-02-214437.ips`). `outputString` keeps the tree on
    /// the Kotlin side and hands back the one string.
    static func driveLink(_ steps: [StepRun]) -> URL? {
        steps
            .compactMap { $0.outputString(key: "folderWebViewLink") }
            .compactMap(URL.init(string:))
            .first
    }
}
