#if os(iOS) || os(macOS)
import Foundation
import ReclyCore
import SwiftUI

/// docs/15: user-opened policy pages. These are never fetched as part of a recording or a job.
public enum PrivacyLinks {
    public static func recly(locale: Locale) -> URL {
        let page = locale.identifier.hasPrefix("ko") ? "privacy-policy.ko" : "privacy-policy"
        return URL(string: "https://recly.dev/policy/" + page)!
    }

    public static func provider(_ name: String) -> URL? {
        policies[name].flatMap(URL.init(string:))
    }

    // Each provider's official privacy or API data page, checked 2026-09-26 (AssemblyAI 2026-09-29) — the
    // same links as the privacy policy's provider table (docs/15 "Provider retention policies"). The account's service agreement may also apply.
    private static let policies: [String: String] = [
        "assemblyai": "https://www.assemblyai.com/docs/data-retention-and-model-training",
        "openai": "https://developers.openai.com/api/docs/guides/your-data",
        "groq": "https://console.groq.com/docs/your-data",
        "together": "https://www.together.ai/privacy",
        "mistral": "https://legal.mistral.ai/terms/privacy-policy/",
        "deepgram": "https://developers.deepgram.com/trust-security/your-data",
        "elevenlabs": "https://elevenlabs.io/privacy-policy",
        "azure": "https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/speech-to-text/data-privacy-security",
        "rev": "https://www.rev.com/legal/privacy",
        "speechmatics": "https://www.speechmatics.com/legal/privacy-policy",
        "daglo": "https://developers.daglo.ai/privacy",
        "rtzr": "https://developers.rtzr.ai/privacy",
        "clova": "https://privacy.navercloudcorp.com/en/ncp/PrivacyPolicy/ncp-p",
        "gladia": "https://www.gladia.io/privacy-notice",
        // docs/15 §10: a summary's destination, OpenAI's own policy (checked 2026-10-09).
        "chatgpt": "https://openai.com/policies/privacy-policy/",
    ]
}

/// docs/15 "iPhone providers" (App Review 5.1.1(i)): ElevenLabs trains on audio unless the account
/// has turned that off, and Recly cannot see the account — so the user says it has, before allowing.
/// ChatGPT is the same for a summary's transcript: "Improve the model for everyone" is on unless the
/// user turned it off in their data controls.
public enum TrainingOptOut {
    static let provider = "elevenlabs"
    // ElevenLabs' own help page for the "Improve the models for everyone" toggle, checked 2026-09-29.
    static let help = URL(string: "https://elevenlabs.io/docs/help-center/legal/is-my-data-used-to-improve-eleven-labs-ai-models")!
    // OpenAI's own help page for the data controls, checked 2026-10-09.
    static let chatGptHelp = URL(string: "https://help.openai.com/en/articles/7730893")!

    public static func required(_ targets: [TransferTarget]) -> Bool {
        targets.contains { $0.provider == provider || TransferTargetText.summary($0) }
    }
}

/// Who a destination is, as the permission screens name it.
public enum TransferTargetText {
    /// docs/15 §10: the transcript a summary sends to the user's ChatGPT plan.
    public static func summary(_ target: TransferTarget) -> Bool {
        target.kind == TransferTargets.shared.SUMMARIZE && target.provider == TransferTargets.shared.CHATGPT
    }

    public static func name(_ target: TransferTarget) -> String {
        summary(target) ? RecKitStrings.localized("ChatGPT (OpenAI)") : SttProviders.shared.displayName(name: target.provider)
    }
}

