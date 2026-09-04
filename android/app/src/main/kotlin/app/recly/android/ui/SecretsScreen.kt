package app.recly.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.getSystemService
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.ScreenHeader
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/**
 * docs/11 A6 · docs/05 "시크릿", deliverable 3. Values live in this device's secure store and are
 * never synced; a generated `whsec_…` is readable exactly once — here, before it is stored.
 *
 * Drawn as docs/09 asks: a header with the count, the stored names as ledger rows in monospace
 * (a secret name is an identifier the workflow document refers to, not a sentence), square bordered
 * buttons, and the one rule this design has between the rows.
 */
@Composable
fun SecretsScreen(
    names: List<String>,
    form: SecretsState,
    onName: (String) -> Unit,
    onValue: (String) -> Unit,
    onGenerate: () -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = blueprint
    // The value is a signing key: masked by default, off the keyboard's dictionary and out of its
    // suggestion strip, and shown only while the user asks for it (Sol M2-L4 #2).
    var shown by remember { mutableStateOf(false) }
    // docs/09 화면 원칙 5: what happened is said inline, where the thing that happened is. A toast
    // is chrome the app does not own and a screen reader announces out of order.
    var copied by remember(form.value) { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.secrets_title),
            meta = names.size.toString(),
            trailing = {
                BlueprintButton(
                    label = stringResource(R.string.action_close),
                    onClick = onClose,
                    tone = ButtonTone.QUIET,
                )
            },
        )
        HairLine()

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.secrets_hint),
                modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textMuted,
            )

            names.forEach { name -> SecretRow(name = name, onDelete = { onDelete(name) }) }
            if (names.isEmpty()) {
                Text(
                    stringResource(R.string.secrets_empty),
                    modifier = Modifier.padding(horizontal = Space.m, vertical = Space.s),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textMuted,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                SectionHeader(stringResource(R.string.secrets_add))
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.secrets_name)) },
                    singleLine = true,
                    textStyle = mono.bodySmall,
                    isError = form.error != null,
                    supportingText = { Text(stringResource(form.error ?: R.string.secrets_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("secret-name"),
                )
                OutlinedTextField(
                    value = form.value,
                    onValueChange = onValue,
                    label = { Text(stringResource(R.string.secrets_value)) },
                    singleLine = true,
                    textStyle = mono.bodySmall,
                    visualTransformation = if (shown) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    trailingIcon = {
                        BlueprintButton(
                            label = stringResource(if (shown) R.string.secrets_hide else R.string.secrets_show),
                            onClick = { shown = !shown },
                            modifier = Modifier
                                .padding(end = Space.s)
                                .testTag("secret-value-show"),
                            tone = ButtonTone.QUIET,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("secret-value"),
                )

                if (form.generated) {
                    GeneratedSecret(
                        value = form.value,
                        copied = copied,
                        onCopy = {
                            copy(context, form.value)
                            copied = true
                        },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    // A reveal is per entry: the next secret typed into the cleared form starts
                    // hidden again.
                    BlueprintButton(
                        label = stringResource(R.string.action_save),
                        onClick = { shown = false; onSave() },
                        modifier = Modifier.testTag("secret-save"),
                        tone = ButtonTone.PRIMARY,
                    )
                    BlueprintButton(
                        label = stringResource(R.string.secrets_generate),
                        onClick = onGenerate,
                        modifier = Modifier.testTag("secret-generate"),
                    )
                }
            }
        }
    }
}

/** One stored secret: the name as the document spells it, and the one thing that can be done to it. */
@Composable
private fun SecretRow(name: String, onDelete: () -> Unit) {
    val palette = blueprint
    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = mono.bodySmall,
                color = palette.text,
                maxLines = 1,
            )
            BlueprintButton(
                label = stringResource(R.string.action_delete),
                onClick = onDelete,
                tone = ButtonTone.DANGER,
            )
        }
        HairLine()
    }
}

/**
 * docs/05: the generated `whsec_…` is on screen once and then never again. It is drawn as a node on
 * the grid rather than a card, in monospace because it is a key, and it is plain [Text] — not a
 * selectable field — so the only way it leaves this screen is the copy below it, which marks the
 * clip sensitive.
 */
@Composable
private fun GeneratedSecret(value: String, copied: Boolean, onCopy: () -> Unit) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.node)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(palette.line, palette.grid, shape)
            .background(palette.surface, shape)
            .padding(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            stringResource(R.string.secrets_generated),
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
        )
        Text(value, style = mono.bodySmall, color = palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            BlueprintButton(label = stringResource(R.string.secrets_copy), onClick = onCopy)
            if (copied) {
                Text(
                    stringResource(R.string.secrets_copied),
                    modifier = Modifier.testTag("secret-copied"),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
        }
    }
}

private fun copy(context: Context, value: String) {
    val clip = ClipData.newPlainText("webhook secret", value).apply {
        // Without this the system's copy confirmation prints the secret on screen — and it is a
        // signing key. API 33+; minSdk is 34.
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService<ClipboardManager>()?.setPrimaryClip(clip)
}
