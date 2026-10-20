package cn.studybridge.mobile

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// Keep each slot mounted when the IME opens, preserving input focus and drafts.
// In short landscape windows, tools share the composer's row to leave room to read.
@Composable fun ReadingChatLayout(beside: Boolean, modifier: Modifier = Modifier, toolbar: @Composable () -> Unit, body: @Composable () -> Unit, composer: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
    if (!constraints.hasBoundedHeight) {
        Column { toolbar(); body(); composer() }
        return@BoxWithConstraints
    }
    // When even compact rows leave fewer than a few reading lines, let tools
    // float beside the text. Their panel keeps the original actions available.
    val floating = beside && maxHeight < 190.dp
    val compact = if (beside) 0f else LocalReadingCompactFraction.current.coerceIn(0f, 1f)
    val panelWidth = minOf(336.dp, maxWidth - 16.dp)
    val compactTools = compact > 0.5f
    Layout(modifier = Modifier.fillMaxSize().clipToBounds(), content = {
        Box(Modifier.alpha(if (compactTools) (compact - 0.5f) * 2f else 1f - compact * 2f)) {
            if (floating || compactTools) FloatingReadingTools(panelWidth, toolbar) else toolbar()
        }
        Box { body() }
        Box { composer() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val side = beside && !floating && width >= 560.dp.roundToPx()
        val toolsWidth = if (floating || compactTools) 48.dp.roundToPx() else if (side) minOf(336.dp.roundToPx(), (width * 0.55f).toInt()) else width
        fun slot(width: Int, height: Int = constraints.maxHeight) = constraints.copy(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = height.coerceAtLeast(0))
        val tools = children[0].measure(slot(toolsWidth))
        val input = children[2].measure(slot(if (side) width - toolsWidth else width))
        val toolStrip = (tools.height * (1f - compact)).toInt()
        val controlsHeight = if (floating) input.height else if (side) maxOf(tools.height, input.height) else toolStrip + input.height
        val viewportHeight = (height - controlsHeight).coerceAtLeast(0)
        val textWidth = (width - 56.dp.roundToPx() * (if (floating) 1f else compact)).toInt().coerceAtLeast(0)
        val viewport = children[1].measure(slot(textWidth, viewportHeight).copy(minHeight = viewportHeight))
        layout(width, height) {
            viewport.placeRelative(0, if (side || floating) 0 else toolStrip)
            tools.placeRelative(
                if (compactTools) width - tools.width + (112.dp.roundToPx() * (1f - compact)).toInt() else if (floating) width - tools.width else 0,
                if (compactTools) toolStrip + ((viewportHeight - tools.height) / 2).coerceAtLeast(0) else if (floating) ((viewportHeight - tools.height) / 2).coerceAtLeast(0) else if (side) height - controlsHeight + (controlsHeight - tools.height) / 2 else -(tools.height * compact).toInt()
            )
            input.placeRelative(if (side) toolsWidth else 0, height - input.height)
        }
    }
    }
}

@Composable private fun FloatingReadingTools(panelWidth: androidx.compose.ui.unit.Dp, toolbar: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val reveal = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { reveal.targetState = expanded }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 3.dp) {
        FeedbackIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Tune, "悬浮阅读工具") }
    }
    if (reveal.currentState || reveal.targetState) Popup(alignment = Alignment.TopEnd, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = false)) {
        AnimatedVisibility(visibleState = reveal, enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut()) {
            Surface(Modifier.width(panelWidth), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("阅读工具", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        FeedbackIconButton(onClick = { expanded = false }) { Icon(Icons.Outlined.Close, "收起悬浮工具") }
                    }
                    CompositionLocalProvider(LocalFloatingReadingTools provides true) { toolbar() }
                }
            }
        }
    }
}
