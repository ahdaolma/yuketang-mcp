package cn.studybridge.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

// Observe only real reading gestures. Never consume content scrolling or react to
// programmatic jumps, streaming updates, or a document that cannot scroll.
@Stable class ReadingChromeState(private val threshold: Float) {
    var fraction by mutableFloatStateOf(0f)
        private set
    var locked = false
        set(value) { field = value; if (value) restore() }
    private var distance = 0f
    fun restore() { fraction = 0f; distance = 0f }
    private fun moved(delta: Float) {
        if (locked || delta == 0f) return
        if (distance * delta < 0f) distance = 0f
        distance += delta
        if (abs(distance) >= threshold / 3f) fraction = (fraction - delta / (threshold * 3f)).coerceIn(0f, 1f)
    }
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) moved(if (fraction > 0f && consumed.y == 0f && available.y > 0f) available.y else consumed.y)
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            fraction = if (fraction >= 0.5f) 1f else 0f
            distance = 0f
            return Velocity.Zero
        }
    }
}

val LocalReadingChrome = staticCompositionLocalOf<ReadingChromeState?> { null }
// Short, wide windows use a side rail and one inline toolbar, rather than
// spending their scarce height on the portrait app header and bottom tabs.
val LocalInlineReadingHeader = staticCompositionLocalOf { false }

val LocalReadingCompactFraction = staticCompositionLocalOf { 0f }

val LocalFloatingReadingTools = staticCompositionLocalOf { false }
