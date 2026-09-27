import Foundation
import ReclyCore
import SwiftUI

/// docs/05 "고정 처리 설정 도입": the one download of the on-device speech model a shell runs. The
/// settings row, the banner, a waiting recording's row and the first-run card all read this and
/// start or cancel through it, so two of them can never be two downloads.
///
/// Every download goes through `ReclyCore.prepareLocalEngine`, because that is what resumes the
/// recordings waiting for the model.
@MainActor
public final class ModelDownload: ObservableObject {
    /// A capture is starting, running or stopping, and the model is not downloaded then (docs/05).
    /// The shell keeps this in step with its recorder.
    @Published public var capturing = false
    @Published public private(set) var downloading = false
    /// How much of the model is here, 0–1, while it downloads and the engine can say.
    @Published public private(set) var progress: Double?
    /// What went wrong with the last download, for the settings row and the card.
    @Published public private(set) var message: UiMessage?
    /// The saved transcription mode, and the engine's answer for the saved language — what the
    /// first-run card is decided from ([ModelPrompt.visible]).
    @Published public private(set) var savedMode: TranscriptionMode?
    @Published public private(set) var savedStatus: LocalEngineStatus?
    /// A download ended, whichever way it ended: the shell wakes its runner for what it resumed.
    public var onFinished: (() -> Void)?

    private let core: ReclyCore_
    private var task: Task<Void, Never>?
    private var cancelled = false

    public init(core: ReclyCore_) {
        self.core = core
    }

    /// False for the placeholder engine an OS without on-device transcription gets.
    public var engineInstalled: Bool {
        !(core.deps.localTranscription is UnavailableLocalTranscriptionEngine)
    }

    /// [waiting] is whether any recording is already waiting for the model: the banner is then the
    /// one prompt, with its count, and the card stays away.
    public func promptVisible(dismissed: Bool, waiting: Bool) -> Bool {
        ModelPrompt.visible(
            mode: savedMode, engineInstalled: engineInstalled, status: savedStatus,
            dismissed: dismissed, capturing: capturing, waiting: waiting
        )
    }

    /// [language] is a waiting recording's own, from its row; nil is the saved settings' language.
    public func start(language: String? = nil) {
        guard task == nil, !capturing else { return }
        downloading = true
        progress = nil
        message = nil
        cancelled = false
        task = Task { await run(language: language) }
    }

    /// The partial download is the system's to keep or drop; nothing here deletes it.
    public func cancel() {
        guard let task else { return }
        cancelled = true
        task.cancel()
        (core.deps.localTranscription as? ModelDownloadCancelling)?.cancelDownload()
    }

    /// Reads the saved mode and the engine's status for the saved language again.
    public func refresh() async {
        do {
            let transcription = try await core.initializeProcessing().document.settings.transcription
            savedMode = transcription.mode
            savedStatus = transcription.mode == .local
                ? try await core.localEngineInfo(language: Self.code(transcription.language)).status
                : nil
        } catch {
            // Settings that cannot be read have nothing to prompt about.
            savedMode = nil
            savedStatus = nil
        }
    }

    private func run(language requested: String?) async {
        let saved = requested == nil
            ? try? await core.initializeProcessing().document.settings.transcription.language
            : nil
        if let language = requested ?? saved.map(Self.code) {
            let poll = Task { await self.poll(language) }
            do {
                _ = try await core.prepareLocalEngine(language: language)
            } catch {
                if !cancelled { message = .key("Failed: %@", args: [.verbatim(error.localizedDescription)]) }
            }
            poll.cancel()
        }
        // Read before the flag drops, so the card does not flash its buttons on the way out.
        await refresh()
        downloading = false
        progress = nil
        task = nil
        onFinished?()
    }

