#!/usr/bin/env swift
//
// render-icons.swift — regenerates every raster app-icon asset in the repository.
//
//     swift scripts/render-icons.swift
//
// **macOS only.** It draws with CoreGraphics, writes PNGs with ImageIO and shells out to
// `iconutil` (for the .icns) and to `scripts/make-ico.py` (for the .ico). There is no rsvg or
// ImageMagick on the development host, so nothing here parses docs/design/icon.svg: the master's
// three rectangles and its dot grid are re-drawn from the numbers below. The SVG is the design
// source of truth and this script is the export pipeline — a change to one is a change to both.
//
// The generated assets are committed, so a normal build never runs this. Re-running it must
// reproduce them byte for byte apart from PNG metadata.
//
// Android takes no raster at all: its adaptive icon is a set of vector drawables written by hand
// from the same numbers (android/app/src/main/res/drawable/ic_launcher_*.xml).

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// MARK: - Palette

/// A colour role is `nil` when the shape it paints is left out entirely: a transparent ground for
/// the macOS icon set, an unfilled node for the menu bar template.
struct Palette {
    let ground: CGColor?
    let surface: CGColor?
    let ink: CGColor
    let record: CGColor
    let grid: CGColor?
}

func rgb(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(
        red: CGFloat((hex >> 16) & 0xFF) / 255,
        green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255,
        alpha: alpha
    )
}

extension Palette {
    /// docs/09 "토큰", light.
    static let light = Palette(
        ground: rgb(0xF7F7F5), surface: rgb(0xFFFFFF), ink: rgb(0x111111),
        record: rgb(0xDA1E28), grid: rgb(0x888884, 0.28)
    )
    /// docs/09 "토큰", dark. The grid takes the dark secondary at the master's own 28%.
    static let dark = Palette(
        ground: rgb(0x0E0F12), surface: rgb(0x16181D), ink: rgb(0xF2F2F0),
        record: rgb(0xFA4D56), grid: rgb(0x9A9CA3, 0.28)
    )
    /// iOS 18's tinted appearance takes a greyscale image and maps its luminance through the
    /// user's tint, so the mark is drawn the way the menu bar template is — light lines on a dark
    /// ground — but at the master's proportions, so the tinted icon reads at the same size as the
    /// other two.
    static let tinted = Palette(
        ground: rgb(0x000000), surface: rgb(0x000000), ink: rgb(0xFFFFFF),
        record: rgb(0xFFFFFF), grid: rgb(0xFFFFFF, 0.18)
    )
    /// The menu bar / tray template: no ground, no fill, one colour. AppKit repaints a template
    /// image in the menu bar's own colour, so the black here is only a placeholder for that.
    static let template = Palette(
        ground: nil, surface: nil, ink: rgb(0x000000), record: rgb(0x000000), grid: nil
    )
    /// The recording template, which keeps its red and so cannot be a template image — one per
    /// appearance instead (docs/09 "앱 아이콘").
    static let recordingLight = Palette(
        ground: nil, surface: nil, ink: rgb(0x111111), record: rgb(0xDA1E28), grid: nil
    )
    static let recordingDark = Palette(
        ground: nil, surface: nil, ink: rgb(0xF2F2F0), record: rgb(0xFA4D56), grid: nil
    )
}

// MARK: - Geometry

/// Every measurement as a fraction of the artboard's side, so one routine draws the mark at any
/// pixel size.
struct Proportions {
    let node: CGFloat
    let nodeRadius: CGFloat
    let stroke: CGFloat
    let record: CGFloat
    let recordRadius: CGFloat
    /// `0` leaves the dot grid out.
    let gridPitch: CGFloat
    let gridDot: CGFloat

    /// docs/design/icon.svg on its 1024 artboard: the node is 560 wide at (232,232), radius 40,
    /// stroke 28; the record mark is 240 wide, radius 24; the grid is 64 pitch with r=5 dots.
    static let master = Proportions(
        node: 560 / 1024, nodeRadius: 40 / 1024, stroke: 28 / 1024,
        record: 240 / 1024, recordRadius: 24 / 1024,
        gridPitch: 64 / 1024, gridDot: 5 / 1024
    )

    /// Under ~64 px the master's stroke is thinner than a pixel and its grid is noise. The
    /// monochrome template's 22 grid — outer 16 at (3,3) radius 2 stroke 1.5, inner 6 radius 1 —
    /// carries the same mark with lines that survive, and is what the small rasters are drawn with.
    static let template = Proportions(
        node: 16 / 22, nodeRadius: 2 / 22, stroke: 1.5 / 22,
        record: 6 / 22, recordRadius: 1 / 22,
        gridPitch: 0, gridDot: 0
    )

    /// The size below which the master stops reading.
    static let compactBelow = 64
}

