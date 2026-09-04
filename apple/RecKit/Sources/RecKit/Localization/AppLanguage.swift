import Foundation
import SwiftUI

/// docs/07: the app's language — the system's by default, or one the user picked in the app. It is
/// a device setting and is never synced (rule 2), so `UserDefaults` is the whole of the storage.
///
/// `AppleLanguages` is deliberately not touched: rewriting it would need a relaunch to take, and
/// rule 3 asks for the screen to change where it stands. What changes instead is SwiftUI's
/// `\.locale` — which is what `Text` resolves a key in — and, for everything drawn outside SwiftUI,
/// an explicit lookup through [AppLanguage.bundle].
@MainActor
public final class AppLanguage: ObservableObject {
    /// docs/07 rule 1: `system` follows the device, and the two the app is translated into.
    public enum Choice: String, CaseIterable, Identifiable, Sendable {
        case system
        case ko
        case en

        public var id: String { rawValue }

        /// docs/07 rule 2: what the picker offers, each under its own name and in the order of
        /// those names. [system] is not one of them — it is the store's "nothing chosen", and what
        /// the picker then shows as chosen is [AppLanguage.effective].
        public static let choices: [Choice] = [.en, .ko]

        /// The bare tag, or nil for [system] — which has no tag of its own to look anything up in.
        public var code: String? { self == .system ? nil : rawValue }

        /// What `\.locale` becomes. For [system] that is whatever the device resolved the app to,
        /// which is also what a bundle with no `ko` in it falls back to.
        public var locale: Locale { code.map(Locale.init(identifier:)) ?? .current }
    }

    public static let shared = AppLanguage()

    /// The picker's binding. Writing it is what makes every screen redraw, because every SwiftUI
    /// root observes this object and hands the new locale down.
    @Published public var choice: Choice = AppLanguage.current {
        didSet {
            guard choice != oldValue else { return }
            AppLanguage.current = choice
            NotificationCenter.default.post(name: Self.didChange, object: nil)
        }
    }

    /// docs/07 rule 3: what has to be *told* about a language change, because it is not a SwiftUI
    /// body that will simply be evaluated again — the watch, a Live Activity in the widget process,
    /// a notification already standing in Notification Center.
    ///
    /// Posted after [current] is written, so a handler reads the new choice rather than the old one
    /// (which is what a `@Published` publisher, sent from `willSet`, would hand it).
    public static let didChange = Notification.Name("app.recly.language.didChange")

    public var locale: Locale { choice.locale }

    /// What the picker shows as chosen: the language the app is actually in, which with nothing
    /// chosen is the device's rather than "system". Writing it is an ordinary explicit pick — the
    /// one already in effect included, which pins it.
    public var effective: Choice {
        get { Self.effective(choice) }
        set { choice = newValue }
    }

    private init() {}

    // MARK: - Away from the main actor

    private static let key = "appLanguage"

    /// Read straight out of `UserDefaults` rather than off [shared]: a string is looked up from
    /// every actor there is — a background executor pass, a notification, a `WCSession` callback —
    /// and none of them can hop to the main one to ask.
    public nonisolated static var current: Choice {
        get { Choice(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .system }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }

    public nonisolated static var locale: Locale { current.locale }

    /// The language the app is actually in, as a bare tag: the choice's own, or — for `system` —
    /// the device's, narrowed to one of the two the app has (docs/07 rule 1).
    ///
    /// What a *different* device has to be told, because `system` there means that device's locale:
    /// a watch handed `system` follows its own language rather than the phone's (rule 2).
    public nonisolated static var resolvedCode: String { effective(current).rawValue }

    /// The same narrowing as a function of what it depends on, so it can be asked about a device
    /// that is not this one: the choice's own language, or [system]'s, and anything that is not
    /// Korean is the base language the app is written in (docs/07 rule 1).
    public nonisolated static func effective(
        _ choice: Choice,
        system: String? = Locale.current.language.languageCode?.identifier
    ) -> Choice {
        (choice.code ?? system) == "ko" ? .ko : .en
    }

    /// The `.lproj` inside [base] the current choice names, or [base] itself when the choice is
    /// `system` — which is the bundle the loader has already resolved to the device's language.
    public nonisolated static func bundle(in base: Bundle) -> Bundle {
        guard let code = current.code,
              let path = base.path(forResource: code, ofType: "lproj"),
              let localized = Bundle(path: path)
        else { return base }
        return localized
    }
}

/// RecKit's catalog, looked up in the app's language.
///
/// RecKit keeps *keys* in anything it stores and resolves them where they are read (docs/07 rule 3):
/// a sentence resolved when a model was built would outlive the language change the screen is meant
/// to answer, and a SwiftUI body is re-evaluated when the root hands down a new `\.locale`.
public enum RecKitStrings {
    /// RecKit's own catalog. `Bundle.module` is internal to the package, and the shells have to
    /// name it to resolve a key RecKit gave them.
    public static let bundle = Bundle.module

    public nonisolated static func localized(_ key: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            bundle: AppLanguage.bundle(in: bundle),
            locale: AppLanguage.locale
        )
    }

    /// The parameterised half. The argument is never translated — a secret name, an HTTP status, a
    /// count — so every sentence that takes one spells it `%@` and it is formatted in as it stands.
    public nonisolated static func localized(_ key: String, _ argument: String) -> String {
        String(format: localized(key), locale: AppLanguage.locale, arguments: [argument])
    }
}

/// The same two ends over the *app's* own catalog, for the surfaces SwiftUI's `\.locale` does not
/// reach: an `NSAlert`, an `NSMenu` item, a `UNNotificationContent`, a `BGContinuedProcessingTask`.
///
/// A key the catalog does not know comes back as it stands, which is what makes it safe to hand a
/// sentence that was already built with an argument straight back through here.
public enum AppStrings {
    public nonisolated static func localized(_ key: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            bundle: AppLanguage.bundle(in: .main),
            locale: AppLanguage.locale
        )
    }

    public nonisolated static func localized(_ key: String, _ argument: String) -> String {
        String(format: localized(key), locale: AppLanguage.locale, arguments: [argument])
    }
}

/// [AppStrings.localized], spelled short — the **app's** catalog, which is what a shell screen
/// resolves its own keys in.
///
/// Every screen in the four apps declared a private `loc(_:)` of exactly this body, twelve of them
/// between them, because docs/07 rule 3 has each view resolve its keys where it draws them and a
/// body full of `AppStrings.localized(…)` reads as plumbing rather than as words.
///
/// RecKit's own shared views do **not** use it: their sentences live in RecKit's catalog and they
/// each declare a `loc` of their own over [RecKitStrings], which is the one thing this must not be
/// mistaken for.
public nonisolated func loc(_ key: String) -> String {
    AppStrings.localized(key)
}

/// The parameterised half. The argument is never translated — a count, a device name, a secret name.
public nonisolated func loc(_ key: String, _ argument: String) -> String {
    AppStrings.localized(key, argument)
}
