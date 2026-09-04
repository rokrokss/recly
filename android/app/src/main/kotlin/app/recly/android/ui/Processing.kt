package app.recly.android.ui

import app.recly.android.ui.component.ProcessingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * docs/09 트렌드 2: the window every button that reports its own outcome shares — PROCESSING while
 * the work runs, then DONE if it did what the user asked and FAILED otherwise, including when it
 * throws. The `finally` is the point: a button left saying "…" is a screen the user cannot use.
 *
 * @param phase writes the phase into whichever [ProcessingState] field the screen shows; a screen
 * with more to say while the work runs (see [MainViewModel]'s busy guard) says it here.
 * @param block whether the action did what the user asked, which is what the button shows.
 */
internal fun CoroutineScope.processing(
    phase: (ProcessingState) -> Unit,
    block: suspend () -> Boolean,
) {
    launch {
        phase(ProcessingState.PROCESSING)
        var succeeded = false
        try {
            succeeded = block()
        } finally {
            phase(if (succeeded) ProcessingState.DONE else ProcessingState.FAILED)
        }
    }
}
