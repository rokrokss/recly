import Foundation
import os
import UserNotifications

/// docs/10 "사용자가 고칠 수 있는 실패와 그 알림" on Apple. Three rules, and they are the whole of it:
///
/// 1. **One notification per reason.** Five jobs blocked on the same thing are one notification
///    whose body counts them, not five notifications.
/// 2. **Only what a person can fix.** A step inside its retry budget is `WAITING` and never reaches
///    [JobAlerts.reason], so a webhook 500 on its way round the backoff calls nobody.
/// 3. **It comes down by itself.** The queue is the source of truth, so a reason that is no longer
///    in it is withdrawn on the next reading — a sign-in, a `retry()`, a deletion.
///
/// docs/10 also says the phone falls back to "배너만" when there is no permission, which is what
/// this does by simply not posting: the list's own banner is drawn from the same [JobAlert]s and
/// needs nothing from Notification Center.
///
/// The Android twin is `JobAlertNotifier`/`JobAlertShade`; the macOS half shares Notification
/// Center with `MeetingNotifier`, which is why the delegate is optional here — a process may only
/// have one, and on the Mac it is the meeting one, which forwards what it does not recognise.
@MainActor
public final class JobAlertNotifier: NSObject, UNUserNotificationCenterDelegate {

    /// Where the fix is, and — for the reasons a workflow holds it — which workflow's editor.
    /// docs/10: "탭하면 고칠 수 있는 화면으로 간다. '앱 열기'로 끝내지 않는다."
    public var onFix: ((JobAlert) -> Void)?

    /// The last reading of the queue. Kept because two things repaint from it: a language change,
    /// and a tap — the system takes a notification down when it is opened, and the reason it was
    /// about is still in the queue, so it goes back up.
    private var latest: [JobAlert] = []
    /// What this process has actually put in Notification Center, so a pass that read the same
    /// queue again adds nothing.
    private var standing = StandingAlerts()
    /// Nil until Notification Center has been asked. False is "banner only" and is never asked
    /// again — a permission prompt raised on every pass is worse than no notification.
    private var granted: Bool?
    private let logger: Logger
    private let center: AlertCenter

    /// Building one touches nothing outside the process: a shell builds it with its model, and
    /// [adoptDelegate] — which does reach Notification Center — is called by the app itself.
    public convenience init(subsystem: String) {
        self.init(subsystem: subsystem, center: SystemAlertCenter())
    }

    /// The seam the tests come in through: [standing] is only correct if it is written from what
    /// Notification Center actually *took*, and that cannot be checked against the real one.
    init(subsystem: String, center: AlertCenter) {
        logger = Logger(subsystem: subsystem, category: "alerts")
        self.center = center
        super.init()
    }

    /// Become `UNUserNotificationCenter`'s delegate. The phone's app calls this from its own `init`
    /// because a notification tapped from a cold launch is delivered as the process comes up: a
    /// delegate installed when the core finishes opening is one installed after the response was
    /// dropped. The Mac never calls it — a process has one delegate, `MeetingNotifier` is already
    /// it, and it forwards what it does not recognise.
    ///
    /// Idempotent, and deliberately not done in [init]: it is the app's launch that decides when
    /// this process starts answering for Notification Center.
    public func adoptDelegate() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// A new reading of the queue: what has *changed* in it is posted, and every reason it does not
    /// name is withdrawn.
    ///
    /// The change is the whole of it. A `UNNotificationRequest` added under an identifier that is
    /// already standing replaces it — and both platforms alert again when they replace — so posting
    /// every reason on every runner pass buzzes the user every five minutes for one stuck job.
    public func publish(_ alerts: [JobAlert]) async {
        latest = alerts
        let update = standing.apply(alerts)
        // Withdrawing needs no permission and has to happen even when posting cannot: a
        // notification standing from before the user turned permission off is still standing.
        withdraw(update.withdraw)
        guard !update.post.isEmpty, await authorized() else { return }
        for alert in update.post {
            // Recorded only once Notification Center has taken it. An `add` that failed left
            // nothing on the Lock Screen, and recording it anyway made the next reading of the
            // same queue "no change" — so the alert was never posted again.
            guard await post(alert) else { continue }
            standing.record(alert)
        }
    }

    /// docs/07 rule 3: a notification already standing in Notification Center was drawn in the
    /// language of the moment it was posted and does not redraw itself, so a language change posts
    /// the same contents again — under the same identifiers, which replaces them where they stand.
    public func relocalize() async {
        guard !latest.isEmpty, await authorized() else { return }
        // Unconditionally, and not through [standing]: what changed is the sentence rather than the
        // queue, so a reason whose count is exactly what is already up is the one that needs it.
        for alert in latest {
            guard await post(alert) else { continue }
            standing.record(alert)
        }
    }

    /// The tap. Returns false when the response belongs to somebody else's notification, which is
    /// how `MeetingNotifier` on the Mac knows to keep it.
    @discardableResult
    public func handle(response: UNNotificationResponse) -> Bool {
        let content = response.notification.request.content
        guard let reason = Self.reason(ofCategory: content.categoryIdentifier) else { return false }
        guard response.actionIdentifier != UNNotificationDismissActionIdentifier else { return true }
        // Off the notification and not off [latest]: the tap may be on one this process never
        // posted — a notification standing since the last launch, opened before the core is even
        // open — and what it carries is the whole of what the fix screen needs.
        let tapped = JobAlert(
            reason: reason,
            count: content.userInfo[Self.countKey] as? Int ?? 1,
            workflowId: content.userInfo[Self.workflowKey] as? String,
            secret: content.userInfo[Self.secretKey] as? String,
            stepId: content.userInfo[Self.stepKey] as? String
        )
        logger.info("jobs.alert.tap reason=\(reason.rawValue, privacy: .public)")
        onFix?(tapped)
        // docs/10 rule 3: only a reading of the queue without this reason takes it down, and
        // opening a screen is not one — the job is still blocked. The system removes a notification
        // when it is opened, so the standing one goes back up.
        if let alert = latest.first(where: { $0.reason == reason }) {
            Task { @MainActor in
                guard await authorized(), await post(alert) else { return }
                standing.record(alert)
            }
        }
        return true
    }

