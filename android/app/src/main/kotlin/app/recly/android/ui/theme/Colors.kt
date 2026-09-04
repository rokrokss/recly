package app.recly.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * docs/09 "토큰": a neutral palette with one accent, and nothing else. Every colour the app draws
 * comes from here — Material's own scheme is derived from it in [ReclyTheme] so the M3 components
 * that stay (switches, text fields, dialogs) land in the same palette as the ones that do not.
 *
 * [warningInk] is the one token docs/09 does not name. Its `#B28600` amber is 3.3:1 on white, which
 * is enough for a border or a swatch and not enough for text (WCAG AA wants 4.5:1), so the palette
 * carries a darker amber for the letters and keeps the documented one for the graphic. In dark the
 * two are the same colour, because there the documented one already passes.
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
) {
    /** docs/09 "선": 1dp. Connectors, dividers, node borders. */
    val line: Dp get() = 1.dp

    /** What a chosen thing — a selected graph node, a selected chip — draws instead of [line]. */
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
)

/** The palette for the system's dark-mode answer — the one place the two variants are chosen. */
fun blueprintColors(dark: Boolean): BlueprintColors = if (dark) BlueprintDark else BlueprintLight
