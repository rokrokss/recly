import ActivityKit
import Foundation
import os
import RecKit
import UIKit

/// The app's half of docs/13 "Display": one Live Activity for one recording, started when the
/// recording starts, ended when it stops, and asked for again before ActivityKit's eight-hour cap
/// takes it away underneath a meeting that is still going.
///
/// Everything here is best effort. A Live Activity the user has switched off in Settings, or one
/// the system refuses, must not cost anybody a recording — so nothing throws upwards and nothing
/// waits on it.
///
/// ActivityKit starts one only for an app in the foreground (docs/13 "iPhone" Display: a background
/// start needs a push server, and Recly runs none), so a plan that arrives in the background — the
/// paired watch's recording, told to an app it woke — waits for the app to become active, and is
/// applied then. Ending one works from anywhere.
@MainActor
final class RecordingLiveActivity {
    private let logger = Logger(subsystem: "app.recly", category: "activity")
    private var activity: Activity<RecordingActivityAttributes>?
    /// When the *activity* was requested, which is not when the recording started once it has been
    /// asked for a second time (see [refreshIfNeeded]).
    private var requestedAt: Date?
    /// The last plan the model applied, applied again when the app becomes active.
    private var wanted: RecordingActivityPlan = .none
    private var activeObserver: NSObjectProtocol?

    init() {
        activeObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                Task { await self.apply(self.wanted) }
            }
        }
    }

    /// docs/13 deliverable 3: leftover Activities are cleaned up after a quit or a crash. A process
    /// that died mid-recording leaves a pill counting up for a recording nobody is making; the next
    /// launch takes it down.
    ///
    /// Not the paired watch's: the watch records whatever became of this process, and a launch in the
    /// background — the watch's own wake at a part — could not start it again. It is kept as this run's
    /// own, and the model's next apply updates it or ends it. Nor one this run has already started.
    func endStale() async {
        for stale in Activity<RecordingActivityAttributes>.activities where stale.id != activity?.id {
            if activity == nil, stale.content.state.onWatch {
                activity = stale
                // Its request is no earlier than the watch's start, so a renewal counted from here is early, not late.
                requestedAt = stale.content.state.startedAt
                continue
            }
            await stale.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// The one call the model makes: what the recorder is doing, as a plan, applied.
    func apply(_ plan: RecordingActivityPlan) async {
        wanted = plan
        switch plan {
        case .none:
            await end()

        case .show(var state):
            // docs/07 rule 3: stamped on every apply rather than once at the request, so a language
            // picked in the middle of a recording reaches the pill — the model applies again when
            // [AppLanguage.didChange] arrives, and an update is all it takes.
            state.language = AppLanguage.current.code ?? ""
            if let activity {
                await activity.update(ActivityContent(state: state, staleDate: nil))
            } else if UIApplication.shared.applicationState == .active {
                await request(state)
            } else {
                logger.info("activity.deferred")
            }
        }
    }

    /// docs/13 "renewed at the 8-hour limit". Called from the model's own tick, so no timer of its own.
    func refreshIfNeeded(now: Date = Date()) async {
        guard let activity, let requestedAt,
              RecordingActivityPlan.needsRefresh(requestedAt: requestedAt, now: now)
        else { return }
        // The *recording's* start, not this activity's: the clock on the Lock Screen has to keep
        // reading the length of the recording across the hand-over.
        let state = activity.content.state
        await activity.end(nil, dismissalPolicy: .immediate)
        self.activity = nil
        self.requestedAt = nil
        await request(state)
        logger.info("activity.refreshed")
    }

    private func request(_ state: RecordingActivityAttributes.ContentState) async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            return logger.info("activity.disabled")
        }
        do {
            activity = try Activity.request(
                attributes: RecordingActivityAttributes(),
                content: ActivityContent(state: state, staleDate: nil)
            )
            requestedAt = Date()
        } catch {
            logger.error("activity.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    private func end() async {
        guard let activity else { return }
        self.activity = nil
        requestedAt = nil
        await activity.end(nil, dismissalPolicy: .immediate)
    }
}
