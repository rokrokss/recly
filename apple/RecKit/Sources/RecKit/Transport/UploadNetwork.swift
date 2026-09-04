import Foundation

/// docs/11 A5 — the phone's `Upload on Wi-Fi only`: whether a Drive upload is allowed to leave over
/// mobile data. Android says this to WorkManager as `NetworkType.UNMETERED`
/// (`work/WorkScheduler.networkType`); iOS has no scheduler to say it to, so it is said to the
/// upload session and to the task itself.
///
/// `UserDefaults` and not the core: docs/05 "동기화하지 않는 것" — it is a fact about *this* phone's
/// data plan and not about the account, so it is never synced.
///
/// **The request and never the session.** A session's `allowsCellularAccess` is a *cap*, not a
/// default: a task cannot ask for more than its session allows, so a session opened while the
/// switch was on would hold every later task to Wi-Fi however the switch is set afterwards — and a
/// background session cannot be rebuilt, because its identifier admits only one. So the upload
/// configuration is left permissive and the answer is written onto each request, which a background
/// task honours.
///
/// **When a change takes.** The value is read where a task is made rather than kept anywhere, so
/// the next chunk goes out under the new answer, both ways round. A chunk already in flight keeps
/// the request it was started with — exactly as WorkManager re-evaluates its constraints only on
/// the next enqueue, and for the same reason: neither system takes a transfer back off the wire.
public enum UploadNetwork {
    public static let key = "uploads.wifiOnly"

    /// Off until the user turns it on, which is Android's default too: a user who has not answered
    /// has not asked for their recordings to wait for a network they may not be on.
    public static var wifiOnly: Bool {
        get { UserDefaults.standard.bool(forKey: key) }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    /// Both flags, never one: `allowsCellularAccess` alone still lets a task out over a personal
    /// hotspot, which is the mobile data the setting is about.
    public static func apply(wifiOnly: Bool, to request: inout URLRequest) {
        request.allowsCellularAccess = !wifiOnly
        request.allowsExpensiveNetworkAccess = !wifiOnly
    }
}
