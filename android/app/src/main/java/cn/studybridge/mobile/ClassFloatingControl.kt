package cn.studybridge.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun ClassFloatingControl(paused: Boolean, sessionId: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val latestToggle by rememberUpdatedState(onToggle)
    val view = LocalViewConfiguration.current
    var fractionX by rememberSaveable(sessionId) { mutableFloatStateOf(0.94f) }
    var fractionY by rememberSaveable(sessionId) { mutableFloatStateOf(0.36f) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val size = with(density) { 56.dp.toPx() }
        val left = with(density) { 8.dp.toPx() }
        val top = WindowInsets.displayCutout.getTop(density) + with(density) { 28.dp.toPx() }
        val ime = WindowInsets.ime.getBottom(density)
        val reserve = with(density) { (if (maxWidth > maxHeight) 32.dp else 104.dp).toPx() }
        val travelX = (constraints.maxWidth - size - left * 2f).coerceAtLeast(0f)
        val travelY = (constraints.maxHeight - ime - reserve - top - size).coerceAtLeast(0f)
        Surface(Modifier.offset { IntOffset((left + travelX * fractionX).roundToInt(), (top + travelY * fractionY).roundToInt()) }
            .size(56.dp).testTag("上课悬浮圆钮")
            .semantics { role = Role.Button; contentDescription = if (paused) "继续跟课" else "暂停跟课"; stateDescription = if (paused) "已暂停，可拖动" else "正在跟课，可拖动"; onClick { latestToggle(); true } }
            .pointerInput(sessionId, travelX, travelY, view.touchSlop, view.longPressTimeoutMillis) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var dragging = false
                    var multiplePointers = false
                    var finished = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) multiplePointers = true
                        val change = event.changes.find { it.id == down.id } ?: break
                        if (!dragging && (change.position - down.position).getDistance() > view.touchSlop) dragging = true
                        if (dragging && change.pressed) {
                            val delta = change.positionChange()
                            if (travelX > 0) fractionX = (fractionX + delta.x / travelX).coerceIn(0f, 1f)
                            if (travelY > 0) fractionY = (fractionY + delta.y / travelY).coerceIn(0f, 1f)
                        }
                        change.consume()
                        if (!change.pressed) {
                            if (!dragging && !multiplePointers && change.uptimeMillis - down.uptimeMillis < view.longPressTimeoutMillis) latestToggle()
                            finished = true
                        }
                    } while (!finished)
                }
            }, shape = CircleShape, shadowElevation = 5.dp,
            color = if (paused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
        }
    }
}
