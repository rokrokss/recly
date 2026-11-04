#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "Transcript reader" — "Version history": the transcripts this device kept of the recording, newest first; every
/// one but the transcript shown can be brought back (docs/08 "Versions").
struct VersionHistoryPanel: View {
    let versions: [TranscriptVersion]?
    /// The version being brought back now.
    let restoring: String?
    /// Why the last restore did not happen, as a key.
    let refusal: String?
    let restore: (String) -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            if let versions {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(versions.enumerated()), id: \.element.id) { index, version in
                        row(version, first: index == 0)
                    }
                }
            } else {
                LoadingText(text: loc("Loading…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
            }
            if let refusal {
                Text(verbatim: loc(refusal))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .accessibilityIdentifier("versions-refusal")
            }
            Text(verbatim: loc("This device keeps the last 20 versions. Restoring one saves it as the transcript and keeps the one it replaces."))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ version: TranscriptVersion, first: Bool) -> some View {
        let when = Self.when(version.at, locale: locale)
        return HStack(spacing: Space.s) {
            VStack(alignment: .leading, spacing: Space.xs) {
                Text(verbatim: Self.kindLabel(version.kind))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                Text(verbatim: when)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if !first {
                if restoring == version.id {
                    LoadingText(text: loc("Restoring…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                } else {
                    // At the status badge's size, as a time button is (2026-11-04); the row keeps the touch minimum.
                    BlueprintButton(loc("Restore"), tone: .quiet, compact: true) { restore(version.id) }
                        .disabled(restoring != nil)
                        .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Restore the version from %@", when)))
                        .accessibilityIdentifier("version-restore-\(version.id)")
                }
            }
        }
        .frame(minHeight: minTouch)
        .padding(.vertical, Space.xs)
    }

    /// What made a version, as the list says it.
    static func kindLabel(_ kind: TranscriptVersionKind) -> String {
        switch kind {
        case .transcribed: return RecKitStrings.localized("Transcribed")
        case .edited: return RecKitStrings.localized("Edited")
        case .corrected: return RecKitStrings.localized("Corrected")
        case .restored: return RecKitStrings.localized("Restored")
        case .received: return RecKitStrings.localized("Changed on another device")
        }
    }

    /// A version's time — ISO 8601 from the core — as a date with a time, in this device's locale and time zone.
    static func when(_ iso: String, locale: Locale) -> String {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = parser.date(from: iso) ?? ISO8601DateFormatter().date(from: iso) else { return iso }
        return date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(locale))
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

extension TranscriptMatch {
    /// docs/09 "Transcript reader": where the words of [segments] are in the groups — the tint of an answer's changes
    /// (docs/09 "Ask") — in UTF-16 units, as the groups join their segments' trimmed words with a space.
    static func segments(_ segments: Set<Int>, in groups: [TranscriptGroup], of transcript: Transcript) -> [TranscriptMatch] {
        guard !segments.isEmpty else { return [] }
        return groups.flatMap { group -> [TranscriptMatch] in
            var offset = 0
            var found: [TranscriptMatch] = []
            for index in group.segments where transcript.segments.indices.contains(index) {
                let length = transcript.segments[index].text.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count
                if segments.contains(index) { found.append(TranscriptMatch(group: group.id, offset: offset, length: length)) }
                offset += length + 1
            }
            return found
        }
    }
}
#endif
