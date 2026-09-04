import Foundation

/// docs/09 화면 원칙 2: what the ledger's monospace columns say. Numbers and clock faces, never
/// prose — but still through the platform's own formatters, because docs/07 rule 7 keeps dates and
/// times in the user's locale even when they are two digits wide.
public enum LedgerFormat {
    /// A recording that has not been finalized has no length yet, and a blank column reads like a
    /// missing value rather than like one that is not in yet.
    public static let noLength = "--:--"

    /// `08-29`, month first in every language.
    ///
    /// docs/09 화면 원칙 2: this column is a fixed-width pattern, not prose — a locale that writes
    /// the day first (Korean gave `02/09`) would make the same two numbers mean two different
    /// things on two devices, and the desktop already writes `MM-dd`. The date in *words* — the row
    /// announcement's [startedAt] — is still the locale's own, which is what docs/07 rule 7 is
    /// about.
    public static func date(_ iso: String) -> String {
        guard let date = parse(iso) else { return "" }
        return date.formatted(
            .verbatim(
                "\(month: .twoDigits)-\(day: .twoDigits)",
                timeZone: .autoupdatingCurrent,
                calendar: .autoupdatingCurrent
            )
        )
    }

    /// `15:04`, twenty-four hour where the locale is.
    public static func time(_ iso: String) -> String {
        guard let date = parse(iso) else { return "" }
        return date.formatted(
            Date.FormatStyle(locale: AppLanguage.locale)
                .hour(.twoDigits(amPM: .omitted))
                .minute(.twoDigits)
        )
    }

    /// The whole stamp, for the one place a row says when it was rather than showing two columns.
    public static func startedAt(_ iso: String) -> String {
        guard let date = parse(iso) else { return iso }
        return date.formatted(
            Date.FormatStyle(locale: AppLanguage.locale).month().day().hour().minute()
        )
    }

    /// `42:10`, or `1:02:33` past the hour — the same shape the recorder's own clock uses.
    public static func length(_ seconds: Double?) -> String {
        guard let seconds, seconds >= 0 else { return noLength }
        return elapsed(Int(seconds.rounded(.down)))
    }

    public static func elapsed(_ seconds: Int) -> String {
        seconds >= 3600
            ? String(format: "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            : String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    /// docs/09 화면 원칙 2: the row is one accessibility element, and this is the sentence it says —
    /// the state in words rather than as the code the badge draws.
    public static func announce(title: String, at: String, length: String, state: String) -> String {
        UiMessage.key(
            "%1$@, recorded %2$@, length %3$@, %4$@",
            args: [.verbatim(title), .verbatim(at), .verbatim(length), .verbatim(state)]
        ).text
    }

    /// The core writes `startedAt` as ISO-8601 with milliseconds (docs/03); a stamp without them is
    /// still read rather than dropped.
    private static func parse(_ iso: String) -> Date? {
        withFraction.date(from: iso) ?? plain.date(from: iso)
    }

    private static let withFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let plain = ISO8601DateFormatter()
}
