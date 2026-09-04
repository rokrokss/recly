import Foundation
import os
import RecKit
import UserNotifications

/// The two notifications the meeting detector is allowed to raise (docs/12 "미팅 감지", ADR-011:
/// detect → confirm → record). Both are offers with a button on them — the app never starts or
/// stops a recording because it thinks it should.
///
/// Notification Center draws these, so every string is looked up in the app's language explicitly
/// (docs/07 rule 3). The categories are re-registered on every [post] for the same reason: the
/// button's title was fixed at registration and the language may have changed since.
///
/// A menu-bar app with `LSUIElement` has nowhere else to put this. The menu is only visible while
/// the user is already looking at it, and the whole point of the detection is to reach someone who
/// is looking at Zoom.
@MainActor
final class MeetingNotifier: NSObject, UNUserNotificationCenterDelegate {
    enum Action: String {
        case start = "meeting.start"
        case stop = "meeting.stop"
    }

    /// Called on the main actor when the user takes the notification's button — or the notification
    /// itself, which macOS reports as the default action and which means the same thing here.
    var onAction: ((Action) -> Void)?

    /// docs/10 "macOS": the job alerts go through Notification Center too, and a process has one
    /// delegate. This one is it, so a response it does not recognise is handed on — the job
    /// notifier answers true for its own and false for anybody else's.
    var forward: ((UNNotificationResponse) -> Bool)?

    private let logger = Logger(subsystem: "app.recly.mac", category: "detect")

    override init() {
        super.init()
        adoptDelegate()
    }

    /// Become Notification Center's delegate. Called again from `RecMacApp.init` because that is
    /// the earliest the shell runs any code of its own, and a notification tapped from a cold
    /// launch — a meeting offer, or a job alert this forwards — is delivered as soon as the launch
    /// finishes. Idempotent: the same object set twice is the same delegate.
    func adoptDelegate() {
        UNUserNotificationCenter.current().delegate = self
    }

    private func registerCategories() {
        UNUserNotificationCenter.current().setNotificationCategories([
            Self.category(.start, button: AppStrings.localized("Start recording")),
            Self.category(.stop, button: AppStrings.localized("Stop recording")),
        ])
    }

    /// Authorization is asked for here rather than at launch: the first meeting is when the app has
    /// something to say, and a permission prompt on a menu-bar app nobody has used yet is a prompt
    /// with no context. `requestAuthorization` answers immediately once it has been decided.
    func post(_ action: Action) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { [weak self] granted, error in
            guard let self else { return }
            Task { @MainActor in
                guard granted else {
                    self.logger.info("detect.notify.denied error=\(String(describing: error), privacy: .public)")
                    return
                }
                self.registerCategories()
                self.add(action)
            }
        }
    }

    /// docs/07 rule 3: an offer already standing in Notification Center was drawn in the language of
    /// the moment it was posted, and its button's title was fixed when the category was registered.
    /// Neither redraws itself, so a language change re-registers the categories and posts the same
    /// offers again — under the same identifiers, which replaces them where they stand rather than
    /// leaving the old wording next to the new.
    func relocalize() async {
        registerCategories()
        let standing = await UNUserNotificationCenter.current().deliveredNotifications()
        for action in standing.compactMap({ Action(rawValue: $0.request.content.categoryIdentifier) }) {
            add(action)
        }
    }

    private func add(_ action: Action) {
        let content = UNMutableNotificationContent()
        switch action {
        case .start:
            content.title = AppStrings.localized("Are you in a meeting?")
            content.body = AppStrings.localized("Recly can record this meeting from here on.")
        case .stop:
            content.title = AppStrings.localized("End the recording?")
            content.body = AppStrings.localized(
                "The microphone has been quiet for over a minute. Stop the recording if the meeting is over."
            )
        }
        content.categoryIdentifier = action.rawValue
        // The identifier is the action's own, so a second offer replaces the first rather than
        // stacking a column of them in Notification Center.
        let request = UNNotificationRequest(identifier: action.rawValue, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                self?.logger.error("detect.notify.failed error=\(String(describing: error), privacy: .public)")
            }
        }
    }

    private static func category(_ action: Action, button: String) -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: action.rawValue,
            actions: [
                UNNotificationAction(
                    identifier: action.rawValue, title: button, options: [.foreground]
                )
            ],
            intentIdentifiers: []
        )
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let identifier = response.notification.request.content.categoryIdentifier
        let taken = response.actionIdentifier != UNNotificationDismissActionIdentifier
        Task { @MainActor in
            if let action = Action(rawValue: identifier) {
                if taken {
                    self.logger.info("detect.notify.action id=\(identifier, privacy: .public)")
                    self.onAction?(action)
                }
            } else {
                _ = self.forward?(response)
            }
            completionHandler()
        }
    }

    /// The job alerts are posted while the app is frontmost too — the user may be looking at the
    /// editor window while the queue is the thing with news on it.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list])
    }
}
