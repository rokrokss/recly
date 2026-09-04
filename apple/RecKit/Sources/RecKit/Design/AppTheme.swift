#if os(macOS)
import AppKit
#endif
import Foundation
import SwiftUI

/// docs/09 "접근성": light, dark, or whatever the OS says. The accessibility rule is that the system's
/// `prefers-color-scheme` is followed without being asked about, and this is the override over it —
/// nothing else about the palette is a setting.
///
/// A device setting like the language ([AppLanguage]) and stored the same way: `UserDefaults`, one
/// key, never synced — which machine is dark is a fact about that machine. Nothing stored means
/// nothing chosen, which is "follow the system"; the Windows shell's `AppTheme` is the same three
/// answers under the same three words.
@MainActor
public final class AppTheme: ObservableObject {
    /// docs/09: the system's answer, and the two overrides of it.
    public enum Choice: String, CaseIterable, Identifiable, Sendable {
        case system
        case light
        case dark

        public var id: String { rawValue }

        /// The chip's label. A key and not a word, resolved where it is drawn (docs/07 rule 3).
        public var labelKey: String {
            switch self {
            case .system: return "System default"
            case .light: return "Light"
            case .dark: return "Dark"
            }
        }

        public var label: String { RecKitStrings.localized(labelKey) }

        /// What the phone hands `.preferredColorScheme`. Nil is "no preference", which is the device's
        /// own — and so what [system] means.
        public var colorScheme: ColorScheme? {
            switch self {
            case .system: return nil
            case .light: return .light
            case .dark: return .dark
            }
        }

        #if os(macOS)
        /// The same answer for AppKit, and nil for the same reason: an application with no appearance
        /// of its own is one that follows the system's.
        public var appearance: NSAppearance? {
            switch self {
            case .system: return nil
            case .light: return NSAppearance(named: .aqua)
            case .dark: return NSAppearance(named: .darkAqua)
            }
        }
        #endif
    }

    public static let shared = AppTheme()

    /// The chip row's binding. Writing it stores the choice and applies it — on the Mac at once
    /// ([apply]), on the phone through the root's `.preferredColorScheme`, which every screen below
    /// reads back as `\.colorScheme` and so as its palette (`BlueprintRoot`).
    @Published public var choice: Choice = AppTheme.current {
        didSet {
            guard choice != oldValue else { return }
            AppTheme.current = choice
            apply()
        }
    }

    private init() {}

    /// docs/12 "메뉴바": the Mac's popover is an `NSPanel` and its editor and details windows are
    /// SwiftUI inside AppKit windows, so there is no one SwiftUI root to hang a
    /// `.preferredColorScheme` on. The application's own appearance is what all of them inherit, so
    /// that is what the setting writes — at launch and again on every change.
    ///
    /// Nothing to do on the phone, where the root modifier is the whole of it.
    public func apply() {
        #if os(macOS)
        NSApplication.shared.appearance = choice.appearance
        #endif
    }

    // MARK: - Away from the main actor

    private static let key = "appTheme"

    /// Read straight out of `UserDefaults` the way [AppLanguage.current] is, and for the same
    /// reason: what is stored is the whole of the setting, and nothing has to hop to the main actor
    /// to know it.
    public nonisolated static var current: Choice {
        get { Choice(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .system }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }
}
