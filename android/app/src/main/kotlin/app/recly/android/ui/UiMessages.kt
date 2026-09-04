package app.recly.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.recly.android.core.UiMessage
import app.recly.android.core.text

/**
 * A [UiMessage] is decided away from the screen; only here does it become words — in the language
 * the screen is being drawn in, however many messages are nested inside it.
 */
@Composable
internal fun UiMessage.text(): String = text(LocalResources.current)
