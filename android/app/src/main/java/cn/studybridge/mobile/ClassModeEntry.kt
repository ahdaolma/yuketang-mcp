package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val LocalClassManager = staticCompositionLocalOf<(() -> Unit)?> { null }
fun UiState.hasClassSession() = classUi.running || data.classRun?.let { !it.ended || it.pending.isNotEmpty() } == true
fun showClassControl(ui: UiState, serviceRunning: Boolean) = serviceRunning && ui.classUi.running && ui.data.session.isNotBlank() && ui.data.classRun?.let { !it.ended || it.pending.isNotEmpty() } == true

@Composable fun ClassManagerButton() {
    LocalClassManager.current?.let { open ->
        FeedbackIconButton(onClick = open) { Icon(Icons.Outlined.AutoStories, "上课管理", Modifier.size(22.dp)) }
    }
}

@Composable fun ClassManagerMenuItem(onSelected: () -> Unit) {
    LocalClassManager.current?.let { open ->
        FeedbackDropdownMenuItem(text = { Text("上课管理") }, onClick = { onSelected(); open() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ClassManagerHost(ui: UiState, vm: StudyViewModel, content: @Composable () -> Unit) {
    var opened by rememberSaveable { mutableStateOf(false) }
    val available = ui.data.session.isNotBlank() || ui.hasClassSession()
    LaunchedEffect(available) { if (!available) opened = false }
    CompositionLocalProvider(LocalClassManager provides if (available) ({ opened = true }) else null, content = content)
    if (opened && available) StudyModalBottomSheet(onDismissRequest = { opened = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上课管理", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                FeedbackIconButton(onClick = { opened = false }) { Icon(Icons.Default.Close, "关闭上课管理") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClassModePanel(ui, vm) { opened = false }
                ClassFollowSettings(ui, vm)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ClassModePanel(ui: UiState, vm: StudyViewModel, onClose: () -> Unit = {}) {
    val classButtonColors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    val run = ui.data.classRun
    val resumable = run != null && (!run.ended || run.pending.isNotEmpty())
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("上课控制面板")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.AutoStories, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("上课模式", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) { Text(when { ui.classUi.running && !ui.classUi.paused -> if (run?.ended == true) "整理中" else if (ui.classUi.connected) "跟随中" else "连接中"; resumable -> "已暂停"; else -> "未开始" }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
            }
            Text(if (resumable) run!!.lesson.title else "老师翻到新一页，笔记就跟着补充。", style = if (resumable) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (resumable) {
                Text("已整理 ${run!!.completed.size} 页 · 待处理 ${run.pending.size} 页", style = MaterialTheme.typography.labelLarge)
                Text(ui.classUi.status.ifBlank { "本节记录已保留，点击继续恢复。" }, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackFilledTonalButton(colors = classButtonColors, onClick = { if (ui.classUi.running && !ui.classUi.paused) vm.pauseClass() else vm.resumeClass() }, enabled = !ui.busy && !ui.chatgptLoginActive) { Text(if (ui.classUi.running && !ui.classUi.paused) "暂停" else "继续") }
                    FeedbackTextButton(onClick = { vm.tab(2); onClose() }) { Text("查看笔记") }
                    FeedbackTextButton(onClick = { vm.endClass(); onClose() }, colors = ButtonDefaults.textButtonColors(), enabled = !ui.busy) { Text("结束") }
                }
            } else {
                FeedbackFilledTonalButton(colors = classButtonColors, onClick = vm::discoverClasses, enabled = !ui.locked && ui.data.session.isNotBlank()) { Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (ui.data.session.isBlank()) "登录后开启上课模式" else "查找正在上的课") }
                if (ui.liveLessons.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = expandedListHeight(240.dp)).verticalScroll(rememberScrollState()).testTag("课堂选择列表")) {
                    ui.liveLessons.forEach { lesson -> StudyChoiceRow(onClick = { vm.startClass(lesson); onClose() }, enabled = !ui.locked) { Text("进入：${lesson.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                }
            }
            Text("进入会登记入课 · 离开 App 后继续整理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
