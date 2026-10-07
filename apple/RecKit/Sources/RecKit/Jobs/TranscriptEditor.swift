#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/08 "Editing" · docs/09 "Editing and speakers": what the editor holds until Save — the words of every
/// segment, who says each, and what the speakers are called — and the one batch of edits that makes the saved
/// transcript of it.
@MainActor
final class TranscriptDraft: ObservableObject {
    let original: Transcript
    @Published var texts: [String]
    @Published private(set) var speakers: [String]
    @Published private(set) var names: [String: String]
    /// Speakers made in this draft, in the order made — the ids the core will give them (`S{n+1}`).
    private var created: [String] = []

    init(_ transcript: Transcript) {
        original = transcript
        texts = transcript.segments.map(\.text)
        speakers = transcript.segments.map(\.speaker)
        names = Dictionary(uniqueKeysWithValues: transcript.speakers.map { ($0.id, $0.name ?? "") })
    }

    var changed: Bool { !edits.isEmpty }

    /// Every speaker id in the draft, in order of first appearance.
    var speakerIds: [String] {
        var seen: [String] = []
        for id in speakers where !id.isEmpty && !seen.contains(id) { seen.append(id) }
        return seen
    }

    func label(_ id: String) -> String {
        let name = names[id]?.trimmingCharacters(in: .whitespaces) ?? ""
        return name.isEmpty ? id : name
    }

    /// Segment [index] to speaker [id], or to a new one. A transcript nobody was identified in gets its
    /// first speaker everywhere, as the core does; the user moves the other lines off it.
    func setSpeaker(_ index: Int, _ id: String?) {
        let target = id ?? newSpeaker()
        if speakerIds.isEmpty {
            speakers = speakers.map { _ in target }
        } else {
            speakers[index] = target
        }
    }

    func rename(_ id: String, _ name: String) { names[id] = name }

    /// `S{n+1}` after the highest `S` number there is — the core's own rule, so the edits below name
    /// the speaker it will make.
    private func newSpeaker() -> String {
        let numbers = (original.speakers.map(\.id) + speakers + created).compactMap { id -> Int? in
            guard id.hasPrefix("S") else { return nil }
            return Int(id.dropFirst())
        }
        let id = "S\((numbers.max() ?? 0) + 1)"
        created.append(id)
        return id
    }

    /// The edits, in an order the core can apply: each new speaker made on its first line (the core
    /// numbers them `S{n+1}` in that order, so a speaker made and then left unused is skipped and the
    /// ones after it renumbered), then every other line moved, then the words, then the names.
    var edits: [any TranscriptEdit] {
        let base = original.speakers.compactMap { $0.id.hasPrefix("S") ? Int($0.id.dropFirst()) : nil }.max() ?? 0
        let made = created.filter { speakers.contains($0) }
        var real: [String: String] = [:]
        for (offset, id) in made.enumerated() { real[id] = "S\(base + offset + 1)" }
        func actual(_ id: String) -> String { real[id] ?? id }

        var edits: [any TranscriptEdit] = []
        var current = original.segments.map(\.speaker)
        for id in made {
            guard let first = speakers.firstIndex(of: id) else { continue }
            edits.append(TranscriptEditSetSpeaker(segmentIndex: Int32(first), speakerId: nil))
            if current.allSatisfy(\.isEmpty) { current = current.map { _ in actual(id) } } else { current[first] = actual(id) }
        }
        for (index, id) in speakers.enumerated() where !id.isEmpty && actual(id) != current[index] {
            edits.append(TranscriptEditSetSpeaker(segmentIndex: Int32(index), speakerId: actual(id)))
        }
        for (index, text) in texts.enumerated() {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed != original.segments[index].text.trimmingCharacters(in: .whitespacesAndNewlines) {
                edits.append(TranscriptEditSetText(segmentIndex: Int32(index), text: trimmed))
            }
        }
        for id in speakerIds {
            let name = names[id]?.trimmingCharacters(in: .whitespaces) ?? ""
            let was = original.speakers.first { $0.id == id }?.name?.trimmingCharacters(in: .whitespaces) ?? ""
            if name != was { edits.append(TranscriptEditRenameSpeaker(speakerId: actual(id), name: name.isEmpty ? nil : name)) }
        }
        return edits
    }
}

