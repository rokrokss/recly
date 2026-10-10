import Foundation
#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI
#endif

/// docs/09 "Playback": how fast the detail plays and whether it jumps the silences — this device's
/// preferences, kept here and never synced.
public enum PlaybackPreferences {
    public static let rates: [Double] = [0.75, 1, 1.25, 1.5, 1.75, 2]

    public static var rate: Double {
        get {
            let stored = UserDefaults.standard.double(forKey: rateKey)
            return rates.contains(stored) ? stored : 1
        }
        set { UserDefaults.standard.set(newValue, forKey: rateKey) }
    }

    public static var skipSilence: Bool {
        get { UserDefaults.standard.bool(forKey: skipKey) }
        set { UserDefaults.standard.set(newValue, forKey: skipKey) }
    }

    /// `1×`, `1.25×`: data, in mono, the same in every language.
    public static func label(_ rate: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        return (formatter.string(from: NSNumber(value: rate)) ?? "1") + "×"
    }

    private static let rateKey = "playbackRate"
    private static let skipKey = "playbackSkipSilence"
}

#if os(iOS) || os(macOS)

/// docs/09 "Playback": the one quiet control for both — the speed in mono, a small accent dot at its
/// top-end corner while silences are skipped, and a menu of the speeds with the switch under them.
struct PlaybackSpeedChip: View {
    @ObservedObject var player: RecordingPlayer
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        Menu {
            ForEach(PlaybackPreferences.rates, id: \.self) { rate in
                Button {
                    player.setRate(rate)
                } label: {
                    if rate == player.rate {
                        Label(PlaybackPreferences.label(rate), systemImage: "checkmark")
                    } else {
                        Text(verbatim: PlaybackPreferences.label(rate))
                    }
                }
            }
            Divider()
            Toggle(isOn: Binding(get: { player.skipSilence }, set: { player.setSkipSilence($0) })) {
                Text(verbatim: RecKitStrings.localized("Skip silence"))
            }
        } label: {
            Text(verbatim: PlaybackPreferences.label(player.rate))
                .font(blueprint.fonts.monoBodySmall)
                .foregroundStyle(blueprint.palette.textMuted)
                // 2026-10-10 (2.13): `1×` in every language, never `×1`.
                .leftToRight()
                .padding(.horizontal, Space.s)
                .frame(minWidth: minTouch, minHeight: minTouch)
                .overlay {
                    // 2026-10-08 §12: a dropdown box clears 3:1 against the page, light and dark. The
                    // box is the whole target, as the buttons beside it are, so they stand one height.
                    RoundedRectangle(cornerRadius: Radius.node)
                        .strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
                }
                .overlay(alignment: .topTrailing) {
                    if player.skipSilence {
                        Rectangle()
                            .fill(blueprint.palette.accent)
                            .frame(width: 6, height: 6)
                            .padding(.top, -3)
                            .padding(.trailing, -3)
                    }
                }
                .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
        // The speeds in reading order, slowest first, wherever the menu opens.
        .menuOrder(.fixed)
        .buttonStyle(.plain)
        .fixedSize()
        .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Speed")))
        .accessibilityValue(Text(verbatim: player.skipSilence
            ? RecKitStrings.localized("Speed %@, skip silence on", PlaybackPreferences.label(player.rate))
            : RecKitStrings.localized("Speed %@", PlaybackPreferences.label(player.rate))))
        .accessibilityIdentifier("playback-speed")
    }
}
#endif
