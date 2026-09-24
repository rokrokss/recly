import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/10 "사용자가 고칠 수 있는 실패와 그 알림": which failures call the user, which ones do not, and
/// what a queue full of them adds up to. Lane P1 acceptance 7 (one notification per reason, with
/// the count in it) and 8 (a provider 500 never notifies) are both decided here.
///
/// The Android shell's `JobAlertsTest` asks the same questions of the same rules. The two files are
/// how the two shells are held to one answer.
final class JobAlertsTests: XCTestCase {

    func testAParkedJobNamesItsOwnReason() {
        XCTAssertEqual(JobAlerts.reason(status: .needsAuth, lastError: nil), .needsAuth)
        XCTAssertEqual(JobAlerts.reason(status: .needsSpace, lastError: nil), .needsSpace)
    }

    func testAJobThatIsStillBeingCarriedCallsNobody() {
        for status in [JobStatus.pending, .running, .waiting, .done] {
            XCTAssertNil(
                JobAlerts.reason(status: status, lastError: providerError()),
                "\(status.name) alerted"
            )
        }
    }

    /// docs/10: "재시도로 낫는 실패는 알리지 않는다." A 5xx is inside the backoff, not at the end of it.
    func testAProvider500OnTheRetryPathNeverNotifies() {
        XCTAssertNil(JobAlerts.reason(status: .waiting, lastError: providerError()))
        // And even once it has run out of attempts: what spent them was something a retry could
        // have fixed, so there is nothing for the user to do about it but try again.
        let spent = CoreMessage.retryBudgetSpent.code(arg: providerError(), detail: nil)
        XCTAssertNil(JobAlerts.reason(status: .failed, lastError: spent))
    }

    func testTheKeyFailuresPointAtTheKey() {
        XCTAssertEqual(
            JobAlerts.reason(
                status: .failed,
                lastError: CoreMessage.missingSecret.code(arg: "stt_key", detail: nil)
            ),
            .missingSecret
        )
        XCTAssertEqual(
            JobAlerts.reason(
                status: .failed,
                lastError: CoreMessage.authRejected.code(arg: nil, detail: "401")
            ),
            .authRejected
        )
    }

    /// docs/10: a 429 waits quietly, but a budget spent on one is the user's plan or their bill.
    func testAQuotaThatSpentTheRetryBudgetIsTheUsersToFix() {
        let spent = CoreMessage.retryBudgetSpent.code(
            arg: CoreMessage.quota.code(arg: nil, detail: "poll 429"),
            detail: nil
        )

        XCTAssertEqual(JobAlerts.reason(status: .failed, lastError: spent), .quota)
    }

    /// docs/07 §5: a sentence an older build wrote is not a key, and nothing is claimed about it.
    func testProseFromAnOlderBuildAlertsNothing() {
        XCTAssertNil(JobAlerts.reason(status: .failed, lastError: "the upload failed"))
        XCTAssertNil(JobAlerts.reason(status: .failed, lastError: nil))
    }

    /// Acceptance 7: three jobs blocked on one reason are one line, and the line says three.
    func testTheSameReasonOnThreeJobsIsOneAlertWithACountOfThree() {
        let alerts = JobAlerts.fold([
            AlertSource(reason: .needsSpace),
            AlertSource(reason: .needsSpace),
            AlertSource(reason: .needsSpace),
            AlertSource(reason: nil),
        ])

        XCTAssertEqual(alerts, [JobAlert(reason: .needsSpace, count: 3)])
    }

    func testTwoReasonsAreTwoLinesAndNothingIsFoldedAcrossThem() {
        let alerts = JobAlerts.fold([
            AlertSource(reason: .quota),
            AlertSource(reason: .needsAuth),
            AlertSource(reason: .needsAuth),
        ])

        XCTAssertEqual(
            alerts,
            [
                JobAlert(reason: .needsAuth, count: 2),
                JobAlert(reason: .quota, count: 1),
            ]
        )
    }