    private func poll(_ language: String) async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled,
                  let info = try? await core.localEngineInfo(language: language),
                  info.downloading
            else { continue }
            progress = info.progress?.doubleValue
        }
    }

    /// The core's wire code for a language (`zh_cn` → `zh-cn`).
    static func code(_ language: Language) -> String {
        language.name.lowercased().replacingOccurrences(of: "_", with: "-")
    }

    /// "Downloading model… 42%", and the plain line until the engine has said how far it is.
    public nonisolated static func progressText(_ progress: Double?) -> String {
        guard let progress else { return RecKitStrings.localized("Downloading model…") }
        let percent = Int((min(max(progress, 0), 1) * 100).rounded(.down))
        return String(format: RecKitStrings.localized("Downloading model… %d%%"), locale: AppLanguage.locale, percent)
    }
}

/// An engine whose model download the shell can stop. The engine's own `cancel()` is for a
/// transcription that is running, which a Cancel on the download must not touch.
protocol ModelDownloadCancelling: AnyObject {
    func cancelDownload()
}

/// docs/05 "고정 처리 설정 도입": the first-run card is for a device that would transcribe here and
/// cannot yet — never for one that already has the model, or is recording, and never beside the
/// banner of recordings already waiting for it, which says the same thing with a count.
public enum ModelPrompt {
    public static func visible(
        mode: TranscriptionMode?,
        engineInstalled: Bool,
        status: LocalEngineStatus?,
        dismissed: Bool,
        capturing: Bool,
        waiting: Bool
    ) -> Bool {
        mode == .local && engineInstalled && status == .modelRequired && !dismissed && !capturing && !waiting
    }
}

/// The "Download model" a waiting recording's row leads with, in the primary tone. Absent while the
/// download runs: the banner carries its progress then, and one download is all there is.
public struct ModelDownloadButton: View {
    @ObservedObject private var download: ModelDownload
    private let language: String?

    public init(download: ModelDownload, language: String? = nil) {
        self.download = download
        self.language = language
    }

    public var body: some View {
        if !download.downloading {
            BlueprintButton(RecKitStrings.localized("Download model"), tone: .primary) {
                download.start(language: language)
            }
            .disabled(download.capturing)
        }
    }
}

#if os(iOS) || os(macOS)
/// docs/05 "고정 처리 설정 도입": once, above the recording screen, for a device set to transcribe
/// here that has no model yet. "Not now" is remembered by the shell and the card does not come back.
public struct ModelPromptCard: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @ObservedObject private var download: ModelDownload
    private let dismissed: Bool
    private let waiting: Bool
    private let dismiss: () -> Void

    /// - Parameter waiting: whether a recording is already waiting for the model (its banner is up).
    public init(download: ModelDownload, dismissed: Bool, waiting: Bool, dismiss: @escaping () -> Void) {
        self.download = download
        self.dismissed = dismissed
        self.waiting = waiting
        self.dismiss = dismiss
    }

    public var body: some View {
        if download.promptVisible(dismissed: dismissed, waiting: waiting) {
            VStack(alignment: .leading, spacing: Space.s) {
                Text(verbatim: loc("Transcribe on this device"))
                    .font(blueprint.fonts.sans(TypeSize.bodySmall, weight: .medium))
                    .foregroundStyle(blueprint.palette.text)
                // Apple does not say how big its speech assets are, so the body names no size.
                Text(verbatim: loc("On-device transcription needs Apple’s speech recognition model."))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                if download.downloading {
                    HStack(spacing: Space.s) {
                        LoadingText(
                            text: ModelDownload.progressText(download.progress),
                            font: blueprint.fonts.sans(TypeSize.small),
                            color: blueprint.palette.textMuted
                        )
                        Spacer(minLength: 0)
                        BlueprintButton(loc("Cancel download"), tone: .quiet) { download.cancel() }
                    }
                } else {
                    if let message = download.message {
                        Text(verbatim: message.text)
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.danger)
                    }
                    // docs/09 화면 원칙 8: the answers end-aligned, the quiet one first.
                    FlowLayout(alignment: .trailing) {
                        BlueprintButton(loc("Not now"), tone: .quiet, action: dismiss)
                        BlueprintButton(loc("Download model"), tone: .primary) { download.start() }
                            .accessibilityIdentifier("model-prompt-download")
                    }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .padding(12)
            .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(BadgeTone.accent.edge(blueprint.palette), lineWidth: blueprint.line)
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
            .accessibilityIdentifier("model-prompt")
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
