import ReclyCore
import StoreKit

/// docs/15 "중국 본토 App Store": StoreKit's current account storefront, without a saved fallback.
public final class AppleStorefrontRegion: AppStoreRegion {
    public init() {}

    public func __countryCode() async throws -> String? {
        await Storefront.current?.countryCode
    }
}
