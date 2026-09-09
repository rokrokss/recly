#if os(iOS) || os(macOS)
import Foundation
import ReclyCore
import SwiftUI

/// docs/15: user-opened policy pages. These are never fetched as part of a recording or a job.
public enum PrivacyLinks {
    public static func recly(locale: Locale) -> URL {
        let file = locale.identifier.hasPrefix("ko") ? "privacy-policy.ko.md" : "privacy-policy.md"
        return URL(string: "https://github.com/rokrokss/recly/blob/main/docs/policy/" + file)!
    }

    public static func provider(_ name: String) -> URL? {
        policies[name].flatMap(URL.init(string:))
    }

    // Public policy entry points, checked 2026-09-09. The account's service agreement may also apply.
    private static let policies: [String: String] = [
        "assemblyai": "https://www.assemblyai.com/legal/privacy-policy",
        "openai": "https://openai.com/policies/privacy-policy/",
        "groq": "https://groq.com/privacy-policy",
        "together": "https://www.together.ai/privacy",
        "mistral": "https://legal.mistral.ai/terms/privacy-policy",
        "deepgram": "https://deepgram.com/privacy",
        "elevenlabs": "https://elevenlabs.io/privacy-policy",
        "azure": "https://www.microsoft.com/en-us/privacy/privacystatement",
        "rev": "https://www.rev.com/legal/privacy",
        "speechmatics": "https://www.speechmatics.com/legal/privacy-policy",
        "daglo": "https://daglo.ai/d/en/legal/privacy",
        "rtzr": "https://developers.rtzr.ai/privacy",
        "clova": "https://www.ncloud.com/policy/infou",
        "gladia": "https://www.gladia.io/privacy-notice",
    ]
}

/// The same disclosure precedes an editor save, an import and a parked job's approval.
public struct TransferDisclosureList: View {
    private let targets: [TransferTarget]
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(targets: [TransferTarget]) { self.targets = targets }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            ForEach(targets, id: \.id) { target in
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(verbatim: target.kind == "webhook" ? loc("Webhook destination") : target.provider)
                        .font(blueprint.fonts.label)
                    Text(verbatim: target.endpoint)
                        .font(blueprint.fonts.monoSmall)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: loc(target.kind == "webhook"
                        ? "Recording metadata and Drive file links are sent here for your automation. Audio and transcript text are not included."
                        : "The full recording and language and speaker settings are sent here for transcription. Retention and training depend on the provider and your account settings."))
                        .font(blueprint.fonts.bodySmall)
                    if let url = PrivacyLinks.provider(target.provider) {
                        Link(loc("Provider privacy information"), destination: url)
                            .font(blueprint.fonts.bodySmall)
                    }
                    if target.kind == "transcribe",
                       target.endpoint != SttProviders.shared.defaultEndpoint(name: target.provider) {
                        Text(verbatim: loc("For a custom endpoint, also check its operator’s privacy policy."))
                            .font(blueprint.fonts.bodySmall)
                    }
                }
            }
            Text(verbatim: loc("Permission covers future recordings on this device. You can withdraw it in Settings → Privacy."))
                .font(blueprint.fonts.bodySmall)
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

    public init(core: ReclyCore_) {
        self.core = core
        Task { [weak self] in
            for await _ in core.transferConsents.observe() {
                guard let self else { return }
                await self.reload()
            }
        }
        Task { [weak self] in
            for await _ in core.jobs.observe() {
                guard let self else { return }
                await self.reload()
            }
        }
    }

    public func reload() async {
        generation += 1
        let reading = generation
        let previous = readTask
        // Serialize observations with explicit refreshes, so awaiting reload really publishes a
        // snapshot and an older read can never overwrite a completed withdrawal.
        let task = Task { [weak self] in
            await previous?.value
            guard let self else { return }
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
    @Environment(\.locale) private var locale
    @Environment(\.blueprint) private var blueprint

    public init(model: TransferPrivacyModel) { self.model = model }

    public var body: some View {
        let pending = model.pending
        return ScrollView {
            VStack(alignment: .leading, spacing: Space.m) {
                Link(loc("Privacy Policy"), destination: PrivacyLinks.recly(locale: locale))
                if let message = model.message {
                    Text(verbatim: message.text).foregroundStyle(blueprint.palette.danger)
                }
                if !pending.isEmpty {
                    SectionHeader(loc("Transfer permission needed"))
                    TransferDisclosureList(targets: pending)
                    BlueprintButton(loc("Allow transfers and continue"), tone: .primary) {
                        Task { await model.allowPending(pending) }
                    }
                    .disabled(model.working)
                    .accessibilityIdentifier("allow-pending-transfers")
                }
                SectionHeader(loc("Allowed destinations"))
                if model.approved.isEmpty {
                    Text(verbatim: loc("No external destinations allowed yet."))
                }
                ForEach(model.approved, id: \.id) { target in
                    VStack(alignment: .leading, spacing: Space.xs) {
                        Text(verbatim: target.kind == "webhook" ? loc("Webhook destination") : target.provider)
                        Text(verbatim: target.endpoint)
                            .font(blueprint.fonts.monoSmall)
                            .fixedSize(horizontal: false, vertical: true)
                        if let url = PrivacyLinks.provider(target.provider) {
                            Link(loc("Provider privacy information"), destination: url)
                        }
                        BlueprintButton(loc("Withdraw permission"), tone: .quiet) {
                            Task { await model.revoke(target) }
                        }
                        .disabled(model.working)
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
