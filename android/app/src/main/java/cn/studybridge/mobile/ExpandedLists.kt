package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudyDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    title: String = "选择操作",
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return
    // Choices share a predictable, wide sheet rather than a narrow popup
    // whose position and width depend on the triggering label.
    StudyModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(modifier.fillMaxWidth().heightIn(max = expandedListHeight()).testTag("展开菜单容器")) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FeedbackIconButton(onClick = onDismissRequest) { Icon(Icons.Outlined.Close, "关闭$title") }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), content = content)
        }
    }
}