/// The disclosure that precedes a parked job's approval.
public struct TransferDisclosureList: View {
    private let targets: [TransferTarget]
    @Binding private var trainingOff: Bool
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    /// [trainingOff] is the answer the allow button waits for when [TrainingOptOut] requires one.
    public init(targets: [TransferTarget], trainingOff: Binding<Bool>) {
        self.targets = targets
        _trainingOff = trainingOff
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            ForEach(targets, id: \.id) { target in
                let summary = TransferTargetText.summary(target)
                VStack(alignment: .leading, spacing: Space.xs) {
                    // Who: the company by name, and what kind of service it is (App Review 5.1.2(i)).
                    Text(verbatim: TransferTargetText.name(target))
                        .font(blueprint.fonts.label)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: summary
                        ? loc("A third-party AI service. Recly does not operate it.")
                        : loc("A third-party AI speech recognition service. Recly does not operate it."))
                        .font(blueprint.fonts.bodySmall)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: target.endpoint)
                        .font(blueprint.fonts.monoSmall)
                        .fixedSize(horizontal: false, vertical: true)
                    // What: a summary sends the transcript's text, never the audio (docs/15 §10).
                    Text(verbatim: summary
                        ? loc("The transcript text of each recording you summarize or ask about is sent here, with the moments you highlighted and what you wrote in Settings → ChatGPT. The audio is not sent.")
                        : loc("The full recording, the language and speaker settings and your vocabulary are sent here for transcription. Retention and training depend on the provider and your account settings."))
                        .font(blueprint.fonts.bodySmall)
                        .fixedSize(horizontal: false, vertical: true)
                    // The list's text colour would turn a plain `Link` into body text; this one reads as a link.
                    if let url = PrivacyLinks.provider(target.provider) {
                        BlueprintDialogLink(loc("Provider privacy information")) { openURL(url) }
                    }
                    if target.provider == TrainingOptOut.provider {
                        Text(verbatim: loc("ElevenLabs uses recordings to improve its models unless this is turned off in your ElevenLabs account."))
                            .font(blueprint.fonts.bodySmall)
                            .fixedSize(horizontal: false, vertical: true)
                        BlueprintDialogLink(loc("How to turn it off")) { openURL(TrainingOptOut.help) }
                        BlueprintCheckRow(loc("I turned off model training in my ElevenLabs account."), isOn: $trainingOff)
                            .accessibilityIdentifier("training-off")
                    }
                    if summary {
                        Text(verbatim: loc("ChatGPT may use what you send to improve its models unless “Improve the model for everyone” is turned off in your ChatGPT data controls."))
                            .font(blueprint.fonts.bodySmall)
                            .fixedSize(horizontal: false, vertical: true)
                        BlueprintDialogLink(loc("How to turn it off")) { openURL(TrainingOptOut.chatGptHelp) }
                        BlueprintCheckRow(loc("I turned off “Improve the model for everyone” in ChatGPT."), isOn: $trainingOff)
                            .accessibilityIdentifier("training-off")
                    }
                    if target.kind == "transcribe",
                       target.endpoint != SttProviders.shared.defaultEndpoint(name: target.provider) {
                        Text(verbatim: loc("For a custom endpoint, also check its operator’s privacy policy."))
                            .font(blueprint.fonts.bodySmall)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            // A required disclosure is never cut to "…": every line takes the height it needs.
            Text(verbatim: loc("Permission covers future recordings on this device. You can withdraw it in Settings → Privacy."))
                .font(blueprint.fonts.bodySmall)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(blueprint.palette.text)
        .accessibilityIdentifier("transfer-disclosure")
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

@MainActor
public final class TransferPrivacyModel: ObservableObject {
    @Published public private(set) var approved: [TransferTarget] = []
    @Published public private(set) var pending: [TransferTarget] = []
    @Published public private(set) var message: UiMessage?
    @Published public private(set) var working = false
    private let core: ReclyCore_
    private var generation = 0
    private var readTask: Task<Void, Never>?
    private var observers: [Task<Void, Never>] = []
    private var stopped = false

    public init(core: ReclyCore_) {
        self.core = core
        observers.append(Task { [weak self] in
            for await _ in core.transferConsents.observe() {
                guard let self else { return }
                await self.reload()
            }
        })
        observers.append(Task { [weak self] in
            for await _ in core.jobs.observe() {
                guard let self else { return }
                await self.reload()
            }
        })
    }

    deinit {
        observers.forEach { $0.cancel() }
        readTask?.cancel()
    }

    /// Drain subscriptions before the owner closes its store, including temporary test databases.
    func stopObserving() async {
        stopped = true
        let pending = observers
        observers = []
        pending.forEach { $0.cancel() }
        readTask?.cancel()
        for task in pending { await task.value }
        await readTask?.value
        readTask = nil
    }

    public func reload() async {
        guard !stopped, !Task.isCancelled else { return }
        generation += 1
        let reading = generation
        let previous = readTask
        // Serialize observations with explicit refreshes, so awaiting reload really publishes a
        // snapshot and an older read can never overwrite a completed withdrawal.
        let task = Task { [weak self] in
            await previous?.value
            guard let self, !self.stopped, !Task.isCancelled else { return }
            do {
                let approved = try await core.transferConsents.approved()
                let pending = try await core.pendingTransferTargets()
                self.approved = approved
                self.pending = pending
            } catch {
                message = .key("Could not read transfer permissions")
            }
        }
        readTask = task
        await task.value
        if reading == generation { readTask = nil }
    }

    /// Capture exactly what was displayed; a newly queued destination needs its own explicit tap.
    public func allowPending(_ shown: [TransferTarget]) async {
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            try await core.transferConsents.grant(targets: shown)
            try await core.resumeConsentedJobs()
            message = nil
            await reload()
        } catch { message = .key("Could not save transfer permissions") }
    }

    public func revoke(_ target: TransferTarget) async {
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            try await core.transferConsents.revoke(id: target.id)
            message = nil
            await reload()
        } catch { message = .key("Could not save transfer permissions") }
    }
}

public struct TransferPrivacyView: View {
    @ObservedObject private var model: TransferPrivacyModel
    @State private var trainingOff = false
    @Environment(\.locale) private var locale
    @Environment(\.blueprint) private var blueprint
    @Environment(\.openURL) private var openURL

