import Foundation
import ReclyCore
import UserNotifications
#if os(iOS)
import UIKit
#elseif os(watchOS)
import WatchKit
#endif

/// What a recording that runs for hours says through Notification Center, on the iPhone, the watch
/// and the Mac. There is no server to send anything, so every notice is scheduled ahead or posted by
/// the app itself, from the recorder's own events ([SegmentedRecorder.onEvent]):
///
/// - **The dead-man**, two segments after the start and pushed back at every boundary. A recording
///   that runs never lets it fire; a process that died cannot withdraw it, which is the point.
/// - **The reminder**, posted the moment the recorder has heard nobody speak for ten minutes
///   ([RecorderEvent.nobodySpeaking]), with a Stop that asks nothing. Never scheduled ahead: only
///   the audio can say it is due, and the recording process runs in the background to hear it.
/// - **The battery stop** (iPhone, watch): checked every minute, and at 5 % or less with no charger
///   the recording is stopped and filed before the device shuts down in the middle of a segment —
///   unless it started that low ([batteryStops]).
/// - **The pause** (iPhone, watch): a restart that ran out of attempts waits for the app to come
///   back ([SegmentedRecorder.becameActive]), and the person is asked to open it.
///
/// None plays a sound on the iPhone or the Mac: a reminder fires while the microphone records, and
/// its chime would be in the audio. The watch is the exception for the three that fire only when
/// nothing is being recorded — a sound is the one thing that taps a wrist (there is no haptic-only
/// notification), and in silent mode the tap is all it is.
@MainActor
public final class RecordingNotices: NSObject, UNUserNotificationCenterDelegate {

    /// The reminder's Stop: the shell's stop that asks nothing, as the Live Activity's is.
    public var onStop: (() -> Void)?

    private let stopKey: String
    private let logger: any ReclyCore.Logger
    private let center: AlertCenter
    private var recording: (id: String, segmentSec: Int)?
    private var waiting = false
    /// Every call to Notification Center, one after the other: a withdraw that overtook the post
    /// queued before it would leave the dead-man armed over a recording that waits.
    private var queue: Task<Void, Never>?
    #if os(iOS) || os(watchOS)
    private var battery: Timer?
    private var batteryAtStart: (level: Float, unplugged: Bool) = (-1, false)
    #endif

    /// - Parameter stopKey: the shell's own word for stopping, in its own catalog — the reminder's button.
    public convenience init(stopKey: String) {
        #if os(watchOS)
        let center = SystemAlertCenter(options: [.alert, .sound])
        #else
        let center = SystemAlertCenter()
        #endif
        // The core's own logger on Apple, so `rec.recorder.batteryStop` lands beside the recorder's lines.
        self.init(stopKey: stopKey, logger: OSLogLogger(), center: center)
    }

    init(stopKey: String, logger: any ReclyCore.Logger, center: AlertCenter) {
        self.stopKey = stopKey
        self.logger = logger
        self.center = center
        super.init()
    }

    // MARK: - The rules (v1 constants)

    /// Two segments: one late boundary is not a dead recording, two in a row are (Android's
    /// `RecordingAlerts` says the same).
    static func deadManDelaySec(segmentSec: Int) -> TimeInterval { TimeInterval(2 * segmentSec) }

    static let batteryCheckSec: TimeInterval = 60

    /// Not while it charges — the level only goes up then — and never on a level the platform
    /// cannot read (−1).
    static func batteryLow(level: Float, unplugged: Bool) -> Bool {
        level >= 0 && level <= 0.05 && unplugged
    }

    /// A recording that started this low already is the person's choice and runs on: stopping it
    /// within the minute would leave a recording with next to nothing in it. One that started above
    /// the line, or on the charger, is stopped once it drops below it unplugged.
    static func batteryStops(level: Float, unplugged: Bool, startLevel: Float, startUnplugged: Bool) -> Bool {
        batteryLow(level: level, unplugged: unplugged) && !batteryLow(level: startLevel, unplugged: startUnplugged)
    }

    // MARK: - The recorder's events

    public func apply(_ event: RecorderEvent) {
        switch event {
        case .started(let id, let segmentSec):
            recording = (id, segmentSec)
            waiting = false
            let category = Self.category(stop: AppStrings.localized(stopKey))
            then { [center] in
                // At the first recording rather than at launch, where the prompt would have no
                // reason; answered at once every time after.
                _ = await center.authorize()
                await center.register(category)
            }
            post(deadMan(segmentSec: segmentSec))
            startBatteryChecks()

        case .partFiled:
            guard let recording, !waiting else { return }
            post(deadMan(segmentSec: recording.segmentSec))

        case .nobodySpeaking:
            guard let recording, !waiting else { return }
            post(notice(
                Self.reminderId,
                title: RecKitStrings.localized("Still recording"),
                body: RecKitStrings.localized("No one has spoken for 10 minutes."),
                recordingId: recording.id,
                category: Self.reminderCategory
            ))

        case .awaitingResume:
            guard let recording else { return }
            waiting = true
            withdraw([Self.deadManId, Self.reminderId])
            post(notice(
                Self.pausedId,
                title: RecKitStrings.localized("Recording paused"),
                body: RecKitStrings.localized("Open Recly to keep recording."),
                recordingId: recording.id,
                sound: Self.idleSound
            ))

        case .resumed:
            guard let recording else { return }
            waiting = false
            withdraw([Self.pausedId])
            post(deadMan(segmentSec: recording.segmentSec))

        case .ended:
            recording = nil
            waiting = false
            stopBatteryChecks()
            withdraw(Self.scheduledIds)
        }
    }