/// The macOS icon grid: an 824-wide body on a 1024 canvas (a ~10% margin all round) with the
/// platform's corner radius. Approximated with a circular-corner rounded rectangle rather than the
/// true superellipse — the difference is a fraction of a pixel at the sizes this ships at.
enum MacGrid {
    static let inset: CGFloat = 100 / 1024
    static let cornerRadius: CGFloat = 185.4 / 824
}

/// Draws the mark on `context`, which is assumed to be `side` x `side` points.
///
/// - `inset` is the margin left on each edge, as a fraction of `side`; the artboard is what is
///   left inside it, and every proportion is taken against that.
/// - `cornerRadius` rounds the ground, as a fraction of the artboard's side.
///
/// CoreGraphics' origin is bottom-left and the SVG's is top-left, but every shape here is centred
/// and symmetric about both axes, so no flip is needed.
func drawIcon(
    _ context: CGContext,
    side: CGFloat,
    palette: Palette,
    proportions: Proportions,
    inset: CGFloat = 0,
    cornerRadius: CGFloat = 0
) {
    let board = side * (1 - 2 * inset)
    let origin = side * inset
    let boardRect = CGRect(x: origin, y: origin, width: board, height: board)

    func centred(_ fraction: CGFloat) -> CGRect {
        let length = board * fraction
        return CGRect(
            x: origin + (board - length) / 2,
            y: origin + (board - length) / 2,
            width: length,
            height: length
        )
    }

    let groundPath = CGPath(
        roundedRect: boardRect,
        cornerWidth: board * cornerRadius,
        cornerHeight: board * cornerRadius,
        transform: nil
    )

    if let ground = palette.ground {
        context.setFillColor(ground)
        context.addPath(groundPath)
        context.fillPath()
    }

    if let grid = palette.grid, proportions.gridPitch > 0 {
        context.saveGState()
        context.addPath(groundPath)
        context.clip()
        context.setFillColor(grid)
        let pitch = board * proportions.gridPitch
        let radius = board * proportions.gridDot
        var y = origin + pitch / 2
        while y < origin + board {
            var x = origin + pitch / 2
            while x < origin + board {
                context.fillEllipse(in: CGRect(
                    x: x - radius, y: y - radius, width: radius * 2, height: radius * 2
                ))
                x += pitch
            }
            y += pitch
        }
        context.restoreGState()
    }

    let node = CGPath(
        roundedRect: centred(proportions.node),
        cornerWidth: board * proportions.nodeRadius,
        cornerHeight: board * proportions.nodeRadius,
        transform: nil
    )
    if let surface = palette.surface {
        context.setFillColor(surface)
        context.addPath(node)
        context.fillPath()
    }
    context.setStrokeColor(palette.ink)
    // SVG centres a stroke on its path, as CoreGraphics does.
    context.setLineWidth(board * proportions.stroke)
    context.addPath(node)
    context.strokePath()

    context.setFillColor(palette.record)
    context.addPath(CGPath(
        roundedRect: centred(proportions.record),
        cornerWidth: board * proportions.recordRadius,
        cornerHeight: board * proportions.recordRadius,
        transform: nil
    ))
    context.fillPath()
}

// MARK: - Output

let repoRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()

func path(_ relative: String) -> URL {
    repoRoot.appendingPathComponent(relative)
}

/// `log` is off for the members of the .icns and the .ico, which go to a scratch directory.
func writePNG(_ url: URL, pixels: Int, log: Bool = true, _ draw: (CGContext, CGFloat) -> Void) {
    guard let context = CGContext(
        data: nil,
        width: pixels,
        height: pixels,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else {
        fatalError("could not open a \(pixels)px context")
    }
    context.setAllowsAntialiasing(true)
    draw(context, CGFloat(pixels))

    guard let image = context.makeImage() else { fatalError("could not render \(url.path)") }
    try! FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(), withIntermediateDirectories: true
    )
    guard let destination = CGImageDestinationCreateWithURL(
        url as CFURL, UTType.png.identifier as CFString, 1, nil
    ) else {
        fatalError("could not write \(url.path)")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("could not write \(url.path)") }
    if log { print("  \(url.path.replacingOccurrences(of: repoRoot.path + "/", with: ""))") }
}

/// The app icon proper: full bleed unless a platform's grid asks for a margin.
func writeAppIcon(
    _ relative: String,
    pixels: Int,
    palette: Palette = .light,
    inset: CGFloat = 0,
    cornerRadius: CGFloat = 0
) {
    let proportions: Proportions = pixels < Proportions.compactBelow ? .template : .master
    writePNG(path(relative), pixels: pixels) { context, side in
        drawIcon(
            context,
            side: side,
            palette: palette,
            proportions: proportions,
            inset: inset,
            cornerRadius: cornerRadius
        )
    }
}

/// The 22-point monochrome glyph the menu bar and the tray show.
func writeGlyph(_ relative: String, pixels: Int, palette: Palette) {
    writePNG(path(relative), pixels: pixels) { context, side in
        drawIcon(context, side: side, palette: palette, proportions: .template)
    }
}

