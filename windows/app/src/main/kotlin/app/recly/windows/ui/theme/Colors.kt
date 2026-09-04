package app.recly.windows.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * docs/09 "토큰": a neutral palette with one accent, and nothing else. Every colour this shell draws
 * comes from here — Material's own scheme is derived from it in [ReclyDesktopTheme] so the M3
 * components that stay (text fields, chips, dropdowns) land in the same palette as the ones that do
 * not.
 *
 * The same tokens the phone carries (`android/.../ui/theme/Colors.kt`), values included, so a user
 * with both machines is looking at one product. [warningInk] is the one token docs/09 does
 * not name: its `#B28600` amber is 3.3:1 on white — enough for a border, not enough for letters
 * (WCAG AA wants 4.5:1) — so the palette keeps the documented amber for the graphic and a darker
 * one for the text. In dark the two are the same colour, because there the documented one passes.
 */
data class BlueprintColors(
    val background: Color,
    val surface: Color,
    val grid: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val onDanger: Color,
    val success: Color,
    val warning: Color,
    val warningInk: Color,
    val dark: Boolean,
    val highContrast: Boolean,
) {
    /** docs/09 "선": 1dp, and 2dp in high contrast. Connectors, dividers, node borders. */
    val line: Dp get() = if (highContrast) 2.dp else 1.dp

    /**
     * The border of the one that is chosen — always a step heavier than [line] rather than a flat
     * 2dp, because in high contrast the hairline is itself 2dp and a chosen chip has to stay
     * heavier than an unchosen one. The phone's `BlueprintColors.selectedLine` is the same rule.
     */
    val selectedLine: Dp get() = line + 1.dp
}

/** docs/09 팔레트 — Light. */
val BlueprintLight: BlueprintColors = BlueprintColors(
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    grid = Color(0xFFE6E6E2),
    text = Color(0xFF111111),
    textMuted = Color(0xFF5E5E5A),
    accent = Color(0xFF0F62FE),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFDA1E28),
    onDanger = Color(0xFFFFFFFF),
    success = Color(0xFF198038),
    warning = Color(0xFFB28600),
    warningInk = Color(0xFF8A6800),
    dark = false,
    highContrast = false,
)

/** docs/09 팔레트 — Dark. */
val BlueprintDark: BlueprintColors = BlueprintColors(
    background = Color(0xFF0E0F12),
    surface = Color(0xFF16181D),
    grid = Color(0xFF23262D),
    text = Color(0xFFF2F2F0),
    textMuted = Color(0xFF9A9CA3),
    accent = Color(0xFF4589FF),
    // Not white: `#4589FF` under white text is 3.3:1, under the page black it is 5.7:1.
    onAccent = Color(0xFF0E0F12),
    danger = Color(0xFFFA4D56),
    onDanger = Color(0xFF0E0F12),
    success = Color(0xFF42BE65),
    warning = Color(0xFFF1C21B),
    warningInk = Color(0xFFF1C21B),
    dark = true,
    highContrast = false,
)

/**
 * docs/09 "고대비 모드": the grid lines and the secondary text are promoted to the body colour, the
 * accent keeps its saturation, and borders become 2dp (see [BlueprintColors.line]). The dot grid is
 * switched off by the same flag (see [dotGrid]).
 */
fun BlueprintColors.highContrast(): BlueprintColors = copy(
    grid = text,
    textMuted = text,
    highContrast = true,
)

/** The palette for dark and the system's contrast — the one place the four variants are chosen. */
fun blueprintColors(dark: Boolean, highContrast: Boolean): BlueprintColors {
    val base = if (dark) BlueprintDark else BlueprintLight
    return if (highContrast) base.highContrast() else base
}
