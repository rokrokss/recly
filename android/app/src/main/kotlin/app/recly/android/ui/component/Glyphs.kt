package app.recly.android.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.blueprint

/**
 * docs/09 "Icons": the Material Symbols this screen set names (`flag`, `share`, `more_horiz`, `search`,
 * `close`, `upload_file`), drawn as thin geometric lines on a 24-unit grid like the tab bar's glyphs —
 * the app carries no icon font.
 */
enum class Glyph { FLAG, SHARE, MORE, SEARCH, CLOSE, IMPORT }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) { drawGlyph(glyph, color) }
    }
}

/** A text-less header action: the glyph, a [label] for screen readers, and the platform's 48dp target. */
@Composable
fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = blueprint
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.semantics { contentDescription = label }) {
        GlyphIcon(glyph, if (enabled) palette.textMuted else palette.grid)
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val line = Stroke(1.5f)
    when (glyph) {
        Glyph.FLAG -> {
            drawLine(color, Offset(6f, 3f), Offset(6f, 21f), 1.5f)
            drawPath(Path().apply { moveTo(6f, 4f); lineTo(19f, 4f); lineTo(16f, 8.5f); lineTo(19f, 13f); lineTo(6f, 13f) }, color, style = line)
        }
        Glyph.SHARE -> {
            listOf(Offset(18f, 5f), Offset(6f, 12f), Offset(18f, 19f)).forEach { drawCircle(color, 2.5f, it, style = line) }
            drawLine(color, Offset(8.2f, 10.8f), Offset(15.8f, 6.2f), 1.5f)
            drawLine(color, Offset(8.2f, 13.2f), Offset(15.8f, 17.8f), 1.5f)
        }
        Glyph.MORE -> listOf(6f, 12f, 18f).forEach { drawCircle(color, 1.6f, Offset(it, 12f)) }
        Glyph.SEARCH -> {
            drawCircle(color, 6f, Offset(10.5f, 10.5f), style = line)
            drawLine(color, Offset(15f, 15f), Offset(20f, 20f), 1.5f)
        }
        Glyph.CLOSE -> {
            drawLine(color, Offset(6f, 6f), Offset(18f, 18f), 1.5f)
            drawLine(color, Offset(18f, 6f), Offset(6f, 18f), 1.5f)
        }
        Glyph.IMPORT -> {
            drawPath(Path().apply { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close() }, color, style = line)
            drawLine(color, Offset(12f, 18f), Offset(12f, 10f), 1.5f)
            drawPath(Path().apply { moveTo(9f, 13f); lineTo(12f, 10f); lineTo(15f, 13f) }, color, style = line)
        }
    }
}
