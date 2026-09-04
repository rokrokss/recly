package app.recly.windows.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.recly.windows.record.RecordingOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * docs/03: a recording that has ended is waiting for its name, and its job is not made until the
 * answer is in. That wait and the next start have to be ordered against each other, and this is the
 * only place they are: the prompt is published from the recorder's finish, a start goes through
 * [ifIdle], and both take the same lock — so a prompt published while a start is still preparing
 * (reading the workflow summaries, say) cannot be stepped over.
 *
 * Only one recording ever waits. A second one that finishes while the first is still unnamed does
 * not take the prompt away from it ([publish] answers false); the shell queues that one untitled,
 * which loses a name nobody typed rather than a job.
 */
class TitleGate {

    private val mutex = Mutex()

    /** What the tray is showing a dialog for, if anything. */
    var pending: RecordingOutcome? by mutableStateOf(null)
        private set

    /** True when [outcome] now owns the prompt; false when another recording is already waiting. */
    suspend fun publish(outcome: RecordingOutcome): Boolean = mutex.withLock {
        if (pending != null) return@withLock false
        pending = outcome
        true
    }

    /** The waiting recording, handed over once — a second caller gets null. */
    suspend fun take(): RecordingOutcome? = mutex.withLock {
        pending?.also { pending = null }
    }

    /** Runs [block] only while nothing is waiting to be named; null means the start was refused. */
    suspend fun <T : Any> ifIdle(block: suspend () -> T?): T? = mutex.withLock {
        if (pending != null) null else block()
    }
}
