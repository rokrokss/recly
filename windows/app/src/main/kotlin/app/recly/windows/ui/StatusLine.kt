package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.i18n.UiMessage

/**
 * A line and the diagnostic that came with it, moving together — the tray's status and the editor's
 * banner are the same pair (docs/07 §5: a core code may arrive as `CODE|detail`).
 *
 * They move together because a detail that outlived its line would be a diagnostic filed under the
 * wrong complaint. [say] is the only way to set either, which is what makes that true.
 *
 * [text] is a name and not a sentence: both surfaces stay up across a language change (docs/07
 * rule 3), so the words are chosen where they are drawn. [T] is [UiMessage] for a line that is
 * always saying something, and `UiMessage?` for a banner that is usually saying nothing.
 */
class StatusLine<T>(initial: T) {

    var text: T by mutableStateOf(initial)
        private set

    /** Never translated: a diagnostic is what the server or the store actually said. */
    var detail: String? by mutableStateOf(null)
        private set

    fun say(line: T, detail: String? = null) {
        text = line
        this.detail = detail
    }
}
