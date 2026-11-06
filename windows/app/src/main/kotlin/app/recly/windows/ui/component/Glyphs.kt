package app.recly.windows.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint

/**
 * docs/09 "Icons": the marks of a header's actions, drawn as thin geometric lines on a 24-unit grid as the phone's
 * glyphs are — the app carries no icon font.
 */
enum class Glyph { IMPORT, EXPORT, MORE, CHECK }

/**
 * docs/09 "Detail header and More menu": the icon button, as the Mac's and the phones' — the glyph in the quiet ink on a
 * square [MinTouch] target, the `disabled` ink while it is off, and the focus ring. The icon has no words of its own:
 * [label] is what a screen reader says and what shows on hover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    TooltipArea(
        tooltip = {
            Text(
                label,
                modifier = Modifier
                    .border(palette.line, palette.inputBorder, shape)
                    .background(palette.surface, shape)
                    .padding(horizontal = Space.s, vertical = Space.xs),
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
            )
        },
    ) {
        Box(
            modifier
                .size(MinTouch)
                .focusRing()
                .pressCursor(enabled)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (enabled) palette.textMuted else palette.disabled
            Canvas(Modifier.size(GLYPH_SIZE).clearAndSetSemantics { }) {
                val unit = size.width / GRID
                // The grid scales the stroke with it, and the line is 1.5 whatever the glyph's size (docs/09 "Icons").
                scale(unit, unit, pivot = Offset.Zero) { drawGlyph(glyph, ink, 1.5.dp.toPx() / unit) }
            }
        }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color, width: Float) {
    val line = Stroke(width)
    when (glyph) {
        // The Mac's `square.and.arrow.down` and `.up`, in Material Symbols' `download` and `upload`: an open tray, and an
        // arrow into it or out of it.
        Glyph.IMPORT -> {
            drawLine(color, Offset(12f, 4f), Offset(12f, 15f), width)
            drawPath(Path().apply { moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f) }, color, style = line)
            drawTray(color, line)
        }
        Glyph.EXPORT -> {
            drawLine(color, Offset(12f, 4f), Offset(12f, 15f), width)
            drawPath(Path().apply { moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f) }, color, style = line)
            drawTray(color, line)
        }
        Glyph.MORE -> listOf(6f, 12f, 18f).forEach { drawCircle(color, 1.6f, Offset(it, 12f)) }
        // Material Symbols' `check`: Copy all's `Copied`, for as long as it says so.
        Glyph.CHECK -> drawPath(Path().apply { moveTo(4.5f, 11.6f); lineTo(9.5f, 16.6f); lineTo(19.4f, 6.7f) }, color, style = line)
    }
}

private fun DrawScope.drawTray(color: Color, line: Stroke) {
    drawPath(Path().apply { moveTo(5f, 15f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 15f) }, color, style = line)
}

private const val GRID = 24f
private val GLYPH_SIZE = 20.dp
