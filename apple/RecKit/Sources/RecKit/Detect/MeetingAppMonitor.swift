#if os(macOS)
import AppKit
import CoreGraphics
import Foundation

/// Which meeting app is running, if any (docs/12 "미팅 감지" 신호 3).
///
/// Read on demand rather than observed: `MeetingDetector` already ticks every two seconds for the
/// microphone, and the browser half of the answer is a window *title*, which no launch notification
/// would report anyway.
///
/// A native meeting app counts because it is running. A browser does not — a browser is always
/// running — so it counts only while one of its windows is named like a meeting. `CGWindowListCopyWindowInfo`
/// is the whole of that check: the Accessibility tree would name the Zoom "Mute Audio" menu item as
/// well, and an Accessibility prompt is a price this feature is not worth (docs/12 "브라우저 Meet은
/// 창 제목까지만, AX 권한 요구 없음"). Window *names* are themselves behind the screen-recording
/// permission the system-audio tap already asks for; without it the browser case simply does not
/// fire, and the native apps are unaffected.
public enum MeetingAppMonitor {
    /// docs/12: the four the lane names. Bundle ids, because that is what `runningApplications`
    /// answers with and the only identity that survives the app being renamed or localized.
    public static let meetingApps: Set<String> = [
        "us.zoom.xos",
        "com.microsoft.teams2",
        "com.tinyspeck.slackmacgap",
        "com.hnc.Discord",
    ]

    /// The browsers a Meet/Teams/Webex tab is plausibly in. Being in this set is not a signal by
    /// itself — see [meetingWindowTitle].
    public static let browsers: Set<String> = [
        "com.apple.Safari",
        "com.google.Chrome",
        "com.microsoft.edgemac",
        "org.mozilla.firefox",
        "com.brave.Browser",
        "company.thebrowser.Browser",
    ]

    /// What a browser window has to be called before it counts. Lowercased on both sides.
    static let meetingTitles = ["meet.google.com", "google meet", "zoom", "microsoft teams", "webex"]

    /// The bundle id of the meeting app to attribute a recording to, or `nil`.
    public static func running() -> String? {
        let running = Set(NSWorkspace.shared.runningApplications.compactMap(\.bundleIdentifier))
        if let app = meetingApps.first(where: running.contains) { return app }
        guard let browser = browsers.first(where: running.contains), meetingWindowTitle() else { return nil }
        return browser
    }

    /// Any on-screen window whose name reads like a meeting. Not narrowed to the browser's own
    /// windows on purpose: `kCGWindowOwnerName` is a localized display name, and matching it is a
    /// way to be wrong in every language but English.
    private static func meetingWindowTitle() -> Bool {
        let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
        guard let windows = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else {
            return false
        }
        return windows.contains { window in
            guard let name = (window[kCGWindowName as String] as? String)?.lowercased() else { return false }
            return meetingTitles.contains { name.contains($0) }
        }
    }
}
#endif
