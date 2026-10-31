package app.recly.android.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint

/** docs/09 "icon": thin geometric line work, drawn rather than drawn from a font. */
enum class NavGlyph { RECORD, LIST, SETTINGS }

data class NavItem(val glyph: NavGlyph, val label: String, val selected: Boolean, val onClick: () -> Unit)

@Composable
fun BlueprintNavBar(items: List<NavItem>, modifier: Modifier = Modifier) {
    val palette = blueprint
    val compact = LocalConfiguration.current.screenHeightDp < 480
    Column(modifier.fillMaxWidth().background(palette.surface)) {
        HairLine()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = Space.s),
        ) {
            items.forEach { item ->
                val ink = if (item.selected) palette.accent else palette.textMuted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .focusRing(palette)
                        .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
                        .padding(vertical = Space.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    if (!compact) Canvas(Modifier.size(20.dp)) { drawGlyph(item.glyph, ink) }
                    Text(item.label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun DrawScope.drawGlyph(glyph: NavGlyph, color: Color) {
    val stroke = Stroke(width = 1.5.dp.toPx())
    val inset = stroke.width / 2
    val box = Size(size.width - stroke.width, size.height - stroke.width)
    val corner = CornerRadius(2.dp.toPx())
    // The iPhone's tab symbols, drawn in the same line: `dot.square`, `list.bullet.rectangle`, `slider.horizontal.3`.
    when (glyph) {
        NavGlyph.RECORD -> {
            drawRoundRect(color, Offset(inset, inset), box, corner, stroke)
            drawCircle(color, radius = size.width * 0.18f)
        }

        NavGlyph.LIST -> {
            drawRoundRect(color, Offset(inset, inset), box, corner, stroke)
            listOf(0.27f to 0.78f, 0.42f to 0.62f, 0.58f to 0.78f, 0.73f to 0.62f).forEach { (at, end) ->
                val y = size.height * at
                drawCircle(color, radius = stroke.width * 0.75f, center = Offset(size.width * 0.27f, y))
                drawLine(color, Offset(size.width * 0.38f, y), Offset(size.width * end, y), stroke.width)
            }
        }

        // Three sliders, each a line broken by its ring of a knob.
        NavGlyph.SETTINGS -> {
            val knob = size.width * 0.11f
            listOf(0.28f to 0.69f, 0.5f to 0.31f, 0.72f to 0.69f).forEach { (at, x) ->
                val y = size.height * at
                val cx = size.width * x
                drawLine(color, Offset(0f, y), Offset(cx - knob, y), stroke.width)
                drawLine(color, Offset(cx + knob, y), Offset(size.width, y), stroke.width)
                drawCircle(color, radius = knob - stroke.width / 2, center = Offset(cx, y), style = stroke)
            }
        }
    }
}