/// docs/09 "Edit mode": one plain field per segment, each with its time (a seek — the player stays
/// usable) and its speaker. The phone's Cancel · Save sit under the fields, above the keyboard.
struct TranscriptEditor: View {
    @ObservedObject var draft: TranscriptDraft
    let canSeek: Bool
    /// The storage the files are rewritten in: the agent line is about Drive only.
    let drive: Bool
    let saving: Bool
    let onSeek: (Double) -> Void
    let cancel: () -> Void
    let save: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @State private var renaming: String?
    @State private var typedName = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Space.m) {
                    ForEach(Array(draft.original.segments.enumerated()), id: \.offset) { index, segment in
                        row(index, segment)
                    }
                    Text(verbatim: RecKitStrings.localized(drive
                        ? "Saving updates the transcript files in your storage. It does not start your agent again."
                        : "Saving updates the transcript files in your storage."))
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(Space.m)
            }
            #if os(iOS)
            HairLine()
            HStack(spacing: Space.s) {
                Spacer(minLength: 0)
                EditorButtons(changed: draft.changed, saving: saving, cancel: cancel, save: save)
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
            .background(blueprint.palette.surface)
            #endif
        }
        .blueprintDialogOverlay(isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            SpeakerNameDialog(name: $typedName) {
                if let id = renaming { draft.rename(id, typedName) }
                renaming = nil
            } cancel: {
                renaming = nil
            }
        }
    }

    private func row(_ index: Int, _ segment: TranscriptSegment) -> some View {
        let stamp = LedgerFormat.clock(Int(segment.start))
        let speaker = draft.speakers[index]
        return VStack(alignment: .leading, spacing: Space.xs) {
            HStack(spacing: Space.xs) {
                BlueprintButton(stamp, tone: .quiet, mono: true) { onSeek(segment.start) }
                    .disabled(!canSeek)
                    .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Go to %@", stamp)))
                DraftSpeakerMenu(draft: draft, index: index) {
                    typedName = draft.names[$0] ?? ""
                    renaming = $0
                } label: {
                    if speaker.isEmpty {
                        Text(verbatim: RecKitStrings.localized("Add speaker"))
                            .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                            .foregroundStyle(blueprint.palette.accent)
                            .frame(minHeight: minTouch)
                            .contentShape(Rectangle())
                    } else {
                        SpeakerBadge(text: draft.label(speaker), mono: draft.label(speaker) == speaker)
                    }
                }
                .accessibilityIdentifier("edit-speaker-\(index)")
            }
            TextField("", text: Binding(get: { draft.texts[index] }, set: { draft.texts[index] = $0 }), axis: .vertical)
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .padding(.horizontal, 10)
                .padding(.vertical, 9)
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
                }
                .accessibilityLabel(Text(verbatim: stamp))
                .accessibilityIdentifier("edit-text-\(index)")
        }
    }
}

/// The editor's `Cancel` · `Save` — `Done` alone while nothing has changed (docs/09 "Editing and speakers").
struct EditorButtons: View {
    let changed: Bool
    let saving: Bool
    let cancel: () -> Void
    let save: () -> Void
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        if saving {
            LoadingText(text: RecKitStrings.localized("Saving…"), font: blueprint.fonts.sans(TypeSize.small), color: blueprint.palette.textMuted)
                .frame(minHeight: minTouch)
        } else if changed {
            BlueprintButton(RecKitStrings.localized("Cancel"), tone: .quiet, minWidth: minTouch, action: cancel)
            BlueprintButton(RecKitStrings.localized("Save"), tone: .primary, action: save)
                .accessibilityIdentifier("edit-save")
        } else {
            BlueprintButton(RecKitStrings.localized("Done"), tone: .quiet, action: cancel)
                .accessibilityIdentifier("edit-done")
        }
    }
}

/// The speaker menu on a draft line: rename, or move the line — applied to the draft, saved with it.
private struct DraftSpeakerMenu<Label: View>: View {
    @ObservedObject var draft: TranscriptDraft
    let index: Int
    let rename: (String) -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        let current = draft.speakers[index]
        Menu {
            if !current.isEmpty {
                Button(RecKitStrings.localized("Rename speaker")) { rename(current) }
                Menu(RecKitStrings.localized("Change speaker for this line")) {
                    ForEach(draft.speakerIds.filter { $0 != current }, id: \.self) { id in
                        Button(draft.label(id)) { draft.setSpeaker(index, id) }
                    }
                    Button(RecKitStrings.localized("New speaker")) { draft.setSpeaker(index, nil) }
                }
            } else {
                ForEach(draft.speakerIds, id: \.self) { id in
                    Button(draft.label(id)) { draft.setSpeaker(index, id) }
                }
                Button(RecKitStrings.localized("New speaker")) { draft.setSpeaker(index, nil) }
            }
        } label: {
            label()
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
    }
}

/// docs/09 "Editing and speakers": `Speaker name` — the name, prefilled; an empty one takes the name away and
/// the id shows again.
struct SpeakerNameDialog: View {
    @Binding var name: String
    let save: () -> Void
    let cancel: () -> Void
    @Environment(\.blueprint) private var blueprint
    @FocusState private var focused: Bool

    var body: some View {
        BlueprintDialog(title: RecKitStrings.localized("Speaker name")) {
            BlueprintButton(RecKitStrings.localized("Cancel"), tone: .quiet, minWidth: minTouch, action: cancel)
            BlueprintButton(RecKitStrings.localized("Save"), tone: .primary, action: save)
                .accessibilityIdentifier("speaker-name-save")
        } content: {
            TextField("", text: $name, prompt: Text(verbatim: RecKitStrings.localized("Name")).foregroundColor(blueprint.palette.textMuted))
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .padding(.horizontal, 10)
                .padding(.vertical, 9)
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.node)
                        .strokeBorder(focused ? blueprint.palette.accent : blueprint.palette.inputBorder, lineWidth: blueprint.line)
                }
                .focused($focused)
                .onSubmit(save)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Name")))
                .accessibilityIdentifier("speaker-name-field")
                .onAppear { focused = true }
        }
    }
}
#endif
