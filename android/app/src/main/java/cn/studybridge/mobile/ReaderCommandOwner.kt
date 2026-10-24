package cn.studybridge.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Includes layout/readiness waits in the same cancellation boundary as motion.
// A superseded restore must never settle or unblock a newer directory jump.
internal class ReaderCommandOwner {
    var generation by mutableIntStateOf(0)
        private set
    var settled by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun cancel(expected: Int, onCanceled: () -> Unit = {}) {
        if (expected != generation) return
        // Invalidate the old finally block before canceling. Pointer cancellation
        // resets synchronously, even when its coroutine is already canceled.
        generation++
        job?.cancel()
        onCanceled()
        settled = generation
        busy = false
    }

    fun replace(scope: CoroutineScope, onSettled: () -> Unit = {}, block: suspend (Int) -> Unit) {
        val command = ++generation
        job?.cancel()
        busy = true
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { block(command) }
            finally {
                if (command == generation) {
                    onSettled()
                    settled = command
                    busy = false
                }
            }
        }
    }
}
