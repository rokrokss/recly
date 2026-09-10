package app.recly.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint

/** Optional help beside the new-workflow action, presented without moving the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptionSetupHelp() {
    var presented by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.transcription_setup)
    IconButton(onClick = { presented = true }, modifier = Modifier.testTag("transcription-setup")
        .semantics { contentDescription = title }) {
        Text("ⓘ", style = MaterialTheme.typography.titleLarge, color = blueprint.textMuted,
            modifier = Modifier.clearAndSetSemantics { })
    }
    if (presented) {
        ModalBottomSheet(onDismissRequest = { presented = false }, containerColor = blueprint.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = blueprint.text,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.transcription_setup_body), style = MaterialTheme.typography.bodyMedium,
                    color = blueprint.textMuted, modifier = Modifier.testTag("transcription-setup-body"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    BlueprintButton(stringResource(R.string.action_close), { presented = false }, tone = ButtonTone.QUIET,
                        modifier = Modifier.testTag("transcription-setup-close"))
                }
            }
        }
    }
}
