package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.SummaryPreferences
import recly.core.notes.NoteSection
import recly.core.notes.NotesPreferences

/**
 * docs/09 "AI view" — "Settings → Notes": what every recording's notes are asked for, whichever AI provider makes them —
 * Notes instructions, up to three sections of the user's own, and About you. Right after ChatGPT, signed in or not;
 * not drawn where ChatGPT is not offered. The fields save when editing ends — focus leaves, Done, the screen or the app
 * goes — with no Save; Remove saves at once. The rows wait for what is stored, so a field never starts from a guess.
 */
@Composable
fun NotesSection() {
    val context = LocalContext.current
    val signIn = remember { ChatGptSignIn.get(context) }
    val connection by produceState<ChatGptConnection>(ChatGptConnection.Unavailable) {
        CoreModule.get(context).core.chatGpt.observe().collect { value = it }
    }
    val stored by produceState<NotesPreferences?>(null) {
        CoreModule.get(context).core.notes.observePreferences().collect { value = it }
    }
    val about by produceState<SummaryPreferences?>(null) {
        CoreModule.get(context).core.summaries.observePreferences().collect { value = it }
    }
    if (connection is ChatGptConnection.Unavailable) return
    val notes = stored ?: return
    val aboutMe = about ?: return

    SectionHeader(stringResource(R.string.notes_title), Modifier.padding(horizontal = Space.m))
    HairLine()
    val instructionsNote = stringResource(R.string.notes_instructions_note)
    PreferenceField(
        saved = notes.instructions,
        max = NotesPreferences.INSTRUCTIONS_MAX,
        label = stringResource(R.string.notes_instructions),
        placeholder = stringResource(R.string.notes_instructions_placeholder),
        supporting = { instructionsNote },
        lines = INSTRUCTIONS_LINES..INSTRUCTIONS_MAX_LINES,
        newlines = true,
        onSave = { text -> signIn.updateNotesPreferences({ it.copy(instructions = text) }) },
        tag = "notes-instructions",
    )
    SectionRows(notes.sections, signIn)
    val aboutNote = stringResource(R.string.notes_about_note)
    PreferenceField(
        saved = aboutMe.aboutMe,
        max = SummaryPreferences.ABOUT_MAX,
        label = stringResource(R.string.notes_about),
        placeholder = stringResource(R.string.notes_about_placeholder),
        supporting = { aboutNote },
        lines = 1..ABOUT_MAX_LINES,
        newlines = false,
        onSave = { text -> signIn.updatePreferences { it.copy(aboutMe = text) } },
        tag = "notes-about",
    )
}

/** One of My sections as the screen holds it: [key] keeps its fields in place while the core has not given it an [id] yet. */
internal data class SectionDraft(val key: Int, val id: String, val name: String, val description: String)

/** What a save sends: the sections that have a name, in order — one without a name is not kept (docs/09 "AI view"). */
internal fun keptSections(drafts: List<SectionDraft>): List<NoteSection> =
    drafts.filter { it.name.isNotBlank() }.map { NoteSection(it.id, it.name.trim(), it.description.trim()) }

/**
 * [drafts] with the ids the core gave the sections [sent] (their keys, in the order saved) — [kept], in that order — so
 * a new section saved again is the same section, not a second one.
 */
internal fun withIds(drafts: List<SectionDraft>, sent: List<Int>, kept: List<NoteSection>): List<SectionDraft> {
    val ids = sent.zip(kept.map { it.id }).toMap()
    return drafts.map { draft -> ids[draft.key]?.takeIf { draft.id.isEmpty() }?.let { draft.copy(id = it) } ?: draft }
}

/**
 * `My sections`: each a one-line name, what goes in it and a quiet Remove, then a quiet Add section while there are
 * fewer than [NotesPreferences.SECTIONS_MAX] — which adds an empty one and puts the cursor in its name. A section's
 * editing ends when the focus leaves both its fields: one left without a name then goes from the list, and what is left
 * is saved. [initial] is what is stored when the rows first draw; from then on the screen's own list is the one shown.
 */
