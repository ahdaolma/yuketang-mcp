package cn.studybridge.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

// Keep each slot mounted when the IME opens, preserving input focus and drafts.
// In short landscape windows, tools share the composer's row to leave room to read.
@Composable fun ReadingChatLayout(beside: Boolean, modifier: Modifier = Modifier, toolbar: @Composable () -> Unit, body: @Composable () -> Unit, composer: @Composable () -> Unit) {
    Layout(modifier = modifier, content = { Box { toolbar() }; Box { body() }; Box { composer() } }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val side = beside && width >= 560.dp.roundToPx()
        val toolsWidth = if (side) minOf(336.dp.roundToPx(), (width * 0.55f).toInt()) else width
        fun slot(width: Int, height: Int = constraints.maxHeight) = constraints.copy(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = height.coerceAtLeast(0))
        val tools = children[0].measure(slot(toolsWidth))
        val input = children[2].measure(slot(if (side) width - toolsWidth else width))
        val controlsHeight = if (side) maxOf(tools.height, input.height) else tools.height + input.height
        val viewportHeight = (height - controlsHeight).coerceAtLeast(0)
        val viewport = children[1].measure(slot(width, viewportHeight).copy(minHeight = viewportHeight))
        layout(width, height) {
            viewport.placeRelative(0, if (side) 0 else tools.height)
            tools.placeRelative(0, if (side) height - controlsHeight + (controlsHeight - tools.height) / 2 else 0)
            input.placeRelative(if (side) toolsWidth else 0, height - input.height)
        }
    }
}
