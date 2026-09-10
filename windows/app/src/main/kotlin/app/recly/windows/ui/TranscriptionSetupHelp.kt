package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings

/** Optional guidance in the workflow list; no forced onboarding. */
@Composable
internal fun TranscriptionSetupHelp(strings: Strings) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(Space.m)) {
        BlueprintButton(strings[Str.TRANSCRIPTION_SETUP], { expanded = !expanded }, tone = ButtonTone.QUIET,
            modifier = Modifier.testTag("transcription-setup").semantics {
                if (expanded) collapse { expanded = false; true }
                else expand { expanded = true; true }
            })
        if (expanded) Text(strings[Str.TRANSCRIPTION_SETUP_BODY], style = MaterialTheme.typography.bodySmall,
            color = blueprint.textMuted, modifier = Modifier.padding(top = Space.s))
    }
}
