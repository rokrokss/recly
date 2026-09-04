package app.recly.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * docs/09 "타이포": the UI is the platform sans (Roboto here — no font is bundled, so Korean keeps
 * its glyphs), and *data* is monospace. The scale is 12 / 14 / 16 / 20 / 28 / 44, interpolated with
 * the window width rather than snapped at a breakpoint; `sp` keeps the user's own font size on top
 * of that.
 */
object Type {
    const val SMALL = 12f
    const val BODY_SMALL = 14f
    const val BODY = 16f
    const val TITLE = 20f
    const val HEADLINE = 28f
    const val TIMER = 44f
}

/**
 * docs/09 "유동 타이포". A continuous ramp from the narrowest phone to a tablet-width window: 1.0 at
 * 360dp and below, 1.15 at 600dp and above, straight-line in between. Pure so the ramp can be
 * checked without a window.
 */
fun fluidScale(widthDp: Float): Float {
    val t = ((widthDp - NARROW_DP) / (WIDE_DP - NARROW_DP)).coerceIn(0f, 1f)
    return 1f + t * (WIDE_FACTOR - 1f)
}

private const val NARROW_DP = 360f
private const val WIDE_DP = 600f
private const val WIDE_FACTOR = 1.15f

/**
 * The monospace styles, which Material has no slot for: timers, part numbers, byte counts, hashes,
 * status codes and device ids all come from here (docs/09 "Raw 미학").
 */
data class MonoType(
    val small: TextStyle,
    val bodySmall: TextStyle,
    val body: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val timer: TextStyle,
)

fun monoType(scale: Float): MonoType = MonoType(
    small = mono(Type.SMALL * scale),
    bodySmall = mono(Type.BODY_SMALL * scale),
    body = mono(Type.BODY * scale),
    title = mono(Type.TITLE * scale),
    headline = mono(Type.HEADLINE * scale),
    timer = mono(Type.TIMER * scale, letterSpacing = 1f),
)

private fun mono(size: Float, letterSpacing: Float = 0f) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
    lineHeight = (size * 1.35f).sp,
    letterSpacing = letterSpacing.sp,
)

/** The M3 slots, on the same six sizes, so a component we keep is drawn on the same scale. */
fun reclyTypography(scale: Float): Typography {
    val default = Typography()
    fun sans(size: Float, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = (size * scale).sp,
        lineHeight = (size * scale * 1.4f).sp,
        letterSpacing = spacing.sp,
    )
    return default.copy(
        displaySmall = sans(Type.TIMER),
        headlineMedium = sans(Type.HEADLINE, FontWeight.Medium),
        headlineSmall = sans(Type.HEADLINE),
        titleLarge = sans(Type.TITLE, FontWeight.SemiBold),
        titleMedium = sans(Type.BODY, FontWeight.SemiBold),
        titleSmall = sans(Type.BODY_SMALL, FontWeight.SemiBold),
        bodyLarge = sans(Type.BODY),
        bodyMedium = sans(Type.BODY_SMALL),
        bodySmall = sans(Type.SMALL),
        labelLarge = sans(Type.BODY_SMALL, FontWeight.Medium),
        labelMedium = sans(Type.SMALL, FontWeight.Medium),
        // The section headers and node kickers of the mockup: small, tracked out, never shouted.
        labelSmall = sans(Type.SMALL, FontWeight.Medium, spacing = 0.6f),
    )
}
