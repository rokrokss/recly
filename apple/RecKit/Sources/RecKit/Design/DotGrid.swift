import SwiftUI

/// docs/09 "간격": an 8pt dot grid at 6% behind the content — the visible grid the nodes sit on.
/// Off in high contrast, where a texture is noise.
public struct DotGrid: View {
    @Environment(\.displayScale) private var displayScale
    private let palette: BlueprintPalette

    public init(palette: BlueprintPalette) {
        self.palette = palette
    }

    private static let step: CGFloat = 8
    private static let dot: CGFloat = 1.6

    public var body: some View {
        ZStack {
            palette.background
            if !palette.highContrast,
                let tile = Self.tile(ink: palette.hex(.text), scale: displayScale) {
                // One 8pt tile, drawn once and repeated. A phone screen is several thousand dots
                // and not one of them ever changes, so filling a path per dot per frame would be
                // paying for the grid over and over. Android tiles the same grid the same way.
                Image(decorative: tile, scale: displayScale)
                    .resizable(resizingMode: .tile)
            }
        }
    }

    private struct Key: Hashable {
        let ink: UInt32
        let scale: CGFloat
    }

    /// The tile, cached: an app asks for one ink per appearance and one scale per screen, so this
    /// holds two or three images for the life of the process.
    ///
    /// `@MainActor` because that is where a `View`'s body runs — the cache needs no lock of its own.
    @MainActor private static var tiles: [Key: CGImage] = [:]

    @MainActor
    private static func tile(ink: UInt32, scale: CGFloat) -> CGImage? {
        let key = Key(ink: ink, scale: scale)
        if let cached = tiles[key] { return cached }
        let side = max(Int((step * scale).rounded()), 1)
        guard
            let context = CGContext(
                data: nil,
                width: side,
                height: side,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
        else { return nil }
        context.setFillColor(
            red: CGFloat((ink >> 16) & 0xFF) / 255,
            green: CGFloat((ink >> 8) & 0xFF) / 255,
            blue: CGFloat(ink & 0xFF) / 255,
            alpha: 0.06
        )
        let diameter = dot * scale
        context.fillEllipse(
            in: CGRect(
                x: (CGFloat(side) - diameter) / 2,
                y: (CGFloat(side) - diameter) / 2,
                width: diameter,
                height: diameter
            )
        )
        guard let image = context.makeImage() else { return nil }
        tiles[key] = image
        return image
    }
}

extension View {
    /// The page under a Blueprint screen: the paper colour, and the grid on it.
    public func dotGridBackground() -> some View {
        modifier(DotGridBackground())
    }
}

private struct DotGridBackground: ViewModifier {
    @Environment(\.blueprint) private var blueprint

    func body(content: Content) -> some View {
        content.background(DotGrid(palette: blueprint.palette).ignoresSafeArea())
    }
}