    /// A response to one of these notices. False for anybody else's, so the shell's own delegate
    /// can keep it. The Stop stops only the recording it was posted for — a reminder left over from
    /// a run that was killed must not end the one recording now.
    @discardableResult
    public func handle(response: UNNotificationResponse) -> Bool {
        let request = response.notification.request
        guard request.identifier.hasPrefix(Self.prefix) else { return false }
        if response.actionIdentifier == Self.stopAction,
           let id = request.content.userInfo[Self.recordingKey] as? String, id == recording?.id {
            onStop?()
        }
        return true
    }

    /// The reminder's one button. Public because the Mac's meeting offers register the process's
    /// categories too, and have to keep this one among them.
    public static func category(stop title: String) -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: reminderCategory,
            actions: [UNNotificationAction(identifier: stopAction, title: title, options: [])],
            intentIdentifiers: []
        )
    }

    /// No recording outlives the process that made it, so what a killed run left pending — its
    /// dead-man — is withdrawn at launch. Pending only: a dead-man that has fired is
    /// news the person may not have seen yet, and the watch is launched in the background too.
    public static func withdrawPending() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: scheduledIds)
    }

    /// The watch has no other notifications and so no delegate of its own: this one takes it, for
    /// the Stop and so a notice is shown while the app is in front. The iPhone's and the Mac's
    /// delegates hand their responses on to [handle] instead.
    public func adoptDelegate() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// Lets a test wait for what has been handed to Notification Center.
    func settled() async {
        await queue?.value
    }

    // MARK: - Notification Center

    private func deadMan(segmentSec: Int) -> UNNotificationRequest {
        notice(
            Self.deadManId,
            title: RecKitStrings.localized("Recording may have stopped"),
            body: RecKitStrings.localized("Open Recly to check."),
            recordingId: recording?.id ?? "",
            after: Self.deadManDelaySec(segmentSec: segmentSec),
            sound: Self.idleSound
        )
    }

    /// One notice. [after] nil posts it now; the same identifier replaces a request still pending.
    private func notice(
        _ id: String,
        title: String,
        body: String? = nil,
        recordingId: String,
        after delay: TimeInterval? = nil,
        category: String? = nil,
        sound: UNNotificationSound? = nil
    ) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = title
        if let body { content.body = body }
        content.userInfo[Self.recordingKey] = recordingId
        if let category { content.categoryIdentifier = category }
        content.sound = sound
        return UNNotificationRequest(
            identifier: id,
            content: content,
            trigger: delay.map { UNTimeIntervalNotificationTrigger(timeInterval: $0, repeats: false) }
        )
    }

    private func post(_ request: UNNotificationRequest) {
        then { [center] in try? await center.post(request) }
    }

    private func withdraw(_ ids: [String]) {
        then { [center] in center.withdraw(ids) }
    }

    private func then(_ work: @escaping @MainActor () async -> Void) {
        let previous = queue
        queue = Task { @MainActor in
            _ = await previous?.value
            await work()
        }
    }

    /// See the type's note: the watch taps for a notice that fires while nothing is recorded.
    private static var idleSound: UNNotificationSound? {
        #if os(watchOS)
        return .default
        #else
        return nil
        #endif
    }

    // MARK: - The battery (iPhone, watch)

    private func startBatteryChecks() {
        #if os(iOS) || os(watchOS)
        battery?.invalidate()
        Self.device.isBatteryMonitoringEnabled = true
        batteryAtStart = (Self.device.batteryLevel, Self.device.batteryState == .unplugged)
        let timer = Timer(timeInterval: Self.batteryCheckSec, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkBattery() }
        }
        RunLoop.main.add(timer, forMode: .common)
        battery = timer
        #endif
    }

    private func stopBatteryChecks() {
        #if os(iOS) || os(watchOS)
        guard let battery else { return }
        battery.invalidate()
        self.battery = nil
        Self.device.isBatteryMonitoringEnabled = false
        #endif
    }

    #if os(iOS) || os(watchOS)
    #if os(iOS)
    private static var device: UIDevice { .current }
    #else
    private static var device: WKInterfaceDevice { .current() }
    #endif

    /// The stop a reminder's button makes, and then the notice that says why — posted once the
    /// recording is no longer capturing.
    private func checkBattery() {
        let level = Self.device.batteryLevel
        guard let recording, Self.batteryStops(
            level: level,
            unplugged: Self.device.batteryState == .unplugged,
            startLevel: batteryAtStart.level,
            startUnplugged: batteryAtStart.unplugged
        ) else { return }
        stopBatteryChecks()
        logger.log(
            level: .warn,
            event: "rec.recorder.batteryStop",
            fields: ["recordingId": recording.id, "level": Int((level * 100).rounded())],
            error: nil
        )
        onStop?()
        post(notice(
            Self.batteryId,
            title: RecKitStrings.localized("Recording stopped: battery low"),
            recordingId: recording.id,
            sound: Self.idleSound
        ))
    }
    #endif

    // MARK: - Identifiers

    private static let prefix = "recording."
    static let deadManId = "recording.deadman"
    static let pausedId = "recording.paused"
    static let batteryId = "recording.battery"
    static let reminderId = "recording.reminder"
    public static let reminderCategory = "recording.reminder"
    static let stopAction = "recording.stop"
    private static let recordingKey = "recordingId"
    /// What a stop withdraws: everything but the battery notice, which is posted by the stop itself.
    static let scheduledIds = [deadManId, pausedId, reminderId]

    // MARK: - UNUserNotificationCenterDelegate (the watch)

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

    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