func run(_ tool: String, _ arguments: [String]) {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: tool)
    process.arguments = arguments
    process.currentDirectoryURL = repoRoot
    try! process.run()
    process.waitUntilExit()
    guard process.terminationStatus == 0 else {
        fatalError("\(tool) \(arguments.joined(separator: " ")) exited \(process.terminationStatus)")
    }
}

// MARK: - The assets

print("iOS — RecPhone")
let phoneIcons = "apple/RecPhone/RecPhone/Assets.xcassets/AppIcon.appiconset"
writeAppIcon("\(phoneIcons)/AppIcon-1024.png", pixels: 1024)
writeAppIcon("\(phoneIcons)/AppIcon-1024-dark.png", pixels: 1024, palette: .dark)
writeAppIcon("\(phoneIcons)/AppIcon-1024-tinted.png", pixels: 1024, palette: .tinted)

print("watchOS — RecWatch")
writeAppIcon("apple/RecWatch/RecWatch/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png", pixels: 1024)

print("macOS — RecMac")
let macIcons = "apple/RecMac/RecMac/Assets.xcassets/AppIcon.appiconset"
/// (name, pixels) for the macOS ladder: 16/32/128/256/512 at 1x and 2x.
let macSizes: [(String, Int)] = [
    ("icon_16x16", 16), ("icon_16x16@2x", 32),
    ("icon_32x32", 32), ("icon_32x32@2x", 64),
    ("icon_128x128", 128), ("icon_128x128@2x", 256),
    ("icon_256x256", 256), ("icon_256x256@2x", 512),
    ("icon_512x512", 512), ("icon_512x512@2x", 1024),
]
for (name, pixels) in macSizes {
    writeAppIcon(
        "\(macIcons)/\(name).png",
        pixels: pixels,
        inset: MacGrid.inset,
        cornerRadius: MacGrid.cornerRadius
    )
}

print("macOS — menu bar")
let macAssets = "apple/RecMac/RecMac/Assets.xcassets"
writeGlyph("\(macAssets)/MenuBarIcon.imageset/MenuBarIcon.png", pixels: 22, palette: .template)
writeGlyph("\(macAssets)/MenuBarIcon.imageset/MenuBarIcon@2x.png", pixels: 44, palette: .template)
let recordingSet = "\(macAssets)/MenuBarIconRecording.imageset"
writeGlyph("\(recordingSet)/MenuBarIconRecording.png", pixels: 22, palette: .recordingLight)
writeGlyph("\(recordingSet)/MenuBarIconRecording@2x.png", pixels: 44, palette: .recordingLight)
writeGlyph("\(recordingSet)/MenuBarIconRecording-dark.png", pixels: 22, palette: .recordingDark)
writeGlyph("\(recordingSet)/MenuBarIconRecording-dark@2x.png", pixels: 44, palette: .recordingDark)

print("Windows / jpackage")
let jpackage = "windows/app/src/main/icons"
// The Linux icon jpackage wants, and the one the other two are built from.
writeAppIcon("\(jpackage)/recly.png", pixels: 512)

// The .icns and the .ico are containers, so their members go to a scratch directory and only the
// packed file is committed.
let scratch = URL(fileURLWithPath: NSTemporaryDirectory())
    .appendingPathComponent("recly-icons-\(ProcessInfo.processInfo.processIdentifier)")
defer { try? FileManager.default.removeItem(at: scratch) }

let iconset = scratch.appendingPathComponent("recly.iconset")
for (name, pixels) in macSizes {
    let proportions: Proportions = pixels < Proportions.compactBelow ? .template : .master
    writePNG(iconset.appendingPathComponent("\(name).png"), pixels: pixels, log: false) { context, side in
        drawIcon(
            context,
            side: side,
            palette: .light,
            proportions: proportions,
            inset: MacGrid.inset,
            cornerRadius: MacGrid.cornerRadius
        )
    }
}
run("/usr/bin/iconutil", [
    "--convert", "icns",
    "--output", path("\(jpackage)/recly.icns").path,
    iconset.path,
])
print("  \(jpackage)/recly.icns")

// Windows ships square, full-bleed icons; the ladder is what Explorer, the taskbar and the MSI
// each pick from.
let icoSizes = [16, 24, 32, 48, 64, 128, 256]
var icoMembers: [String] = []
for pixels in icoSizes {
    let member = scratch.appendingPathComponent("recly-\(pixels).png")
    let proportions: Proportions = pixels < Proportions.compactBelow ? .template : .master
    writePNG(member, pixels: pixels, log: false) { context, side in
        drawIcon(context, side: side, palette: .light, proportions: proportions)
    }
    icoMembers.append(member.path)
}
run("/usr/bin/env", ["python3", path("scripts/make-ico.py").path,
                     "--out", path("\(jpackage)/recly.ico").path] + icoMembers)
print("  \(jpackage)/recly.ico")
