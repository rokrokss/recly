import SwiftUI

/// docs/07 rule 2·3: the device's own language setting, and the screen follows it at once — the root
/// hands the new locale down and every row is resolved again.
///
/// A row that names the language the app is in, with the rest of them behind it: the list of
/// languages grows, and a row stays one row however long that list gets. The same dropdown box on
/// both platforms (2026-10-10, A-A8), with the platform's own menu behind it.
///
/// Every label is the language's own name and is never translated (docs/07 rule 1), so whoever
/// cannot read the language the app is currently in can still find the one they want. There is no
/// "system default" among them: what is marked, and what the row says, is the language the app is
/// actually in — for a device that has never been given one, the language it followed the system to.
///
/// The Mac's settings pane and the phone's settings tab drew this block line for line, header
/// included, so it is one block.
public struct LanguageSection: View {
    @ObservedObject private var language: AppLanguage
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(language: AppLanguage) {
        self.language = language
    }

    public var body: some View {
        SectionHeader(loc("Language")).padding(.horizontal, Space.m)
        // The watch has no settings screen (it follows the phone's language), and no dropdown either.
        #if !os(watchOS)
        SectionRow(title: loc("App language")) {
            BlueprintDropdown(
                loc("App language"),
                options: AppLanguage.Choice.choices,
                selection: $language.effective,
                itemIdentifier: { "language-" + $0.rawValue },
                title: title
            )
            .accessibilityIdentifier("language")
        }
        #endif
    }

    /// Concatenated rather than interpolated: an interpolation would make this the key
    /// "language.%@" with an argument, not a key at all.
    private func title(_ choice: AppLanguage.Choice) -> String {
        loc("language." + choice.rawValue)
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// docs/09 "Accessibility": the system's light/dark answer is followed without being asked about, and this is
/// the one override of it ([AppTheme]) — the same section, the same three words, that the Windows
/// settings window draws.
///
/// Chips and not a dropdown on either shell: there are three answers, they fit on a line, and a
/// chosen chip says so itself the way the workflow picker's does (docs/09 screen principle 1). They wrap
/// rather than squeeze, because docs/09 Fluid typography makes the label size the user's.
///
/// The section is the question, so the chips sit in a block of their own rather than under a row
/// that would say "Theme" a second time. The Mac's settings pane and the phone's settings tab draw
/// it line for line, so it is one block.
public struct ThemeSection: View {
    @ObservedObject private var theme: AppTheme
    @Environment(\.locale) private var locale

    public init(theme: AppTheme) {
        self.theme = theme
    }

    public var body: some View {
        SectionHeader(loc("Theme")).padding(.horizontal, Space.m)
        SectionBlock {
            ChoiceRow {
                ForEach(AppTheme.Choice.allCases) { choice in
                    BlueprintChip(choice.label, selected: theme.choice == choice, fill: true) {
                        theme.choice = choice
                    }
                    .accessibilityIdentifier("theme-" + choice.rawValue)
                }
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
