package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint

/**
 * docs/09 "Shape": the one text field the settings draw (UX decisions of 2026-10-08) — the list's search
 * field without its glyph: the input border at 4dp radius on the surface, the accent and a heavier line
 * while it has focus, and its [label] above it rather than floating inside, Material's way.
 *
 * [modifier] is the whole block's (a `weight` goes here); [fieldModifier] is the editable text's own
 * (its test tag). [supporting] is a quiet line under the box.
 */
@Composable
fun BlueprintField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldModifier: Modifier = Modifier,
) {
    val palette = blueprint
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.node)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        label?.let {
            // Said by the field itself, so a reader does not hear it twice.
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted, modifier = Modifier.clearAndSetSemantics {})
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle.copy(color = palette.text),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = fieldModifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
            decorationBox = { field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = MinTouch)
                        .border(if (focused) palette.selectedLine else palette.line, if (focused) palette.accent else palette.inputBorder, shape)
                        .background(palette.surface, shape)
                        .padding(horizontal = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = palette.textMuted, maxLines = 1)
                        field()
                    }
                }
            },
        )
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
    }
}