    public init(model: TransferPrivacyModel) { self.model = model }

    public var body: some View {
        let pending = model.pending
        return ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                TextLink(loc("Privacy Policy")) { openURL(PrivacyLinks.recly(locale: locale)) }
                if let message = model.message {
                    Text(verbatim: message.text).foregroundStyle(blueprint.palette.danger)
                }
                if !pending.isEmpty {
                    SectionHeader(loc("Transfer permission needed"))
                    TransferDisclosureList(targets: pending, trainingOff: $trainingOff)
                    BlueprintButton(loc("Allow transfers and continue"), tone: .primary) {
                        trainingOff = false
                        Task { await model.allowPending(pending) }
                    }
                    .disabled(model.working || (TrainingOptOut.required(pending) && !trainingOff))
                    .accessibilityIdentifier("allow-pending-transfers")
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
                SectionHeader(loc("Allowed destinations"))
                if model.approved.isEmpty {
                    Text(verbatim: loc("No external destinations allowed yet."))
                }
                ForEach(model.approved, id: \.id) { target in
                    VStack(alignment: .leading, spacing: Space.xs) {
                        Text(verbatim: TransferTargetText.name(target))
                        Text(verbatim: target.endpoint)
                            .font(blueprint.fonts.monoSmall)
                            .fixedSize(horizontal: false, vertical: true)
                        if let url = PrivacyLinks.provider(target.provider) {
                            TextLink(loc("Provider privacy information")) { openURL(url) }
                        }
                        // docs/09 screen principle 8: the destination spans several lines, so its action sits under it, at the end.
                        BlueprintButton(loc("Withdraw permission"), tone: .quiet) {
                            Task { await model.revoke(target) }
                        }
                        .disabled(model.working)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                }
                Text(verbatim: loc("Withdrawing permission stops future requests. It does not delete data already sent or your saved API keys."))
                    .font(blueprint.fonts.bodySmall)
            }
            .padding(Space.m)
        }
        .navigationTitle(loc("Privacy"))
        .dotGridBackground()
        .task { await model.reload() }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