@Composable
private fun SectionRows(initial: List<NoteSection>, signIn: ChatGptSignIn) {
    val palette = blueprint
    var drafts by remember { mutableStateOf(initial.mapIndexed { index, it -> SectionDraft(index, it.id, it.name, it.description) }) }
    var nextKey by remember { mutableIntStateOf(initial.size) }
    var focusNew by remember { mutableStateOf<Int?>(null) }
    val stored by rememberUpdatedState(initial)
    val save = {
        if (keptSections(drafts) != stored) {
            var sent = emptyList<Int>()
            // Read when the save runs, after the one before gave its new sections their ids, so a section is not sent
            // twice without one.
            signIn.updateNotesPreferences({ saved ->
                val now = drafts
                sent = now.filter { it.name.isNotBlank() }.map { it.key }
                saved.copy(sections = keptSections(now))
            }, kept = { kept -> drafts = withIds(drafts, sent, kept.sections) })
        }
    }
    val commit = { key: Int? ->
        drafts = drafts.filterNot { it.name.isBlank() && (key == null || it.key == key) }
        save()
    }
    val current by rememberUpdatedState(commit)
    // The screen going (another tab) and the app going (Home, the switcher) end the editing, as for the fields.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) current(null) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            current(null)
        }
    }
    val focusManager = LocalFocusManager.current
    Column(
        Modifier.fillMaxWidth().background(palette.surface).padding(Space.m).testTag("notes-sections"),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(stringResource(R.string.notes_sections), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        drafts.forEachIndexed { index, draft ->
            key(draft.key) {
                var inside by remember { mutableStateOf(false) }
                val requester = remember { FocusRequester() }
                LaunchedEffect(focusNew) {
                    if (focusNew == draft.key) {
                        requester.requestFocus()
                        focusNew = null
                    }
                }
                Column(
                    Modifier.fillMaxWidth().onFocusChanged {
                        if (inside && !it.hasFocus) commit(draft.key)
                        inside = it.hasFocus
                    },
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    val update = { change: (SectionDraft) -> SectionDraft -> drafts = drafts.map { if (it.key == draft.key) change(it) else it } }
                    BlueprintField(
                        draft.name,
                        { text -> update { it.copy(name = text.take(NotesPreferences.SECTION_NAME_MAX)) } },
                        Modifier.fillMaxWidth(),
                        label = stringResource(R.string.notes_section_name),
                        placeholder = stringResource(R.string.notes_section_name_placeholder),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        fieldModifier = Modifier.focusRequester(requester).testTag("notes-section-name-$index"),
                    )
                    BlueprintField(
                        draft.description,
                        { text -> update { it.copy(description = text.take(NotesPreferences.DESCRIPTION_MAX)) } },
                        Modifier.fillMaxWidth(),
                        label = stringResource(R.string.notes_section_description),
                        placeholder = stringResource(R.string.notes_section_description_placeholder),
                        fieldModifier = Modifier.testTag("notes-section-description-$index"),
                        maxLines = SECTION_DESCRIPTION_LINES,
                    )
                    val remove = draft.name.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.vocabulary_remove, it) }
                        ?: stringResource(R.string.notes_section_remove)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        BlueprintButton(
                            stringResource(R.string.notes_section_remove),
                            {
                                drafts = drafts.filterNot { it.key == draft.key }
                                save()
                            },
                            tone = ButtonTone.QUIET,
                            modifier = Modifier.semantics { contentDescription = remove }.testTag("notes-section-remove-$index"),
                        )
                    }
                }
            }
        }
        if (drafts.size < NotesPreferences.SECTIONS_MAX) {
            BlueprintButton(
                stringResource(R.string.notes_section_add),
                {
                    val added = nextKey++
                    drafts = drafts + SectionDraft(added, "", "", "")
                    focusNew = added
                },
                tone = ButtonTone.QUIET,
                modifier = Modifier.testTag("notes-section-add"),
            )
        }
        Text(stringResource(R.string.notes_sections_note), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
    }
    HairLine()
}

/**
 * One text setting as a table row: its name over the field and a quiet line under it, cut at [max] characters as it
 * is typed. What was typed is saved when editing ends and differs from [saved] — the core trims it. The box is
 * [lines] tall, growing with the text; Return adds a line where [newlines], and is Done — the end of editing —
 * where not.
 */
@Composable
private fun PreferenceField(
    saved: String,
    max: Int,
    label: String,
    placeholder: String,
    /** The quiet line under the field, for what is typed in it now. */
    supporting: (String) -> String,
    lines: IntRange,
    newlines: Boolean,
    onSave: (String) -> Unit,
    tag: String,
) {
    var text by remember(saved) { mutableStateOf(saved) }
    val focusManager = LocalFocusManager.current
    val current by rememberUpdatedState(text)
    val kept by rememberUpdatedState(saved)
    val save by rememberUpdatedState(onSave)
    val commit = { if (current.trim() != kept) save(current) }
    // The screen going (another tab) and the app going (Home, the switcher) both end the editing.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) commit() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            commit()
        }
    }
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(blueprint.surface)) {
        BlueprintField(
            text,
            { text = it.take(max) },
            modifier = Modifier.fillMaxWidth().padding(Space.m),
            label = label,
            placeholder = placeholder,
            supporting = supporting(text),
            keyboardOptions = if (newlines) KeyboardOptions.Default else KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            fieldModifier = Modifier
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                }
                .testTag(tag),
            minLines = lines.first,
            maxLines = lines.last,
        )
        HairLine()
    }
}

/** Notes instructions' box: two lines to start with, eight before it scrolls; About you wraps to three. */
private const val INSTRUCTIONS_LINES = 2
private const val INSTRUCTIONS_MAX_LINES = 8
private const val ABOUT_MAX_LINES = 3

/** What goes in a section wraps to three lines, then scrolls. */
private const val SECTION_DESCRIPTION_LINES = 3
