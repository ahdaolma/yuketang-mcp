package cn.studybridge.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReaderCommandOwnerTest {
    @Test fun aDirectoryJumpCancelsAnInitialRestoreWaitingForUnmountedContent() = runBlocking {
        val commands = ReaderCommandOwner()
        var restoreCanceled = false
        var restoredPage = -1
        commands.replace(this) {
            try { awaitCancellation() }
            finally { restoreCanceled = true }
        }
        commands.replace(this) { restoredPage = 4 }
        yield()
        assertTrue(restoreCanceled)
        assertEquals(4, restoredPage)
        assertFalse(commands.busy)
        assertEquals(commands.generation, commands.settled)
    }

    @Test fun canceledAnimationCannotClearANewerWaitOrMarkItSettled() = runBlocking {
        val commands = ReaderCommandOwner()
        val ready = CompletableDeferred<Unit>()
        var oldSettles = 0
        var newSettles = 0
        commands.replace(this, onSettled = { oldSettles++ }) { awaitCancellation() }
        commands.replace(this, onSettled = { newSettles++ }) { ready.await() }
        yield()
        assertEquals(0, oldSettles)
        assertEquals(0, newSettles)
        assertTrue(commands.busy)
        assertTrue(commands.settled < commands.generation)
        ready.complete(Unit)
        yield()
        assertEquals(0, oldSettles)
        assertEquals(1, newSettles)
        assertFalse(commands.busy)
        assertEquals(commands.generation, commands.settled)
    }

    @Test fun cancelingAnOldPointerOwnerCannotResetANewerJump() = runBlocking {
        val commands = ReaderCommandOwner()
        var resets = 0
        commands.replace(this) { awaitCancellation() }
        val oldOwner = commands.generation
        commands.replace(this) { awaitCancellation() }
        commands.cancel(oldOwner) { resets++ }
        assertEquals(0, resets)
        assertTrue(commands.busy)
        val currentOwner = commands.generation
        commands.cancel(currentOwner) { resets++ }
        commands.cancel(currentOwner) { resets++ }
        assertEquals(1, resets)
        assertFalse(commands.busy)
        yield()
        assertEquals(1, resets)
        assertEquals(commands.generation, commands.settled)
    }
}
