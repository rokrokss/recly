#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "AI view" — "Correct transcript": the user's request — what to fix, with anything pasted under it that helps —
/// and its outcome when nothing was saved. A correction that saved closes it (docs/08 "Correcting"). Drawn in Ask's frame:
/// the Mac's card over the detail, the phone's full-height sheet.
struct CorrectPanel: View {
    @Binding var request: String
    let running: Bool
    let notice: CorrectionNotice?
    let correct: () -> Void
    @FocusState private var focused: Bool
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            field
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .contentDirection(request)
                .fieldBox(focused: focused, inset: EdgeInsets())
                .disabled(running)
                .accessibilityLabel(Text(verbatim: loc("Correct transcript")))
                .accessibilityIdentifier("correct-request")
            Text(verbatim: loc("Saved at once. Version history keeps the transcript as it was."))
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: Space.s) {
                Spacer(minLength: 0)
                BlueprintButton(loc("Correct"), tone: .primary, action: send)
                    .disabled(running || typed.isEmpty)
                    // docs/09 "AI view": Return is a new line in the request; ⌘Return corrects.
                    .keyboardShortcut(.return, modifiers: .command)
                    .accessibilityIdentifier("correct-send")
            }
            outcome
        }
        .onAppear { if !running { focused = true } }
        .onChange(of: request) { _, typed in
            let max = Int(CorrectionRequest.shared.MAX)
            if typed.utf16.count > max { request = String(typed.utf16.prefix(max)) ?? String(typed.prefix(max)) }
        }
    }

    @ViewBuilder
    private var outcome: some View {
        if running {
            LoadingText(text: loc("Correcting…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                .accessibilityIdentifier("correct-running")
        } else if let notice {
            switch notice {
            case .nothing:
                muted(loc("Nothing to correct"))
            case .refused(let key):
                muted(loc(key))
            case .failed(let reason):
                ChatGptFailureNotice(reason: reason, failed: loc("Could not correct the transcript"), retrying: false, retry: send)
                    .accessibilityIdentifier("correct-failed")
            }
        }
    }

    private func muted(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.sans(TypeSize.small))
            .foregroundStyle(blueprint.palette.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("correct-notice")
    }

    /// The request: a new line on Return, as a memo's, from six lines up to sixteen, scrolling past them.
    @ViewBuilder
    private var field: some View {
        let placeholder = loc("What to fix, and anything that helps: names, terms, background, or another transcript of the same conversation.")
        #if os(macOS)
        GrowingTextEditor(text: $request, placeholder: placeholder, lines: 6...16, focused: $focused)
            .padding(FieldInset.textView)
        #else
        TextField("", text: $request, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette), axis: .vertical)
            .lineLimit(6...16)
            .textFieldStyle(.plain)
            .submitLabel(.return)
            .focused($focused)
            .padding(FieldInset.field)
        #endif
    }

    private var typed: String { request.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func send() {
        guard !running, !typed.isEmpty else { return }
        focused = false
        correct()
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

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
                    BlueprintButton(loc("Restore"), tone: .quiet) { restore(version.id) }
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
    /// docs/09 "Transcript reader": where the words of [segments] are in the groups — a correction's tint — in UTF-16
    /// units, as the groups join their segments' trimmed words with a space.
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

/// docs/09 "AI view": what a correction came to when it saved nothing, in its sheet.
public enum CorrectionNotice: Equatable {
    /// ChatGPT found nothing to change.
    case nothing
    /// The core refused before asking; [key] is the reason's localization key (`Transcribing…`).
    case refused(String)
    /// The request failed; [reason] is a `CoreMessage` wire code.
    case failed(String)
}

/// docs/09 "Transcript reader" — "After a correction": the segments it changed, how many speakers it named, and the
/// version that undoes it.
public struct CorrectionMarks: Equatable {
    public let segments: Set<Int>
    public let names: Int
    public let previousVersionId: String?
}
