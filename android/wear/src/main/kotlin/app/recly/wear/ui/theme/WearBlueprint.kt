package app.recly.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * docs/09 토큰, on the watch. A watch screen is always the dark palette — a Galaxy Watch is an OLED
 * that is off most of the time, and a paper background would be a battery bill — so these are the
 * phone's dark values, kept here rather than shared because the watch app does not depend on the
 * phone app (docs/11 W1: only the recorder and the data layer are shared).
 */
object WearBlueprint {
    val background: Color = Color(0xFF0E0F12)
    val surface: Color = Color(0xFF16181D)
    val grid: Color = Color(0xFF23262D)
    val text: Color = Color(0xFFF2F2F0)
    val textMuted: Color = Color(0xFF9A9CA3)
    val accent: Color = Color(0xFF4589FF)
    val onAccent: Color = Color(0xFF0E0F12)
    val danger: Color = Color(0xFFFA4D56)
    val onDanger: Color = Color(0xFF0E0F12)
    val success: Color = Color(0xFF42BE65)
    val warning: Color = Color(0xFFF1C21B)

    /** docs/09 "선": one hairline, and the square node's thick edge. */
    val line: Dp = 1.dp
    val nodeEdge: Dp = 3.dp
    val radius: Dp = 4.dp

    /** docs/09 "타이포": the watch is small enough that the scale stops at 34. */
    val timer: TextStyle = mono(34f)
    val label: TextStyle = mono(13f)
    val small: TextStyle = mono(11f)

    private fun mono(size: Float) = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        lineHeight = (size * 1.25f).sp,
    )
}

/** Wear's own Material, in the Blueprint palette — the components the watch keeps land in it. */
@Composable
fun ReclyWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = WearBlueprint.accent,
            onPrimary = WearBlueprint.onAccent,
            background = WearBlueprint.background,
            onBackground = WearBlueprint.text,
            surfaceContainerLow = WearBlueprint.background,
            surfaceContainer = WearBlueprint.surface,
            surfaceContainerHigh = WearBlueprint.surface,
            onSurface = WearBlueprint.text,
            onSurfaceVariant = WearBlueprint.textMuted,
            outline = WearBlueprint.grid,
            outlineVariant = WearBlueprint.grid,
            error = WearBlueprint.danger,
            onError = WearBlueprint.onDanger,
        ),
        content = content,
    )
}
