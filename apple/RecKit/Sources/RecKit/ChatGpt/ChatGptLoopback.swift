#if os(iOS) || os(macOS)
import Foundation
import Network

/// docs/15 §10 "Sign in with ChatGPT": the redirect OpenAI sends the browser back to. OpenAI accepts only
/// `http://127.0.0.1:<port>/auth/callback`, so this listens on the IPv4 loopback — never `localhost`,
/// never another interface (RFC 8252 §8.3) — at a port the system picks, for as long as one sign-in takes.
/// A stopped `NWListener` cannot start again, so every sign-in makes a new one of these.
///
/// The callback is the one GET to [path] whose `state` is the sign-in's own. It is answered at once with
/// [matched]'s answer — the browser is not kept waiting on the token exchange — and handed whole to the
/// core. Anything else that reaches the port, a favicon or a guess, is a 404 and the wait goes on. A second
/// callback after the first is answered with [repeated] and handed to nobody.
public final class ChatGptLoopback: @unchecked Sendable {
    public enum Answer: Sendable, Equatable {
        /// 200, one inline HTML document.
        case page(String)
        /// 302 to [location] — the iPhone's way of closing the sign-in sheet.
        case redirect(String)
    }

    public static let path = "/auth/callback"
    public static let host = "127.0.0.1"

    public static func redirectUri(port: UInt16) -> String { "http://\(host):\(port)\(path)" }

    private let matched: @Sendable (URLComponents) -> Answer
    private let repeated: @Sendable () -> Answer
    /// Every field below is read and written on this queue only.
    private let queue = DispatchQueue(label: "app.recly.chatgpt.loopback")
    private var listener: NWListener?
    private var port: UInt16 = 0
    private var expected: String?
    private var callbackURL: String?
    private var stopped = false
    private var waiter: CheckedContinuation<String?, Never>?
    private var starting: CheckedContinuation<UInt16, Error>?

    public init(
        matched: @escaping @Sendable (URLComponents) -> Answer,
        repeated: @escaping @Sendable () -> Answer = {
            .page(ChatGptLoopback.page(RecKitStrings.localized("This sign-in has already been handled. You can close this window.")))
        }
    ) {
        self.matched = matched
        self.repeated = repeated
    }

    /// Starts listening and answers the port. Throws when the system will not open one.
    public func start() async throws -> UInt16 {
        let parameters = NWParameters.tcp
        // The loopback address alone, not every interface: a listener without a local endpoint takes them all.
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        parameters.acceptLocalOnly = true
        let listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return connection.cancel() }
            connection.start(queue: self.queue)
            self.read(connection, buffer: Data())
        }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.port = listener?.port?.rawValue ?? 0
                    self.started(.success(self.port))
                case .failed(let error):
                    listener?.cancel()
                    self.started(.failure(error))
                case .cancelled:
                    self.started(.failure(CancellationError()))
                default:
                    break
                }
            }
            queue.async { [weak self] in
                // Stopped before it started: a listener never started reports nothing, so this answers.
                guard let self, !self.stopped else { return continuation.resume(throwing: CancellationError()) }
                self.starting = continuation
                self.listener = listener
                listener.start(queue: self.queue)
            }
        }
    }

    /// On [queue]: the first of ready, failed or cancelled is the answer to [start].
    private func started(_ result: Result<UInt16, Error>) {
        starting?.resume(with: result)
        starting = nil
    }

    /// The `state` of the sign-in that is waiting. Until it is set, every request is a 404.
    public func expect(state: String) {
        queue.async { self.expected = state }
    }

    /// The whole URL of the callback, or nil when the receiver was stopped before one came.
    public func callback() async -> String? {
        await withCheckedContinuation { continuation in
            queue.async {
                if let url = self.callbackURL {
                    continuation.resume(returning: url)
                } else if self.stopped {
                    continuation.resume(returning: nil)
                } else {
                    self.waiter?.resume(returning: nil)
                    self.waiter = continuation
                }
            }
        }
    }

    /// Closes the port. A wait still open ends with no callback.
    public func stop() {
        queue.async {
            self.stopped = true
            self.listener?.cancel()
            self.listener = nil
            self.waiter?.resume(returning: self.callbackURL)
            self.waiter = nil
        }
    }

    // MARK: - HTTP

    /// The request head and nothing after it; a GET has no body, and the browser's head is far smaller.
    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, complete, error in
            guard let self else { return connection.cancel() }
            var buffer = buffer
            if let data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                self.answer(connection, head: buffer[..<end.lowerBound])
            } else if error != nil || complete || buffer.count > Self.maxHead {
                connection.cancel()
            } else {
                self.read(connection, buffer: buffer)
            }
        }
    }

    private func answer(_ connection: NWConnection, head: Data) {
        let requestLine = String(decoding: head, as: UTF8.self).components(separatedBy: "\r\n").first ?? ""
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3, parts[0] == "GET", parts[1].hasPrefix("/") else {
            return send(connection, status: "404 Not Found", body: nil)
        }
        let url = "http://\(Self.host):\(port)\(parts[1])"
        guard let components = URLComponents(string: url), components.path == Self.path,
              let expected, components.queryItems?.first(where: { $0.name == "state" })?.value == expected
        else {
            return send(connection, status: "404 Not Found", body: nil)
        }
        if callbackURL != nil {
            return send(connection, answer: repeated())
        }
        callbackURL = url
        send(connection, answer: matched(components))
        waiter?.resume(returning: url)
        waiter = nil
    }

    private func send(_ connection: NWConnection, answer: Answer) {
        switch answer {
        case .page(let html):
            send(connection, status: "200 OK", body: Data(html.utf8))
        case .redirect(let location):
            send(connection, status: "302 Found", body: nil, headers: ["Location: \(location)"])
        }
    }

    private func send(_ connection: NWConnection, status: String, body: Data?, headers: [String] = []) {
        var head = ["HTTP/1.1 \(status)"] + headers
        if body != nil { head.append("Content-Type: text/html; charset=utf-8") }
        // The page's URL carries the authorization code: nothing may cache it or pass it on.
        head += ["Content-Length: \(body?.count ?? 0)", "Cache-Control: no-store", "Referrer-Policy: no-referrer", "Connection: close"]
        let bytes = Data((head.joined(separator: "\r\n") + "\r\n\r\n").utf8) + (body ?? Data())
        connection.send(content: bytes, completion: .contentProcessed { _ in connection.cancel() })
    }

    private static let maxHead = 64 * 1024

    /// The browser tab the user is left looking at: one inline document, no stylesheet, image or script —
    /// anything it fetched would carry this URL, and its code, out in a `Referer` (the Windows shell's page).
    /// The answers build it when the request comes, so it is in the app's language at that moment.
    public static func page(_ sentence: String) -> String {
        let text = sentence
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
        return "<!doctype html><meta charset=\"utf-8\"><title>Recly</title>"
            + "<body style=\"font-family:-apple-system,sans-serif;padding:3rem\"><p>\(text)</p></body>"
    }
}
#endif
