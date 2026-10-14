import Accessibility
import SwiftUI

extension View {
    /// docs/09 "Disabled, focus and structure" (2026-10-15): a status line that changes while the user looks at it — the
    /// record screens', the watch's, the popover's — is announced politely: on Apple, an announcement when its text
    /// changes, queued behind whatever VoiceOver is saying. Not when it first appears, and not when it empties — an
    /// empty line has nothing to say.
    public func announcesChanges(of text: String) -> some View {
        onChange(of: text) { _, now in
            guard !now.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            var spoken = AttributedString(now)
            spoken.accessibilitySpeechAnnouncementPriority = .low
            AccessibilityNotification.Announcement(spoken).post()
        }
    }
}
