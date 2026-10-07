import UIKit
import UniformTypeIdentifiers

/// docs/09 "Import": `Import to Recly` in the share sheet of other apps — the audio or video files shared are
/// copied into the app group's inbox and the app is opened to import them. Nothing else happens here:
/// the extension links no RecKit and no core, and the app imports whatever the inbox holds whenever it
/// comes to the front, so a file is not lost if the open below does not happen.
final class ShareViewController: UIViewController {
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await handOver() }
    }

    private func handOver() async {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        if let inbox = Self.inbox {
            try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
            for provider in providers {
                guard let type = [UTType.audio, .movie].first(where: { provider.hasItemConformingToTypeIdentifier($0.identifier) })
                else { continue }
                await copy(provider, type: type, into: inbox)
            }
        }
        openApp()
        extensionContext?.completeRequest(returningItems: nil)
    }

    /// The file as the sharing app offers it, under its own name in a folder of its own, so two files
    /// of the same name do not meet.
    private func copy(_ provider: NSItemProvider, type: UTType, into inbox: URL) async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                defer { done.resume() }
                guard let url else { return }
                let name = provider.suggestedName.map { $0 + "." + url.pathExtension } ?? url.lastPathComponent
                let target = inbox.appendingPathComponent("\(Date().timeIntervalSince1970)-\(UUID().uuidString.prefix(8))-\(name)")
                try? FileManager.default.copyItem(at: url, to: target)
            }
        }
    }

    /// The app, at `recly://import`. A share extension has no `open` of its own, so the call goes up
    /// the responder chain to the application object.
    private func openApp() {
        guard let url = URL(string: "recly://import") else { return }
        var responder: UIResponder? = self
        while let next = responder {
            if next.responds(to: #selector(OpensURLs.open(_:options:completionHandler:))) {
                unsafeBitCast(next, to: OpensURLs.self).open(url, options: [:], completionHandler: nil)
                return
            }
            responder = next.next
        }
    }

    /// The app group's inbox — `ImportInbox.directory` in the app.
    private static var inbox: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.recly")?
            .appendingPathComponent("Inbox", isDirectory: true)
    }
}

/// `UIApplication.open(_:options:completionHandler:)`, which an extension may not name directly.
@objc private protocol OpensURLs {
    @objc(openURL:options:completionHandler:)
    func open(_ url: URL, options: [String: Any], completionHandler: ((Bool) -> Void)?)
}