    /// A queue with nothing wrong in it leaves no banner and no notification standing.
    func testACleanQueueHasNoAlerts() {
        XCTAssertEqual(
            JobAlerts.fold([
                AlertSource(reason: nil),
                AlertSource(reason: nil),
            ]),
            []
        )
    }

    /// `onError: continue` lets a job run past a failed step, so the first FAILED row is not the one
    /// that ended the job — the aborting step is the last failure with nothing successful after it.
    /// Picking the first would report a provider error nobody has to fix in place of the missing key.
    func testTheAbortingStepIsTheOneReportedAndNotAnEarlierContinue() {
        let steps = [
            step(0, .failed, providerError()),
            step(1, .succeeded, nil),
            step(2, .failed, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil)),
            step(3, .pending, nil),
        ]

        let blocking = JobAlerts.blockingError(steps: steps)

        XCTAssertEqual(blocking, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil))
        XCTAssertEqual(JobAlerts.reason(status: .failed, lastError: blocking), .missingSecret)
    }

    /// Two failures and nothing succeeded in between: the later one still stopped it.
    func testTheLaterOfTwoFailuresIsTheOneThatStoppedTheJob() {
        let quota = CoreMessage.quota.code(arg: nil, detail: "transcribe 429")
        let steps = [step(0, .failed, providerError()), step(1, .failed, quota)]

        XCTAssertEqual(JobAlerts.blockingError(steps: steps), quota)
    }

    /// Nothing is holding it up: the last thing anything complained about is all there is to say.
    func testAJobWithNoFailingStepFallsBackToTheLastComplaint() {
        let steps = [step(0, .succeeded, providerError()), step(1, .succeeded, nil)]

        XCTAssertEqual(JobAlerts.blockingError(steps: steps), providerError())
        XCTAssertNil(JobAlerts.blockingError(steps: []))
    }

    /// The rows come back in whatever order the query gave them; the rule is about the ordinal.
    func testTheOrderTheRowsArriveInDoesNotDecideTheAnswer() {
        let quota = CoreMessage.quota.code(arg: nil, detail: "transcribe 429")
        let steps = [step(1, .failed, quota), step(0, .failed, providerError())]

        XCTAssertEqual(JobAlerts.blockingError(steps: steps), quota)
    }

    /// docs/10: every reason has somewhere to go, and it is never just "open the app".
    func testEveryReasonHasAFixScreenBehindIt() {
        XCTAssertEqual(AlertReason.needsAuth.fix, .signIn)
        XCTAssertEqual(AlertReason.needsSpace.fix, .driveStorage)
        XCTAssertEqual(AlertReason.missingSecret.fix, .secrets)
        XCTAssertEqual(AlertReason.authRejected.fix, .secrets)
        XCTAssertEqual(AlertReason.quota.fix, .editor)
    }

    /// docs/07 rule 3: the reason is kept as a key and said in words where it is drawn, so a banner
    /// already on screen answers a language change.
    func testEveryReasonSpeaksBothLanguages() {
        for reason in AlertReason.allCases {
            AppLanguage.current = .en
            let english = reason.label
            AppLanguage.current = .ko
            let korean = reason.label

            // The key is namespaced, so a lookup that found nothing shows in *both* languages.
            XCTAssertNotEqual(english, reason.labelKey, "\(reason) has no English sentence")
            XCTAssertNotEqual(korean, english, "\(reason) is not translated — the catalog gave the key back")
        }
        AppLanguage.current = .system
    }

    /// docs/10: the count is the whole of what the second line says, and it survives the language.
    func testTheCountIsInTheLineInBothLanguages() {
        let alert = JobAlert(reason: .needsSpace, count: 3)

        AppLanguage.current = .en
        let english = alert.waiting
        AppLanguage.current = .ko
        let korean = alert.waiting
        AppLanguage.current = .system

        XCTAssertTrue(english.contains("3"), english)
        XCTAssertTrue(korean.contains("3"), korean)
        XCTAssertNotEqual(korean, english, "the catalog gave the key back")
    }

    // MARK: - The whole queue, not the ledger

    /// The regression this rule exists for: the ledger is the newest **five** recordings, and a job
    /// blocked before those was folded out of the alerts the moment a sixth recording pushed it
    /// off — the banner went quiet and the notification was withdrawn while the job was still
    /// stuck. The queue is what docs/10 rule 3 counts, so seven jobs are seven sources.
    func testAnOlderBlockedJobStillAlertsWhenNewerRecordingsFillTheLedger() {
        let stuck = QueuedJob(
            status: .failed,
            steps: [step(0, .failed, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil))]
        )
        let carried = (0 ..< 6).map { _ in
            QueuedJob(status: .done, steps: [step(0, .succeeded, nil)])
        }

        let queue = [stuck] + carried
        XCTAssertEqual(queue.count, 7)
        let alerts = JobAlerts.fold(JobAlerts.sources(queue))

        XCTAssertEqual(
            alerts,
            [JobAlert(reason: .missingSecret, count: 1, secret: "stt_key", stepId: "step0")]
        )
    }

    /// Every job counts once, whatever the recordings behind them were: two of the seven are stuck
    /// on the same thing and the line says two.
    func testTheCountIsOverTheQueueAndNotOverTheLedger() {
        let parked = QueuedJob(status: .needsSpace, steps: [])
        let queue = [parked, parked]
            + (0 ..< 5).map { _ in QueuedJob(status: .running, steps: []) }

        XCTAssertEqual(queue.count, 7)
        XCTAssertEqual(
            JobAlerts.fold(JobAlerts.sources(queue)),
            [JobAlert(reason: .needsSpace, count: 2)]
        )
    }

    /// `onError: continue` again, one level up: the source is folded off the step that *stopped*
    /// the job, so the provider error nobody has to fix does not become the queue's reason.
    func testAJobsSourceIsFoldedOffTheStepThatStoppedIt() {
        let source = JobAlerts.source(
            status: .failed,
            steps: [
                step(0, .failed, providerError()),
                step(1, .succeeded, nil),
                step(2, .failed, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil)),
            ]
        )

        XCTAssertEqual(
            source,
            AlertSource(reason: .missingSecret, secret: "stt_key", stepId: "step2")
        )
    }

    /// A job nobody has to do anything about carries no fix either — nothing for a secret form to
    /// be opened on.
    func testAJobThatIsNotTheUsersToFixCarriesNoFixAtAll() {
        XCTAssertEqual(
            JobAlerts.source(status: .running, steps: [step(0, .running, nil)]),
            AlertSource(reason: nil)
        )
    }

    // MARK: - Where the key is entered (docs/10 · docs/08 "오류")

    /// docs/10: `MISSING_SECRET` carries the key that is missing and the step that asked for it.
    func testTheKeyFailuresCarryTheSecretAndTheStepTheFormOpensOn() {
        let missing = JobAlerts.source(
            status: .failed,
            steps: [step(0, .failed, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil))]
        )
        XCTAssertEqual(missing.secret, "stt_key")
        XCTAssertEqual(missing.stepId, "step0")
    }

    /// `AUTH_REJECTED` is a provider refusing a value and never says which key held it (the core
    /// writes it with no argument at all), so the step is the whole of what the form has to go on.
    func testAuthRejectedNamesTheStepEvenThoughItNamesNoKey() {
        let source = JobAlerts.source(
            status: .failed,
            steps: [step(2, .failed, CoreMessage.authRejected.code(arg: nil, detail: "401"))]
        )

        XCTAssertEqual(source.reason, .authRejected)
        XCTAssertNil(source.secret)
        XCTAssertEqual(source.stepId, "step2")
    }

    /// Nothing but the missing key names a secret: a quota argument is a status or a provider, and
    /// prefilling a secret form with it would be nonsense.
    func testNoOtherReasonPretendsToNameASecret() {
        let quota = JobAlerts.source(
            status: .failed,
            steps: [step(0, .failed, CoreMessage.retryBudgetSpent.code(
                arg: CoreMessage.quota.code(arg: nil, detail: "429"), detail: nil
            ))]
        )

        XCTAssertEqual(quota.reason, .quota)
        XCTAssertNil(quota.secret)
        XCTAssertEqual(quota.stepId, "step0")
    }

    /// The fold hands the notification one job's fix, and both halves of it come from that one job
    /// — a step id from one and a key name from another would open the wrong form.
    func testTheFoldCarriesTheSecretAndTheStepOfOneJob() {
        let alerts = JobAlerts.fold([
            AlertSource(reason: .missingSecret, secret: "stt_key", stepId: "s1"),
            AlertSource(reason: .missingSecret, secret: "other_key", stepId: "s9"),
        ])

        XCTAssertEqual(
            alerts,
            [JobAlert(reason: .missingSecret, count: 2, secret: "stt_key", stepId: "s1")]
        )
    }

    /// [JobAlerts.blockingStep] is what [JobAlerts.blockingError] reads the code off, so the two
    /// can never name different steps.
    func testTheBlockingStepIsTheOneTheBlockingErrorCameFrom() {
        let steps = [
            step(0, .failed, providerError()),
            step(1, .succeeded, nil),
            step(2, .failed, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil)),
        ]

        XCTAssertEqual(JobAlerts.blockingStep(steps: steps)?.stepId, "step2")
        XCTAssertEqual(JobAlerts.blockingStep(steps: steps)?.lastError, JobAlerts.blockingError(steps: steps))
        XCTAssertNil(JobAlerts.blockingStep(steps: []))
    }

    // MARK: - A snapshot that could not be read

    /// The regression: `jobs.steps` was read as `(try? …) ?? []`, so a job whose step rows could not
    /// be read folded to *no reason* — indistinguishable from one that had come unstuck. The reading
    /// then looked like a queue with nothing wrong in it, and `publishAlerts` withdrew the
    /// notification and emptied the banner for a job that was still blocked. The read failing takes
    /// the whole snapshot with it instead, so the shell returns early and leaves what is standing.
    func testAStepReadThatFailedFailsTheWholeSnapshot() async {
        struct Unreadable: Error {}
        let jobs = [(id: "j1", status: JobStatus.failed)]

        do {
            _ = try await JobAlerts.sources(jobs: jobs) { _ in throw Unreadable() }
            XCTFail("a failed job with unreadable steps was folded as having no reason")
        } catch is Unreadable {
            // What the shell needs: something to catch.
        } catch {
            XCTFail("unexpected \(error)")
        }
    }

    /// And the fold itself is unchanged when the rows can be read: each job's reason comes off its
    /// own steps.
    func testEachJobIsFoldedFromItsOwnStepsWhenTheyCanBeRead() async throws {
        let jobs = [
            (id: "j1", status: JobStatus.failed),
            (id: "j2", status: JobStatus.done),
        ]
        let steps = [
            "j1": [step(0, .failed, CoreMessage.missingSecret.code(arg: "stt_key", detail: nil))],
            "j2": [step(0, .succeeded, nil)],
        ]

        let sources = try await JobAlerts.sources(jobs: jobs) { steps[$0] ?? [] }

        XCTAssertEqual(
            JobAlerts.fold(sources),
            [JobAlert(reason: .missingSecret, count: 1, secret: "stt_key", stepId: "step0")]
        )
    }

    /// docs/09 화면 원칙 2: the banner's badge is a code, and no two reasons share one.
    func testEveryReasonHasACodeOfItsOwn() {
        let codes = AlertReason.allCases.map(\.code)
        XCTAssertEqual(codes.count, Set(codes).count)
        XCTAssertTrue(codes.allSatisfy { $0 == $0.uppercased() })
    }

    // MARK: - Pieces

    /// A provider 5xx: retried by the runner, never the user's to fix.
    private func providerError() -> String {
        CoreMessage.providerError.code(arg: nil, detail: "500")
    }

    private func step(_ ordinal: Int32, _ status: StepStatus, _ lastError: String?) -> StepRun {
        StepRun(
            id: "s\(ordinal)",
            jobId: "j",
            stepId: "step\(ordinal)",
            ordinal: ordinal,
            status: status,
            attempts: 0,
            nextAttemptAt: nil,
            lastError: lastError,
            state: nil,
            output: nil
        )
    }
}
