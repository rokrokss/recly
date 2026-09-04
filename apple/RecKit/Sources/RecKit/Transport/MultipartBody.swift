import Foundation
import ReclyCore

/// `HttpBody.Multipart` written out as a file (docs/08: `clova` and `rtzr` take the audio as a form
/// upload). A file rather than `Data` because that is the only shape a background `URLSession`
/// accepts — and because the audio part is a whole recording, which is not something a phone
/// should be holding in memory.
///
/// Only the phone's [BackgroundTransport] needs this; every other shell hands multipart plans to
/// `KtorTransport`, which streams them itself. It lives outside that file's `#if os(iOS)` so the
/// encoding can be tested on the Mac.
enum MultipartBody {
    /// Writes [body] to [file] and answers the boundary the `Content-Type` has to name.
    static func write(_ body: HttpBody.Multipart, to file: URL) throws -> String {
        let boundary = "recly" + String(UInt64.random(in: UInt64.min ... UInt64.max), radix: 16)
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let sink = try FileHandle(forWritingTo: file)
        defer { try? sink.close() }

        for part in body.parts {
            try sink.write(contentsOf: Data(header(for: part, boundary: boundary).utf8))
            switch onEnum(of: part.source) {
            case .bytes(let source):
                try sink.write(contentsOf: source.bytes.data)
            case .file(let source):
                try copy(URL(fileURLWithPath: source.path.description(), isDirectory: false), into: sink)
            }
            try sink.write(contentsOf: Data(crlf.utf8))
        }
        try sink.write(contentsOf: Data("--\(boundary)--\(crlf)".utf8))
        return boundary
    }

    /// What tells one submission from another where there is no `Content-Range` to name a slice:
    /// two recordings posted to the same provider URL are two different uploads, and a background
    /// session that keyed them the same would hand one of them the other's answer.
    static func identity(_ body: HttpBody.Multipart) -> String {
        body.parts.map { part in
            switch onEnum(of: part.source) {
            case .file(let source): return "\(part.name)=file:\(source.path.description())"
            case .bytes(let source): return "\(part.name)=bytes:\(source.bytes.size)"
            }
        }.joined(separator: "|")
    }

    /// The value the request's `Content-Type` needs — the media type alone says nothing.
    static func contentType(boundary: String) -> String {
        "\(HttpBody.Multipart.companion.FORM_DATA); boundary=\(boundary)"
    }

    /// The upload request for [plan], with the body already written and [boundary] known.
    ///
    /// `timeoutInterval` is the plan's, and it matters: it is how long the connection may sit idle,
    /// and a `clova` submission is a *synchronous* call that answers with the whole transcript —
    /// the server says nothing for up to fifteen minutes (docs/08). Foundation's default is sixty
    /// seconds, which would kill every such request. The session's `timeoutIntervalForResource` is
    /// not what bites here: a background configuration leaves that at seven days.
    ///
    /// Built outside [BackgroundTransport] so it can be tested on the Mac — the transport itself
    /// is the phone's alone.
    static func request(for plan: HttpPlan, boundary: String) -> URLRequest {
        var request = URLRequest(url: URL(string: plan.url)!)
        request.httpMethod = plan.method
        for (name, value) in plan.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        // Last, and by hand: the plan cannot carry a boundary that did not exist when it was made.
        request.setValue(contentType(boundary: boundary), forHTTPHeaderField: "Content-Type")
        if let timeout = plan.timeoutSec {
            request.timeoutInterval = TimeInterval(truncating: timeout)
        }
        return request
    }

    private static func header(for part: HttpBody.MultipartPart, boundary: String) -> String {
        var header = "--\(boundary)\(crlf)Content-Disposition: form-data; name=\"\(part.name)\""
        if let filename = part.filename {
            header += "; filename=\"\(filename)\""
        }
        header += "\(crlf)Content-Type: \(part.contentType)\(crlf)\(crlf)"
        return header
    }

    /// Block by block, so the recording is never read into memory in one piece.
    private static func copy(_ source: URL, into sink: FileHandle) throws {
        let handle = try FileHandle(forReadingFrom: source)
        defer { try? handle.close() }
        while let block = try handle.read(upToCount: copyBuffer), !block.isEmpty {
            try sink.write(contentsOf: block)
        }
    }

    private static let crlf = "\r\n"
    private static let copyBuffer = 64 * 1024
}
