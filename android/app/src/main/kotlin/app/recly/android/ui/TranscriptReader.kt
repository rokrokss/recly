@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.recly.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import kotlinx.coroutines.delay
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument
import androidx.compose.ui.res.stringResource
import app.recly.android.R

/** Reading actions stay beside the text; playback keeps the platform's existing position. */
@Composable
internal fun TranscriptReader(
    transcript: Transcript,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
) {
    val document = remember(transcript) { TranscriptDocument(transcript) }
    var query by remember(transcript.recordingId) { mutableStateOf("") }
    var searching by remember(transcript.recordingId) { mutableStateOf(false) }
    var copied by remember(transcript.recordingId) { mutableStateOf(false) }
    val matches = remember(document, query) { document.search(query) }
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(transcript.recordingId, query) { list.scrollToItem(0) }
    LaunchedEffect(copied) { if (copied) { delay(3000); copied = false } }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("transcript-passages"), state = list,
            contentPadding = PaddingValues(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            item(key = "reading-actions") {
                Column {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
                        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        BlueprintButton(stringResource(R.string.transcript_search), { searching = !searching; if (!searching) query = "" },
                            modifier = Modifier.testTag("transcript-search-toggle"), tone = ButtonTone.QUIET)
                        BlueprintButton(if (copied) stringResource(R.string.transcript_copied) else stringResource(R.string.transcript_copy),
                            { clipboard.setText(AnnotatedString(document.plainText)); copied = true },
                            modifier = Modifier.testTag("transcript-copy"), tone = ButtonTone.QUIET)
                    }
                    if (searching) {
                        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.transcript_search)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m).testTag("transcript-search"))
                        if (query.isNotEmpty()) BlueprintButton(stringResource(R.string.transcript_clear_search), { query = "" },
                            modifier = Modifier.padding(horizontal = Space.m), tone = ButtonTone.QUIET)
                    }
                    if (matches.isEmpty()) Text(stringResource(R.string.transcript_no_matches),
                        modifier = Modifier.padding(Space.m), color = blueprint.textMuted)
                }
            }
            items(matches, key = { it.index }) { block ->
                Column(Modifier.padding(horizontal = Space.m)) {
                    val stamp = hms(block.start.toLong())
                    val seekLabel = stringResource(R.string.transcript_seek, stamp)
                    BlueprintButton("$stamp ${block.speaker}", { onSeek(block.start) }, enabled = canSeek && block.start < seekableDurationSec,
                        modifier = Modifier.testTag("transcript-time-${block.index}")
                            .semantics { contentDescription = seekLabel }, tone = ButtonTone.QUIET, monospace = true)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium, color = blueprint.text,
                        modifier = Modifier.testTag("transcript-text-${block.index}"))
                }
            }
        }
    }
}
