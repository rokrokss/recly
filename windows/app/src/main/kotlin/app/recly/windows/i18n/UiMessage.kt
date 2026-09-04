package app.recly.windows.i18n

/**
 * A line for the user that is decided away from the screen — in [app.recly.windows.ui.ShellModel],
 * in the mapping of a `SyncResult` onto words, in a `step_run.last_error`.
 *
 * It stays a name until something draws it. The models outlive any window and the language can
 * change under them, so a sentence resolved when the state was built would still be in the old
 * language after the tray and the windows redrew (docs/07 rule 3); a key and its arguments cannot
 * go stale. This is the phone's `UiMessage` with a [Str] where its resource id is.
 *
 * The exception is text that came from somewhere else — the parser's own wording (docs/02), a core
 * diagnostic, a title the user typed — which is shown as it arrived.
 */
sealed interface UiMessage {
    /**
     * [args] are `String.format` arguments, so an `Int` stays an `Int` for a `%1$d`.
     *
     * One of them may be another [UiMessage] — a sentence with a core message inside it — and it is
     * resolved along with this one rather than before it, so nothing here goes stale either.
     */
    data class Res(val key: Str, val args: List<Any> = emptyList()) : UiMessage

    data class Text(val value: String) : UiMessage
}

/** A [UiMessage] as words, in whatever language [strings] is. */
fun UiMessage.text(strings: Strings): String = when (this) {
    is UiMessage.Res -> {
        // A nested message is resolved with this one, not before it, so it cannot go stale either.
        val resolved = args.map { if (it is UiMessage) it.text(strings) else it }
        strings.get(key, *resolved.toTypedArray())
    }

    is UiMessage.Text -> value
}

/** Shorthand for the common case of a key with no arguments. */
fun Str.message(vararg args: Any): UiMessage = UiMessage.Res(this, args.toList())
