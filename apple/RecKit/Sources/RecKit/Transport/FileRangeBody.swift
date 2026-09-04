import CryptoKit
import Foundation
import ReclyCore

/// An `HttpBody.FileRange` that is a request body in its own right rather than a Drive chunk
/// (docs/08 `assemblyai`: `POST /v2/upload` sends the whole recording as raw bytes and answers with
/// the `upload_url`). The phone puts it through the background `URLSession` for the same reason as
/// the multipart submissions — a 40 MB upload does not survive iOS suspending the job that started
/// it, and starting over from zero every wake never finishes.
///
/// Only the phone's [BackgroundTransport] needs this; every other shell hands the plan to
/// `KtorTransport`, which streams the range itself. It lives outside that file's `#if os(iOS)` so
/// the key and the request can be tested on the Mac.
enum FileRangeBody {
    /// Stable across launches and unique per submission, because both are what a relaunch needs of
    /// it: the same upload asked for twice must find the task it left running (or the answer it
    /// left behind), and two recordings posted to the same provider URL must not be handed each
    /// other's answer.
    ///
    /// Deliberately not the chunk key ([BackgroundTransport.key(for:)]), which names a slice by its
    /// `Content-Range`: these requests have no `Content-Range`, so what tells them apart is the
    /// file and the span of it being sent.
    static func key(for plan: HttpPlan, range: HttpBody.FileRange) -> String {
        let text = "\(plan.method)|\(plan.url)|\(range.path.description())|\(range.offset)|\(range.length)"
        return SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The upload request for [plan]. `Content-Type` comes from the body — Ktor writes that one
    /// itself, so the plan does not carry it — and `timeoutInterval` from the plan, which is what
    /// keeps Foundation's sixty seconds from killing a fifteen-minute upload of a whole recording
    /// (docs/08; the same reason as [MultipartBody.request]).
    static func request(for plan: HttpPlan, range: HttpBody.FileRange) -> URLRequest {
        var request = URLRequest(url: URL(string: plan.url)!)
        request.httpMethod = plan.method
        for (name, value) in plan.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.setValue(range.contentType, forHTTPHeaderField: "Content-Type")
        if let timeout = plan.timeoutSec {
            request.timeoutInterval = TimeInterval(truncating: timeout)
        }
        return request
    }
}
