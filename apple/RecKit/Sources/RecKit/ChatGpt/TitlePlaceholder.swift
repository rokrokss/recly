#if os(iOS) || os(macOS)
import Foundation

/// docs/08 "Titles and speaker names": the title field's placeholder in the sheet after Stop — `Filled in automatically`
/// while the recording's publication fills in a title left empty ([ChatGptSettingsModel.autoFill], with transcription
/// on), else `Untitled`.
public enum TitlePlaceholder {
    public static func text(autoFill: Bool) -> String {
        RecKitStrings.localized(autoFill ? "Filled in automatically" : "Untitled")
    }
}
#endif
