package app.recly.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import androidx.compose.ui.res.stringResource
import app.recly.android.R

/** Optional guidance in the workflow list; no forced onboarding. */
@Composable
internal fun TranscriptionSetupHelp() {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(Space.m)) {
        BlueprintButton(stringResource(R.string.transcription_setup), { expanded = !expanded }, tone = ButtonTone.QUIET,
            modifier = Modifier.testTag("transcription-setup").semantics {
                if (expanded) collapse { expanded = false; true }
                else expand { expanded = true; true }
            })
        if (expanded) Text(stringResource(R.string.transcription_setup_body), style = MaterialTheme.typography.bodySmall,
            color = blueprint.textMuted, modifier = Modifier.padding(top = Space.s))
    }
}
