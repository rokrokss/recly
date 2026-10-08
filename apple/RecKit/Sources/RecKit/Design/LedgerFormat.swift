import Foundation

/// docs/09 screen principle 2: what the ledger's monospace columns say. Numbers and clock faces, never
/// prose — fixed-width patterns that read the same in every language. Only the sentence a row says
/// ([startedAt]) goes through the locale's own formatter, which is what docs/07 rule 7 is about.
public enum LedgerFormat {
    /// A recording that has not been finalized has no length yet, and a blank column reads like a
    /// missing value rather than like one that is not in yet.
    public static let noLength = "--:--"

    /// `08-29`, month first in every language.
    ///
    /// docs/09 screen principle 2: this column is a fixed-width pattern, not prose — a locale that writes
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

    /// `15:04`, twenty-four hour in every language.
    ///
    /// docs/09 screen principle 2: the same fixed-width pattern as [date]. A twelve-hour locale's clock
    /// does not fit it — without the day period `03:05` is either end of the day, and with it the
    /// column is no longer five wide. The spoken [startedAt] keeps the locale's own clock.
    public static func time(_ iso: String) -> String {
        guard let date = parse(iso) else { return "" }
        return date.formatted(
            .verbatim(
                "\(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)):\(minute: .twoDigits)",
                timeZone: .autoupdatingCurrent,
                calendar: .autoupdatingCurrent
            )
        )
    }

    /// The whole stamp, for the one place a row says when it was rather than showing two columns.
    public static func startedAt(_ iso: String) -> String {
        guard let date = parse(iso) else { return iso }
        return date.formatted(
            Date.FormatStyle(locale: AppLanguage.locale).month().day().hour().minute()
        )
    }

    /// `42:10`, or `01:02:33` past the hour — [elapsed]'s shape, which is what Android and Windows
    /// write in the same column.
    public static func length(_ seconds: Double?) -> String {
        guard let seconds, seconds >= 0 else { return noLength }
        return elapsed(Int(seconds.rounded(.down)))
    }

    /// `00:12:34` whatever the length — what a screen reader is told (the waveform's position, a
    /// highlight's name). The UX decisions of 2026-10-08 §3 leave spoken text as it was; what is
    /// drawn goes through [elapsed] or [stamp].
    public static func clock(_ seconds: Int) -> String {
        let total = max(0, seconds)
        return String(format: "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    /// docs/09 "Typography" (2026-10-08 §3): `MM:SS` under an hour and `HH:MM:SS` from one — the live
    /// timers (record screen, menu bar, watches, the `Highlight · …` line while recording) and the
    /// ledger's length column. The hours are not wrapped at 24, because a recording is not a clock.
    public static func elapsed(_ seconds: Int) -> String {
        let total = max(0, seconds)
        return total >= 3600
            ? String(format: "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
            : String(format: "%02d:%02d", total / 60, total % 60)
    }

    /// 2026-10-08 §3: a moment inside one recording's screen — the playback clock, a transcript time, a
    /// highlight and its menu — in the shape the recording's whole length takes, so every time on
    /// that screen is one width. A recording whose length is not known uses the time itself.
    public static func stamp(_ seconds: Int, total: Double?) -> String {
        guard let total, total > 0 else { return elapsed(seconds) }
        return Int(total.rounded(.down)) >= 3600 || seconds >= 3600 ? clock(seconds) : elapsed(seconds)
    }

    /// docs/09 screen principle 2: the row is one accessibility element, and this is the sentence it says —
    /// the state in words rather than as the code the badge draws.
    ///
    /// - Parameter preview: the transcript's first words the row draws under its title (2026-10-08 §8),
    ///   said right after the title, as they are seen.
    public static func announce(title: String, preview: String? = nil, at: String, length: String, state: String) -> String {
        let heading = preview.map { "\(title), \($0)" } ?? title
        return UiMessage.key(
            "%1$@, recorded %2$@, length %3$@, %4$@",
            args: [.verbatim(heading), .verbatim(at), .verbatim(length), .verbatim(state)]
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
