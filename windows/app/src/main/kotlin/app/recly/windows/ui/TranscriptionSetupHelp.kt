package app.recly.windows.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.component.BlueprintMenu
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings

/** Anchored, dismissible help beside the new-workflow action; no inline list expansion. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptionSetupHelp(strings: Strings) {
    var presented by remember { mutableStateOf(false) }
    var anchorHeight by remember { mutableStateOf(0) }
    val title = strings[Str.TRANSCRIPTION_SETUP]
    Box {
        TooltipArea(tooltip = {
            Text(title, color = blueprint.text, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.background(blueprint.surface).padding(Space.s))
        }) {
            IconButton(onClick = { presented = !presented }, modifier = Modifier.testTag("transcription-setup")
                .onSizeChanged { anchorHeight = it.height }
                .semantics { contentDescription = title }) {
                Text("ⓘ", style = MaterialTheme.typography.titleLarge, color = blueprint.textMuted,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
        BlueprintMenu(presented, { presented = false }, offset = IntOffset(0, anchorHeight)) {
            Column(Modifier.width(320.dp).padding(Space.m)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = blueprint.text,
                    modifier = Modifier.semantics { heading() })
                Text(strings[Str.TRANSCRIPTION_SETUP_BODY], style = MaterialTheme.typography.bodyMedium,
                    color = blueprint.textMuted, modifier = Modifier.padding(top = Space.s).testTag("transcription-setup-body"))
            }
        }
    }
}
