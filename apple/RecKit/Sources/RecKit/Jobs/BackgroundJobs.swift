// docs/13 I4. `BGTaskScheduler` is iOS-only, and so is the half of the executor that needs it.
#if os(iOS)
import BackgroundTasks
import Foundation
import os

/// docs/13 "the executor": what runs the queue when the app is not on screen.
///
/// The division of labour with [BackgroundTransport] is the whole point of this type. The transport
/// gets the *bytes* of a Drive upload out while the app is suspended, because a background
/// `URLSession` is the only thing iOS keeps running for a suspended app. Every other step of a job
/// — opening the resumable session, `meta.json`, the webhook, marking the job done — is core code,
/// and core code runs only while the app does. So a stop asks the system for processing time
/// (`app.recly.jobs`), and so does every upload event that wakes the app up: the pass that follows
/// is what turns finished chunks into a finished job.
///
/// A grant is not a promise, and one grant is rarely the whole queue. Every handled task schedules
/// its own successor before it starts, the same rule [JobRunner] follows in the foreground.
@MainActor
public final class BackgroundJobs {
    /// docs/13 deliverable 4. Must also be in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`,
    /// or `register` returns false and the app never gets a pass.
    public static let identifier = "app.recly.jobs"
    /// iOS 26's `BGContinuedProcessingTask` wants a wildcard prefixed with the bundle id; one
    /// concrete identifier is submitted per "upload now".
    public static let uploadNowPrefix = "app.recly.uploadNow"

    /// One `runDueJobs()` pass, whatever it takes. The model owns the core; this type owns the
    /// scheduling around it.
    private let pass: () async -> Void
    private let logger = Logger(subsystem: CoreBridge.appName, category: "jobs")

    public init(pass: @escaping () async -> Void) {
        self.pass = pass
    }

    /// Both handlers, and it has to happen before `didFinishLaunching` returns — the system may be
    /// launching the app *for* one of them. Registering twice kills the app, so this is called once.
    public func register() {
        let processing = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.identifier,
            using: nil
        ) { [weak self] task in
            // `using: nil` is a background queue, and everything below the model is main-actor.
            Task { @MainActor in self?.handle(task) }
        }
        var continued = false
        if #available(iOS 26.0, *) {
            continued = BGTaskScheduler.shared.register(
                forTaskWithIdentifier: "\(Self.uploadNowPrefix).*",
                using: nil
            ) { [weak self] task in
                Task { @MainActor in self?.handle(task) }
            }
        }
        logger.info(
            """
            jobs.bg.register processing=\(processing, privacy: .public) \
            continued=\(continued, privacy: .public)
            """
        )
    }

    /// docs/13 deliverable 4: right after a stop, and after every batch of upload events. The
    /// request replaces whatever is already queued under the same identifier, so calling it often
    /// costs nothing.
    public func schedule() {
        let request = BGProcessingTaskRequest(identifier: Self.identifier)
        // An upload with no network is a pass that does nothing; charge is not required, because a
        // recording the user made this morning should not wait for tonight.
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        do {
            try BGTaskScheduler.shared.submit(request)
            logger.info("jobs.bg.scheduled")
        } catch {
            // The simulator refuses every submission (`BGTaskSchedulerErrorCodeUnavailable`), and so
            // does a device with Background App Refresh off. Neither is worth telling the user
            // about: the foreground executor still runs the queue.
            logger.error("jobs.bg.schedule.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    /// docs/13 deliverable 4: an upload the user asked for by hand — a retry — on iOS 26, which
    /// keeps the pass running with progress UI after they leave the app. Older systems have
    /// [schedule] and the foreground pass, which is what the button does there.
    public func uploadNow(recordingId: String, title: String) {
        guard #available(iOS 26.0, *) else { return }
        let request = BGContinuedProcessingTaskRequest(
            identifier: "\(Self.uploadNowPrefix).\(recordingId)",
            title: title,
            // The system draws this one, so it is looked up in the app's language explicitly.
            subtitle: RecKitStrings.localized("Uploading to Drive")
        )
        // Queued rather than refused when the system is busy — the user asked for this one.
        request.strategy = .queue
        do {
            try BGTaskScheduler.shared.submit(request)
            logger.info("jobs.bg.uploadNow id=\(recordingId, privacy: .public)")
        } catch {
            logger.error("jobs.bg.uploadNow.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    /// One granted task. The successor is asked for first: a pass that ends without one is a queue
    /// that stalls until the app is opened.
    private func handle(_ task: BGTask) {
        if task is BGProcessingTask { schedule() }
        let work = Task { await pass() }
        // Expiration is the system taking the time back. Cancelling leaves the step `RUNNING`,
        // which the next pass resets and repeats from its saved state (docs/10).
        task.expirationHandler = { work.cancel() }
        Task {
            await work.value
            if #available(iOS 26.0, *), let continued = task as? BGContinuedProcessingTask {
                // The progress UI has to end somewhere, and this is the end of the work.
                continued.progress.totalUnitCount = 1
                continued.progress.completedUnitCount = 1
            }
            task.setTaskCompleted(success: !work.isCancelled)
        }
    }
}
#endif
