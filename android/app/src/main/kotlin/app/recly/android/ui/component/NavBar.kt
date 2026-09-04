package app.recly.android.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** docs/09 "아이콘": thin geometric line work, drawn rather than drawn from a font. */
enum class NavGlyph { RECORD, LIST, WORKFLOWS, SETTINGS }

data class NavItem(val glyph: NavGlyph, val label: String, val selected: Boolean, val onClick: () -> Unit)

@Composable
fun BlueprintNavBar(items: List<NavItem>, modifier: Modifier = Modifier) {
    val palette = blueprint
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
                        .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
                        .padding(vertical = Space.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Canvas(Modifier.size(20.dp)) { drawGlyph(item.glyph, ink) }
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
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
    when (glyph) {
        NavGlyph.RECORD -> {
            drawRoundRect(color, Offset(inset, inset), box, corner, stroke)
            val side = size.width * 0.4f
            val at = (size.width - side) / 2
            drawRoundRect(color, Offset(at, at), Size(side, side), CornerRadius(1.dp.toPx()))
        }

        NavGlyph.LIST -> {
            drawRoundRect(color, Offset(inset, inset), box, corner, stroke)
            val left = size.width * 0.25f
            val right = size.width * 0.75f
            listOf(0.32f, 0.5f, 0.68f).forEach { at ->
                drawLine(color, Offset(left, size.height * at), Offset(right, size.height * at), stroke.width)
            }
        }

        NavGlyph.WORKFLOWS -> {
            val side = size.width * 0.42f
            val at = (size.width - side) / 2
            drawRoundRect(color, Offset(at, inset), Size(side, side), corner, stroke)
            drawRoundRect(color, Offset(at, size.height - side - inset), Size(side, side), corner, stroke)
            drawLine(
                color,
                Offset(size.width / 2, side + inset),
                Offset(size.width / 2, size.height - side - inset),
                stroke.width,
            )
        }

        NavGlyph.SETTINGS -> drawCircle(color, radius = box.width / 2, style = stroke)
    }
}
