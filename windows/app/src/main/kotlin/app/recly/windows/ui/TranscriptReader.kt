@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.recly.windows.ui

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
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import kotlinx.coroutines.delay
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings

/** Reading actions stay beside the text; playback keeps the platform's existing position. */
@Composable
internal fun TranscriptReader(
    transcript: Transcript,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    strings: Strings,
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
                        BlueprintButton(strings[Str.TRANSCRIPT_SEARCH], { searching = !searching; if (!searching) query = "" },
                            modifier = Modifier.testTag("transcript-search-toggle"), tone = ButtonTone.QUIET)
                        BlueprintButton(if (copied) strings[Str.TRANSCRIPT_COPIED] else strings[Str.TRANSCRIPT_COPY],
                            { clipboard.setText(AnnotatedString(document.plainText)); copied = true },
                            modifier = Modifier.testTag("transcript-copy"), tone = ButtonTone.QUIET)
                    }
                    if (searching) {
                        OutlinedTextField(query, { query = it }, label = { Text(strings[Str.TRANSCRIPT_SEARCH]) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m).testTag("transcript-search"))
                        if (query.isNotEmpty()) BlueprintButton(strings[Str.TRANSCRIPT_CLEAR_SEARCH], { query = "" },
                            modifier = Modifier.padding(horizontal = Space.m), tone = ButtonTone.QUIET)
                    }
                    if (matches.isEmpty()) Text(strings[Str.TRANSCRIPT_NO_MATCHES],
                        modifier = Modifier.padding(Space.m), color = blueprint.textMuted)
                }
            }
            items(matches, key = { it.index }) { block ->
                Column(Modifier.padding(horizontal = Space.m)) {
                    val stamp = LedgerFormat.elapsed((block.start * 1000).toLong())
                    val seekLabel = strings[Str.TRANSCRIPT_SEEK, stamp]
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
