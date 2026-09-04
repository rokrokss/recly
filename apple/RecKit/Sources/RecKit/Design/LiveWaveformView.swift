import SwiftUI

/// docs/09 화면 원칙 6: the strip that runs beside the clock while a recording is going — one bar
/// per tenth of a second of the track being written, newest at the right edge, so the whole thing
/// walks leftwards and a microphone that has stopped hearing anything is visible as a flat end.
///
/// It draws what [SegmentedRecorder.livePeaks] answers with rather than anything of its own, and it
/// asks ten times a second, which is the rate the recorder finishes a window at. A `Canvas` and not
/// a stack of shapes: three hundred bars are three hundred views to lay out, ten times a second.
///
/// docs/09 "모션": nothing here is animated. Each tick is a fresh drawing of a new reading — a state
/// change, not a transition — so reduce motion has nothing to turn off.
public struct LiveWaveformView: View {
    @Environment(\.blueprint) private var blueprint
    private let peaks: () -> [Float]

    /// [peaks] is read on every tick rather than passed as a value: the levels are the recorder's
    /// and change on the audio thread, and a `@Published` array of three hundred floats at 10 Hz
    /// would redraw every view that observes the model.
    public init(peaks: @escaping () -> [Float]) {
        self.peaks = peaks
    }

    /// docs/09 "간격": a 2pt bar on a 3pt step, the rhythm the detail screen's waveform is drawn on.
    private static let bar: CGFloat = 2
    private static let step: CGFloat = 3
    /// A window with nothing in it is still a window that was recorded.
    private static let minBar: CGFloat = 1
    /// The rate the recorder finishes a window at.
    private static let tickSec: Double = 0.1

    public var body: some View {
        let ink = blueprint.palette.danger
        return TimelineView(.periodic(from: .now, by: Self.tickSec)) { timeline in
            // Both read out here, in the timeline's own body, so the renderer below is handed
            // values that differ from tick to tick. A `Canvas` whose renderer only *calls*
            // `peaks()` is, from outside, the same drawing of the same nothing every time — and a
            // strip that stands still is exactly the answer this view exists not to give.
            let levels = peaks()
            let now = timeline.date
            Canvas { context, size in
                // The instant this reading was taken. Nothing about a bar is computed from it —
                // the shape is the recorder's windows and only those — but it is what makes the
                // drawing an input that moves rather than a closure that looks unchanged.
                _ = now
                let bars = min(levels.count, Int(size.width / Self.step))
                for index in 0 ..< bars {
                    // From the right: the newest window is the one at the edge the strip grows from.
                    let peak = levels[levels.count - 1 - index]
                    let height = max(CGFloat(Self.height(ofPeak: peak)) * size.height, Self.minBar)
                    let rect = CGRect(
                        x: size.width - CGFloat(index + 1) * Self.step,
                        y: (size.height - height) / 2,
                        width: Self.bar,
                        height: height
                    )
                    context.fill(Path(rect), with: .color(ink))
                }
            }
        }
        // The height of the buttons it sits between in the popover.
        .frame(height: minTouch)
        // The clock beside it says the same thing in words; a picture of it would be read twice.
        .accessibilityHidden(true)
    }

    /// The fraction of the row a peak fills. `sqrt` rather than the peak itself: full scale is the
    /// only level linear amplitude gives a tall bar to, and a quiet room at 0.05 would be a line
    /// indistinguishable from silence — which is the one thing this must not show while it records.
    static func height(ofPeak peak: Float) -> Float {
        sqrt(min(max(peak, 0), 1))
    }
}
