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
import recly.core.notes.NoteKind
import recly.core.notes.NoteSection
import recly.core.notes.NotesPreferences

/**
 * docs/09 "AI view" — "Settings → Summary": what every recording's notes are asked for, whichever AI provider makes them —
 * Notes instructions, Sections — the built-in groups and up to three of the user's own — and About you. Right after
 * ChatGPT, signed in or not; not drawn where ChatGPT is not offered. The fields save when editing ends — focus leaves,
 * Done, the screen or the app goes — with no Save; Remove and Restore defaults save at once. The rows wait for what is
 * stored, so a field never starts from a guess.
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

/** A built-in group's own words in the app's language: its name, and what goes in it. */
internal data class GroupWords(val name: String, val description: String)

/**
 * One row of Sections as the screen holds it: [key] keeps its fields in place while the core has not given a section of
 * the user's own an [id] yet. A built-in group's row ([kind]) holds the words its fields show — its own ([GroupWords])
 * while the user has given it none.
 */
internal data class SectionDraft(val key: Int, val id: String, val name: String, val description: String, val kind: NoteKind = NoteKind.SECTION) {
    val builtIn: Boolean get() = kind != NoteKind.SECTION

    /** Whether a save sends it: a built-in group always, a section of the user's own once it has a name. */
    val sent: Boolean get() = builtIn || name.isNotBlank()
}

/** [sections] as the rows show them, keyed from [firstKey]: a built-in group's empty name or description is its own words. */
internal fun sectionDrafts(sections: List<NoteSection>, words: Map<NoteKind, GroupWords>, firstKey: Int = 0): List<SectionDraft> =
    sections.mapIndexed { index, section ->
        val own = words[section.kind]
        SectionDraft(
            firstKey + index,
            section.id,
            section.name.ifEmpty { own?.name.orEmpty() },
            section.description.ifEmpty { own?.description.orEmpty() },
            section.kind,
        )
    }

/**
 * What a save sends, in order (docs/09 "AI view"): every built-in group, a field in its own words — or left empty — as
 * empty, which is the core's "its own"; then the user's sections that have a name — one without a name is not kept.
 */
internal fun keptSections(drafts: List<SectionDraft>, words: Map<NoteKind, GroupWords>): List<NoteSection> =
    drafts.filter { it.sent }.map { draft ->
        val name = draft.name.trim()
        val description = draft.description.trim()
        val own = words[draft.kind]
        if (own == null) {
            NoteSection(draft.id, name, description, draft.kind)
        } else {
            NoteSection(draft.id, name.takeIf { it != own.name }.orEmpty(), description.takeIf { it != own.description }.orEmpty(), draft.kind)
        }
    }

/**
 * [drafts] once the editing of [key]'s row ends — every row's, for null: a section of the user's own left without a name
 * goes, and a built-in group's field left empty shows its own words again, which is what it saved.
 */
internal fun endEditing(drafts: List<SectionDraft>, key: Int?, words: Map<NoteKind, GroupWords>): List<SectionDraft> =
    drafts.mapNotNull { draft ->
        val own = words[draft.kind]
        when {
            key != null && draft.key != key -> draft
            own != null -> draft.copy(name = draft.name.ifBlank { own.name }, description = draft.description.ifBlank { own.description })
            draft.name.isBlank() -> null
            else -> draft
        }
    }

/** `Restore defaults`: the built-in groups as they come, keyed from [firstKey], then the user's own sections as they are. */
internal fun restoredDrafts(drafts: List<SectionDraft>, words: Map<NoteKind, GroupWords>, firstKey: Int): List<SectionDraft> =
    sectionDrafts(NoteSection.DEFAULTS, words, firstKey) + drafts.filterNot { it.builtIn }

/** `Add section` is there while the user's own sections are fewer than [NotesPreferences.SECTIONS_MAX]; built-ins do not count. */
internal fun addsSection(drafts: List<SectionDraft>): Boolean = drafts.count { !it.builtIn } < NotesPreferences.SECTIONS_MAX

