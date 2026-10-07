import UIKit
import UniformTypeIdentifiers

/// docs/09 "Import": `Import to Recly` in the share sheet of other apps — the audio or video files shared are
/// copied into the app group's inbox, and one line says the app takes them in. Nothing else happens here:
/// the extension links no RecKit and no core, and the app imports whatever the inbox holds whenever it
/// comes to the front.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let line = UILabel()
        line.text = String(localized: "Open Recly to import.")
        line.font = .preferredFont(forTextStyle: .body)
        line.textColor = .secondaryLabel
        line.textAlignment = .center
        line.numberOfLines = 0
        line.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(line)
        NSLayoutConstraint.activate([
            line.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            line.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            line.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await handOver() }
    }

    private func handOver() async {
        let shown = ContinuousClock.now
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        if let inbox = Self.inbox {
            try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
            for provider in providers {
                guard let type = [UTType.audio, .movie].first(where: { provider.hasItemConformingToTypeIdentifier($0.identifier) })
                else { continue }
                await copy(provider, type: type, into: inbox)
            }
        }
        // Long enough to read the line.
        try? await Task.sleep(until: shown + .seconds(1.5))
        extensionContext?.completeRequest(returningItems: nil)
    }

    /// The file as the sharing app offers it, under its own name — the app titles the recording with it — in a
    /// folder of its own, so two files of the same name do not meet. Written under a hidden name and then
    /// put in place, so the app never takes in half a file.
    private func copy(_ provider: NSItemProvider, type: UTType, into inbox: URL) async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                defer { done.resume() }
                guard let url else { return }
                let name = provider.suggestedName.map { suggested in
                    (suggested as NSString).pathExtension.caseInsensitiveCompare(url.pathExtension) == .orderedSame
                        ? suggested : suggested + "." + url.pathExtension
                } ?? url.lastPathComponent
                let id = UUID().uuidString
                let staging = inbox.appendingPathComponent("." + id, isDirectory: true)
                do {
                    try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: url, to: staging.appendingPathComponent(name))
                    try FileManager.default.moveItem(at: staging, to: inbox.appendingPathComponent(id, isDirectory: true))
                } catch {
                    try? FileManager.default.removeItem(at: staging)
                }
            }
        }
    }

    /// The app group's inbox — `ImportInbox.directory` in the app.
    private static var inbox: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.recly")?
            .appendingPathComponent("Inbox", isDirectory: true)
    }
}
