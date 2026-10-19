package cn.studybridge.mobile

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.material3.Button as NativeButton
import androidx.compose.material3.OutlinedButton as NativeOutlinedButton
import androidx.compose.material3.TextButton as NativeTextButton
import androidx.compose.material3.FilledTonalButton as NativeFilledTonalButton
import androidx.compose.material3.IconButton as NativeIconButton
import androidx.compose.material3.FilledIconButton as NativeFilledIconButton
import androidx.compose.material3.FilterChip as NativeFilterChip
import androidx.compose.material3.Switch as NativeSwitch
import androidx.compose.material3.Card as NativeCard
import androidx.compose.material3.OutlinedCard as NativeOutlinedCard
import androidx.compose.material3.DropdownMenuItem as NativeDropdownMenuItem
import androidx.compose.material3.NavigationBarItem as NativeNavigationBarItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

data class ControlFeedback(val explain: (String) -> Unit, val unavailable: String)
val LocalControlFeedback = staticCompositionLocalOf { ControlFeedback({}, "此操作暂时不可用，请先准备所需资料。") }

@Composable private fun feedbackClick(action: () -> Unit): () -> Unit {
    val view = LocalView.current
    val current by rememberUpdatedState(action)
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); current() } }
}

// Observe, never consume, the touch stream. Native Material ripple and click handling remain active.
// Moving beyond touch slop cancels the visual press and never activates a disabled action.
private fun Modifier.pressFeedback(enabled: Boolean, unavailableReason: String? = null): Modifier = composed {
    val feedback = LocalControlFeedback.current
    val reason = unavailableReason ?: feedback.unavailable
    val slop = LocalViewConfiguration.current.touchSlop
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "control-press")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics { if (!enabled) stateDescription = reason }
        .pointerInput(enabled, reason, slop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var tap = true
                pressed = true
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.find { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > slop || event.changes.count { it.pressed } > 1) { tap = false; pressed = false }
                        if (!change.pressed) {
                            if (tap && !enabled) feedback.explain(reason)
                            break
                        }
                    }
                } finally { pressed = false }
            }
        }
}

@Composable fun FeedbackButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.buttonColors(), shape: Shape = RoundedCornerShape(12.dp), unavailableReason: String? = null, content: @Composable RowScope.() -> Unit) {
    NativeButton(feedbackClick(onClick), modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, colors = colors, shape = shape, content = content)
}
@Composable fun FeedbackOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), unavailableReason: String? = null, content: @Composable RowScope.() -> Unit) {
    NativeOutlinedButton(feedbackClick(onClick), modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, colors = colors, shape = RoundedCornerShape(12.dp), content = content)
}
@Composable fun FeedbackTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.textButtonColors(), unavailableReason: String? = null, content: @Composable RowScope.() -> Unit) {
    NativeTextButton(feedbackClick(onClick), modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, colors = colors, shape = RoundedCornerShape(12.dp), content = content)
}
@Composable fun FeedbackFilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), unavailableReason: String? = null, content: @Composable RowScope.() -> Unit) {
    NativeFilledTonalButton(feedbackClick(onClick), modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, colors = colors, shape = RoundedCornerShape(12.dp), content = content)
}
@Composable fun FeedbackIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, unavailableReason: String? = null, content: @Composable () -> Unit) {
    NativeIconButton(feedbackClick(onClick), modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, content = content)
}
@Composable fun FeedbackFilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, unavailableReason: String? = null, content: @Composable () -> Unit) {
    NativeFilledIconButton(feedbackClick(onClick), modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, content = content)
}
@Composable fun FeedbackFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    NativeFilterChip(selected, feedbackClick(onClick), label, modifier.pressFeedback(enabled), enabled = enabled)
}
@Composable fun FeedbackSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, unavailableReason: String? = null) {
    val view = LocalView.current
    NativeSwitch(checked, { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onCheckedChange(it) }, modifier.pressFeedback(enabled, unavailableReason), enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh, disabledUncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
    ))
}
@Composable fun FeedbackDropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier, leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null, enabled: Boolean = true) {
    NativeDropdownMenuItem(text, feedbackClick(onClick), modifier.pressFeedback(enabled), leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled)
}
@Composable fun RowScope.FeedbackNavigationBarItem(selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: @Composable (() -> Unit)? = null) {
    NativeNavigationBarItem(selected, feedbackClick(onClick), icon, modifier.pressFeedback(enabled), enabled = enabled, label = label, colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.onSurface,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant, indicatorColor = Color.Transparent
    ))
}
@Composable fun FeedbackCard(modifier: Modifier = Modifier, shape: Shape = CardDefaults.shape, colors: CardColors = CardDefaults.cardColors(), content: @Composable ColumnScope.() -> Unit) {
    NativeCard(modifier, shape = shape, colors = colors, content = content)
}
@Composable fun FeedbackCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = CardDefaults.shape, colors: CardColors = CardDefaults.cardColors(), border: androidx.compose.foundation.BorderStroke? = null, content: @Composable ColumnScope.() -> Unit) {
    NativeCard(feedbackClick(onClick), modifier.pressFeedback(enabled), enabled = enabled, shape = shape, colors = colors, border = border, content = content)
}
@Composable fun FeedbackOutlinedCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    NativeOutlinedCard(feedbackClick(onClick), modifier.pressFeedback(enabled), enabled = enabled, content = content)
}
