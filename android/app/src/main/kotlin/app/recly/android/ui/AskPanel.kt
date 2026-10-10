@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.settings.AppLanguage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintChip
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.FillRow
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.dotGrid
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptModel

/** docs/08 "Ask": each preset under its own words; Translate names the app's language, as the App language list does. */
@StringRes
internal fun AskPreset.presetLabel(): Int = when (this) {
    AskPreset.FOLLOW_UP_EMAIL -> R.string.ask_follow_up_email
    AskPreset.ACTION_ITEMS -> R.string.ask_action_items
    AskPreset.OPEN_QUESTIONS -> R.string.ask_open_questions
    AskPreset.TRANSLATE -> R.string.ask_translate
    AskPreset.MY_SPEAKING -> R.string.ask_my_speaking
}

/** What the question field holds when the panel opens: the question already running or answered, else nothing. */
internal fun askedQuestion(state: AskState): String = when (state) {
    AskState.None -> null
    is AskState.Running -> state.question
    is AskState.Ready -> state.answer.question
    is AskState.Failed -> state.question
}.orEmpty()

/** Ask is pressable with words in the field and nothing running. */
internal fun canAsk(state: AskState, question: String): Boolean = state !is AskState.Running && question.isNotBlank()

/** The preset behind the answer on screen — or the one being written, or the one that failed — whose chip has `✓`. */
internal fun askedPreset(state: AskState): AskPreset? = when (state) {
    AskState.None -> null
    is AskState.Running -> state.preset.takeIf { state.question == null }
    is AskState.Ready -> state.answer.preset.takeIf { state.answer.question == null }
    is AskState.Failed -> state.preset.takeIf { state.question == null }
}

/**
 * docs/08 "Ask": one question about the open recording, in a full-height sheet over the detail — the Share sheet's
 * paper and corners. The presets that make sense for it ([presets]) ask at once; the field asks the user's own. While
 * one runs the inputs wait under `Asking…`; the answer is selectable text whose citations play from their time
 * ([canSeek], [onSeek] — the transcript's own seek), with `ChatGPT · <model>` and Copy all; a failure is the summary's
 * notice, its Retry asking the same again. Nothing is kept: closing is [onClose], which lets the answer go — so only
 * the ✕ and Back close the sheet, never a drag that could throw an answer away.
 */
@Composable
internal fun AskPanel(
    state: AskState,
    presets: List<AskPreset>,
    models: List<ChatGptModel>,
    canSeek: (Double) -> Boolean,
    onSeek: (Double) -> Unit,
    onAsk: (AskPreset?, String?) -> Unit,
    onClose: () -> Unit,
) {
    val palette = blueprint
    val running = state is AskState.Running
    var question by remember { mutableStateOf(askedQuestion(state)) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = palette.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxSize().dotGrid(palette).navigationBarsPadding().imePadding().testTag("ask-panel")) {
            Row(Modifier.fillMaxWidth().padding(start = Space.m, end = Space.xs, top = Space.xs, bottom = Space.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ask_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = palette.text)
                GlyphButton(Glyph.CLOSE, stringResource(R.string.action_close), onClose, Modifier.testTag("ask-close"))
            }
            HairLine()
            if (presets.isNotEmpty()) {
                // The app's own language, under its own name — the App language list's word for it.
                val language = stringResource(AppLanguage.effective(LocalConfiguration.current.locales[0]).labelRes())
                val asked = askedPreset(state)
                FillRow(Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.m, vertical = Space.s)) {
                    presets.forEach { preset ->
                        // An action, not a choice: a tap asks. The one the answer below came from has `✓`.
                        BlueprintChip(
                            if (preset == AskPreset.TRANSLATE) stringResource(preset.presetLabel(), language) else stringResource(preset.presetLabel()),
                            selected = preset == asked,
                            onClick = { onAsk(preset, null) },
                            enabled = !running,
                            role = Role.Button,
                            modifier = Modifier.testTag("ask-${preset.name.lowercase()}"),
                        )
                    }
                }
                HairLine()
            }
            val own = stringResource(R.string.ask_own_question)
            Row(
                Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.m, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.Bottom,
            ) {
                // The keyboard's own Send asks, as the button beside it does.
                BlueprintField(
                    question,
                    { question = it },
                    modifier = Modifier.weight(1f),
                    placeholder = own,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canAsk(state, question)) onAsk(null, question) }),
                    fieldModifier = Modifier.semantics { contentDescription = own }.testTag("ask-field"),
                    maxLines = QUESTION_LINES,
                    enabled = !running,
                )
                BlueprintButton(stringResource(R.string.ask_ask), { onAsk(null, question) }, tone = ButtonTone.PRIMARY,
                    enabled = canAsk(state, question), modifier = Modifier.testTag("ask-send"))
            }
            HairLine()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state) {
                    AskState.None -> Unit
                    is AskState.Running -> LoadingText(stringResource(R.string.ask_running), MaterialTheme.typography.bodySmall, palette.textMuted,
                        Modifier.padding(horizontal = Space.m, vertical = Space.s).testTag("ask-running"))
                    is AskState.Ready -> Column(Modifier.fillMaxSize()) {
                        val answer = state.answer
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(palette.surface).verticalScroll(rememberScrollState())) {
                            CitedText(answer.text, canSeek, onSeek, Modifier.testTag("ask-answer"))
                        }
                        HairLine()
                        CopyFooter(stringResource(R.string.summary_model, modelLabel(answer.modelName, answer.model, models)), answer.text,
                            Modifier.testTag("ask-copy"))
                    }
                    is AskState.Failed -> ChatGptFailure(state.reason, R.string.ask_failed, { onAsk(state.preset, state.question) },
                        Modifier.fillMaxSize(), retryTag = "ask-retry")
                }
            }
        }
    }
}

/** The question field grows to four lines, then scrolls. */
private const val QUESTION_LINES = 4
