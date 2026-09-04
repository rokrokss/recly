import SwiftUI

/// docs/07 rule 2·3: the device's own language setting, and the screen follows it at once — the root
/// hands the new locale down and every row is resolved again.
///
/// A row that names the language the app is in, with the rest of them behind it: the list of
/// languages grows, and a row stays one row however long that list gets. What opens it is what each
/// platform already has — the Mac's own pop-up menu on the Mac, and a dialog on the phone, where a
/// menu hanging off a tapped row is not the idiom.
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
    @State private var picking = false

    public init(language: AppLanguage) {
        self.language = language
    }

    public var body: some View {
        SectionHeader(loc("Language")).padding(.horizontal, Space.m)
        #if os(macOS)
        SectionRow(title: loc("Language")) {
            BlueprintDropdown(
                loc("Language"),
                options: AppLanguage.Choice.choices,
                selection: $language.effective,
                title: title
            )
            .accessibilityIdentifier("language")
        }
        #else
        Button { picking = true } label: {
            SectionRow(title: loc("Language")) {
                Text(verbatim: title(language.effective))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("language")
        .blueprintDialog(isPresented: $picking) {
            BlueprintDialog(title: loc("Language")) {
                // Nothing to cancel: a choice is applied the moment it is made (rule 3), so the one
                // answer here closes a question that has already been answered.
                BlueprintButton(loc("Close"), tone: .quiet) { picking = false }
            } content: {
                ForEach(AppLanguage.Choice.choices) { choice in
                    BlueprintRadioRow(title(choice), selected: language.effective == choice) {
                        picking = false
                        language.choice = choice
                    }
                    .accessibilityIdentifier("language-" + choice.rawValue)
                }
            }
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

/// docs/09 "접근성": the system's light/dark answer is followed without being asked about, and this is
/// the one override of it ([AppTheme]) — the same section, the same three words, that the Windows
/// settings window draws.
///
/// Chips and not a dropdown on either shell: there are three answers, they fit on a line, and a
/// chosen chip says so itself the way the workflow picker's does (docs/09 화면 원칙 1). They wrap
/// rather than squeeze, because docs/09 유동 타이포 makes the label size the user's.
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
            FlowLayout {
                ForEach(AppTheme.Choice.allCases) { choice in
                    BlueprintChip(choice.label, selected: theme.choice == choice) {
                        theme.choice = choice
                    }
                    .accessibilityIdentifier("theme-" + choice.rawValue)
                }
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
