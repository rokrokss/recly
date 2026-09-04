package app.recly.android.core

import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * A line for the user that is decided away from the screen — in a ViewModel, or in the mapping of
 * what the parser said onto the field that caused it.
 *
 * It stays a name until something draws it. A ViewModel outlives the activity, so a sentence
 * resolved when the state was built would still be in the old language after the language setting
 * recreated the screen (docs/07 rule 3); a resource id and its arguments cannot go stale.
 *
 * The exception is text that came from somewhere else — docs/02 owns the parser's wording, and a
 * core diagnostic is not ours to translate — which is shown as it arrived.
 */
sealed interface UiMessage {
    /**
     * [args] are `getString` format arguments, so an `Int` stays an `Int` for a `%1$d`.
     *
     * One of them may be another [UiMessage] — a sentence with a core message inside it — and it
     * is resolved along with this one rather than before it, so nothing here goes stale either.
     */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage

    data class Text(val value: String) : UiMessage
}

/**
 * A [UiMessage] as words. [string] is the lookup that knows the language — a screen's resources,
 * a `Context`'s `getString` — and a nested [UiMessage] argument goes through it first.
 */
fun UiMessage.text(string: (Int, List<Any>) -> String): String = when (this) {
    is UiMessage.Res -> string(id, args.map { if (it is UiMessage) it.text(string) else it })
    is UiMessage.Text -> value
}

/** The same, where the [Resources] to read it from are already at hand. */
fun UiMessage.text(resources: Resources): String = text { id, args ->
    if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.toTypedArray())
}