    // MARK: - Notification Center

    /// Hands one alert to Notification Center, and says whether it got there.
    ///
    /// False is the whole reason this is awaited rather than fired off: [standing] is what makes a
    /// reading that changed nothing post nothing, so an alert recorded there without having landed
    /// is one the user never sees and this process never tries again.
    private func post(_ alert: JobAlert) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = alert.reason.label
        content.body = alert.waiting
        content.categoryIdentifier = Self.category(of: alert.reason)
        content.userInfo[Self.countKey] = alert.count
        if let workflowId = alert.workflowId {
            content.userInfo[Self.workflowKey] = workflowId
        }
        if let secret = alert.secret {
            content.userInfo[Self.secretKey] = secret
        }
        if let stepId = alert.stepId {
            content.userInfo[Self.stepKey] = stepId
        }
        // docs/10: "무음" — worth seeing, never worth interrupting anybody for.
        content.sound = nil
        // The identifier is the reason's own, so a new count replaces the standing notification
        // rather than stacking a column of them.
        let request = UNNotificationRequest(
            identifier: Self.category(of: alert.reason),
            content: content,
            trigger: nil
        )
        do {
            try await center.post(request)
            return true
        } catch {
            logger.error("jobs.alert.failed error=\(String(describing: error), privacy: .public)")
            return false
        }
    }

    private func withdraw(_ reasons: [AlertReason]) {
        guard !reasons.isEmpty else { return }
        center.withdraw(reasons.map(Self.category(of:)))
    }

    /// Asked when there is a first thing to say rather than at launch: a permission prompt on an
    /// app nobody has finished setting up is a prompt with no context (the same argument
    /// `MeetingNotifier` makes). Answered once and remembered — a refusal means banner only.
    private func authorized() async -> Bool {
        if let granted { return granted }
        let granted = await center.authorize()
        self.granted = granted
        if !granted { logger.info("jobs.alert.denied") }
        return granted
    }

    // MARK: - Identifiers

    private static let prefix = "job.alert."
    private static let workflowKey = "workflowId"
    private static let secretKey = "secret"
    private static let stepKey = "stepId"
    private static let countKey = "count"

    static func category(of reason: AlertReason) -> String { prefix + reason.rawValue }

    static func reason(ofCategory category: String) -> AlertReason? {
        guard category.hasPrefix(prefix) else { return nil }
        return AlertReason(rawValue: String(category.dropFirst(prefix.count)))
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        Task { @MainActor in
            handle(response: response)
            completionHandler()
        }
    }

    /// The app is on screen and a job just parked. Shown anyway: the user may be looking at the
    /// record tab while the list is the thing with news on it.
    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }
}

/// The three things [JobAlertNotifier] asks of Notification Center. A protocol only so that the
/// rule about *when* an alert counts as standing can be tested: `UNUserNotificationCenter.current()`
/// answers a test bundle with whatever the machine's own permissions are, and never refuses an
/// `add` on request.
@MainActor
protocol AlertCenter {
    /// The permission, asked once (docs/10: a refusal means banner only).
    func authorize() async -> Bool
    /// Throws exactly what Notification Center reported, so a request that did not land is not
    /// mistaken for one that did.
    func post(_ request: UNNotificationRequest) async throws
    func withdraw(_ identifiers: [String])
}

struct SystemAlertCenter: AlertCenter {
    func authorize() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .badge])) ?? false
    }

    func post(_ request: UNNotificationRequest) async throws {
        try await UNUserNotificationCenter.current().add(request)
    }

    func withdraw(_ identifiers: [String]) {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }
}

/// What one reading of the queue changes in Notification Center — [JobAlertNotifier]'s rules 1 and
/// 3 as arithmetic, so they can be checked without a notification centre to check them against.
///
/// The map is the whole point. `UNUserNotificationCenter.add` under an identifier that is already
/// standing *replaces* the notification and alerts again while it does, so a runner pass that read
/// the same stuck queue for the fifth time must add nothing at all: only a reason that was not
/// standing, or one whose line now says something else, is worth a person's attention.
struct AlertUpdate: Equatable {
    /// The alerts to hand to Notification Center — new reasons and changed counts, nothing else.
    let post: [JobAlert]
    /// The reasons whose notification comes down. Every reason the reading does not name, and not
    /// only the ones this process posted: a notification left standing by the *last* launch is
    /// still on the Lock Screen, and this reading is the first thing that knows it is stale.
    let withdraw: [AlertReason]
}

/// The reason → alert map of what has actually reached Notification Center.
struct StandingAlerts {
    private var posted: [AlertReason: JobAlert] = [:]

    /// What this reading changes. A reason that has left the queue is forgotten here, so its next
    /// appearance is a fresh post rather than a silent replace.
    mutating func apply(_ alerts: [JobAlert]) -> AlertUpdate {
        let byReason = Dictionary(alerts.map { ($0.reason, $0) }, uniquingKeysWith: { first, _ in first })
        posted = posted.filter { byReason[$0.key] != nil }
        return AlertUpdate(
            post: alerts.filter { posted[$0.reason] != $0 },
            withdraw: AlertReason.allCases.filter { byReason[$0] == nil }
        )
    }

    /// One alert reached Notification Center.
    mutating func record(_ alert: JobAlert) {
        posted[alert.reason] = alert
    }
}
