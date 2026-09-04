package app.recly.android.workflow

import androidx.annotation.StringRes
import app.recly.android.R

/**
 * docs/05 "시크릿": the name is the only part of a secret that ever leaves this device — it is what
 * a step's `secretRef` holds — so it obeys the same `^[a-z][a-z0-9_]{0,31}$` the parser enforces on
 * `secretRef` (docs/02). A name this rejects could be stored but never referenced.
 */
object SecretName {
    val RULE = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** Null when the name is usable, otherwise the string that says why it is not. */
    @StringRes
    fun problem(name: String, existing: Collection<String> = emptyList()): Int? = when {
        name.isEmpty() -> R.string.secret_name_empty
        !RULE.matches(name) -> R.string.secret_name_invalid
        name in existing -> R.string.secret_name_taken
        else -> null
    }
}