/**
 * [drafts] with the ids the core gave the sections [sent] (their keys, in the order saved) — [kept], in that order — so
 * a new section saved again is the same section, not a second one.
 */
internal fun withIds(drafts: List<SectionDraft>, sent: List<Int>, kept: List<NoteSection>): List<SectionDraft> {
    val ids = sent.zip(kept.map { it.id }).toMap()
    return drafts.map { draft -> ids[draft.key]?.takeIf { draft.id.isEmpty() }?.let { draft.copy(id = it) } ?: draft }
}

/** The built-in groups' own words, from the app's resources. */
@Composable
private fun groupWords(): Map<NoteKind, GroupWords> = mapOf(
    NoteKind.POINT to GroupWords(stringResource(R.string.notes_key_points), stringResource(R.string.notes_key_points_about)),
    NoteKind.DECISION to GroupWords(stringResource(R.string.notes_decisions), stringResource(R.string.notes_decisions_about)),
    NoteKind.TODO to GroupWords(stringResource(R.string.notes_todos), stringResource(R.string.notes_todos_about)),
    NoteKind.QUESTION to GroupWords(stringResource(R.string.notes_questions), stringResource(R.string.notes_questions_about)),
)

/**
 * `Sections`: one list — the built-in groups the user kept, then their own — each a one-line name, what goes in it and a
 * quiet Remove; then a quiet Add section while the user's own are fewer than [NotesPreferences.SECTIONS_MAX], which adds
 * an empty one and puts the cursor in its name, and a quiet Restore defaults while the built-in groups are not as they
 * come. A built-in group's fields show its own words until the user gives it others, and those are their placeholders.
 * A section's editing ends when the focus leaves both its fields: one of the user's own left without a name then goes
 * from the list, a built-in group's empty field shows its own words again, and what is left is saved. [initial] is what
 * is stored when the rows first draw; from then on the screen's own list is the one shown.
 */
@Composable
private fun SectionRows(initial: List<NoteSection>, signIn: ChatGptSignIn) {
    val palette = blueprint
    val words = groupWords()
    var drafts by remember { mutableStateOf(sectionDrafts(initial, words)) }
    var nextKey by remember { mutableIntStateOf(initial.size) }
    var focusNew by remember { mutableStateOf<Int?>(null) }
    val stored by rememberUpdatedState(initial)
    val save = {
        if (keptSections(drafts, words) != stored) {
            var sent = emptyList<Int>()
            // Read when the save runs, after the one before gave its new sections their ids, so a section is not sent
            // twice without one.
            signIn.updateNotesPreferences({ saved ->
                val now = drafts
                sent = now.filter { it.sent }.map { it.key }
                saved.copy(sections = keptSections(now, words))
            }, kept = { kept -> drafts = withIds(drafts, sent, kept.sections) })
        }
    }
    val commit = { key: Int? ->
        drafts = endEditing(drafts, key, words)
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
                    val own = words[draft.kind]
                    BlueprintField(
                        draft.name,
                        { text -> update { it.copy(name = text.take(NotesPreferences.SECTION_NAME_MAX)) } },
                        Modifier.fillMaxWidth(),
                        label = stringResource(R.string.notes_section_name),
                        placeholder = own?.name ?: stringResource(R.string.notes_section_name_placeholder),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        fieldModifier = Modifier.focusRequester(requester).testTag("notes-section-name-$index"),
                    )
                    BlueprintField(
                        draft.description,
                        { text -> update { it.copy(description = text.take(NotesPreferences.DESCRIPTION_MAX)) } },
                        Modifier.fillMaxWidth(),
                        label = stringResource(R.string.notes_section_description),
                        placeholder = own?.description ?: stringResource(R.string.notes_section_description_placeholder),
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
        if (addsSection(drafts)) {
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
        if (NoteSection.changed(keptSections(drafts, words))) {
            BlueprintButton(
                stringResource(R.string.notes_section_restore),
                {
                    drafts = restoredDrafts(drafts, words, nextKey)
                    nextKey += NoteSection.DEFAULTS.size
                    save()
                },
                tone = ButtonTone.QUIET,
                modifier = Modifier.testTag("notes-section-restore"),
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
