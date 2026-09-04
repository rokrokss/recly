import Foundation
import ReclyCore

/// docs/01: the shell owns the wall clock. `Clock` is qualified because Swift has one too.
public final class SystemClock: ReclyCore.Clock {
    public init() {}

    public func now() -> KotlinInstant {
        KotlinInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64((Date().timeIntervalSince1970 * 1000).rounded())
        )
    }
}
