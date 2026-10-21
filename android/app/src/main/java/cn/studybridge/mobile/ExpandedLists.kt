package cn.studybridge.mobile

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu as NativeDropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

// Shared by every expandable choice/action list. Relative limits also work on
// short landscape windows and with large system fonts; contents scroll inside.
@Composable fun expandedListHeight(maximum: Dp = 320.dp): Dp =
    minOf(maximum, (LocalConfiguration.current.screenHeightDp * .45f).dp)

@Composable fun expandedPanelHeight(): Dp =
    minOf(440.dp, (LocalConfiguration.current.screenHeightDp * .60f).dp)

@Composable fun StudyDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val width = minOf(336.dp, (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(1).dp)
    NativeDropdownMenu(expanded, onDismissRequest,
        modifier.heightIn(max = expandedListHeight()).widthIn(max = width).testTag("展开菜单容器"),
        properties = properties, content = content)
}
