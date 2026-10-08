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
 * docs/09 "Icons": the Material Symbols this screen set names (`share`, `more_horiz`, `search`,
 * `close`, `download`), drawn as thin geometric lines on a 24-unit grid like the tab bar's glyphs —
 * the app carries no icon font.
 */
enum class Glyph { SHARE, MORE, SEARCH, CLOSE, IMPORT, DOCUMENT, SUBTITLES, AUDIO, COPY }

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
        Glyph.DOCUMENT -> {
            drawPath(Path().apply { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close() }, color, style = line)
            listOf(11f, 14f, 17f).forEach { drawLine(color, Offset(9f, it), Offset(15f, it), 1.5f) }
        }
        Glyph.SUBTITLES -> {
            drawRoundRect(color, Offset(3f, 5f), androidx.compose.ui.geometry.Size(18f, 14f), androidx.compose.ui.geometry.CornerRadius(2f), style = line)
            drawLine(color, Offset(6f, 12f), Offset(12f, 12f), 1.5f)
            drawLine(color, Offset(6f, 15.5f), Offset(16f, 15.5f), 1.5f)
        }
        Glyph.AUDIO -> listOf(4f to 4f, 8f to 8f, 12f to 6f, 16f to 9f, 20f to 3f).forEach { (x, h) ->
            drawLine(color, Offset(x, 12f - h), Offset(x, 12f + h), 1.5f)
        }
        Glyph.COPY -> {
            drawPath(Path().apply { moveTo(15f, 2f); lineTo(3f, 2f); lineTo(3f, 17f) }, color, style = line)
            drawRect(color, Offset(7f, 6f), androidx.compose.ui.geometry.Size(14f, 16f), style = line)
        }
        // Material Symbols' `download`: an arrow down into an open tray.
        Glyph.IMPORT -> {
            drawLine(color, Offset(12f, 4f), Offset(12f, 15f), 1.5f)
            drawPath(Path().apply { moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f) }, color, style = line)
            drawPath(Path().apply { moveTo(5f, 15f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 15f) }, color, style = line)
        }
    }
}
