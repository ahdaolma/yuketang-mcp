package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.studybridge.mobile.FeedbackCard as Card
import cn.studybridge.mobile.FeedbackTextButton as TextButton

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable fun CourseLibraryControls(order: CourseOrder, page: Int?, enabled: Boolean, onOrder: (CourseOrder) -> Unit, lesson: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("课件排序")) { Text("排列：${order.label}") }
        }
        val scope = if (lesson) "仅排列本节已开放课件；时间按接口实际提供字段显示。" else if (page != null) "仅当前第 ${page + 1} 批已加载记录；时间优先使用创建、发布、上课或记录时间。" else when (order) {
            CourseOrder.TEACHER -> "课程按老师归类；未提供的老师单独列出。"
            CourseOrder.NAME -> "全部已加载课程按名称排列。"
            else -> "课程接口未提供可排序时间，保留接口顺序；进入课程后可按课件时间排列。"
        }
        Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) StudyModalBottomSheet(onDismissRequest = { open = false }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("课件排列", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { open = false }) { Text("关闭排列") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = expandedListHeight(420.dp)).testTag("课件排列列表"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CourseOrder.entries) { choice ->
                Card(onClick = { onOrder(choice); open = false }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (choice == order) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                    Text((if (choice == order) "已选：" else "") + choice.label, Modifier.padding(16.dp))
                }
            }
        }
    }
}

// Rows live in the parent's single LazyColumn so a wide layout never nests scrolling lists.
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.courseLibraryItems(ui: UiState, vm: StudyViewModel, columns: Int) {
    if (ui.lesson != null) item(key = "lesson-context") { Text("${ui.lesson.title} · ${ui.presentations.size} 份课件", style = MaterialTheme.typography.titleSmall) }
    item(key = "library-order") { CourseLibraryControls(ui.courseOrder, ui.course?.takeIf { ui.lesson == null }?.let { ui.activityPage }, !ui.locked, vm::courseOrder, ui.lesson != null) }
    if (ui.course == null) {
        groupedCourses(ui.courses, ui.courseOrder).forEachIndexed { groupIndex, (teacher, courses) ->
            if (teacher.isNotBlank()) item(key = "teacher-$groupIndex") { StudySectionLabel(teacher) }
            items(courses.chunked(columns), key = { row -> "courses:${row.joinToString { it.id }}" }) { row ->
                CourseLibraryRow(columns, row) { c ->
                    Card(onClick = { vm.loadActivities(c) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("课程", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(c.title, style = MaterialTheme.typography.titleSmall)
                            Text("老师：${c.teacher.ifBlank { "未提供" }}", style = MaterialTheme.typography.bodySmall)
                            val detail = c.term.ifBlank { c.detail }
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("查看课堂与课件 →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    } else {
        val ordered = orderedPresentations(ui.presentations, ui.courseOrder)
        val groups = if (ui.courseOrder == CourseOrder.TEACHER) ordered.groupBy { it.teacher.ifBlank { "老师未提供" } }.toList() else listOf("" to ordered)
        groups.forEachIndexed { groupIndex, (teacher, presentations) ->
            if (teacher.isNotBlank()) item(key = "ppt-teacher-$groupIndex") { StudySectionLabel(teacher) }
            items(presentations.chunked(columns), key = { row -> "presentations:${row.joinToString { "${it.lessonId}:${it.id}" }}" }) { row ->
                CourseLibraryRow(columns, row) { p ->
                    Card(onClick = { vm.openPresentation(p) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (p.isLesson) "课堂记录" else "课件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(p.title, style = MaterialTheme.typography.titleSmall)
                            Text(presentationMetadata(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DeckPreview(ui, p, vm)
                            Text(if (p.isLesson) "查看本节课件 →" else "查看页面与 AI 讲解 →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (ordered.isEmpty()) item { Text(if (ui.lesson != null) "本节课未开放可读取的课件。" else "当前批次没有可读取的课件，可查看下一批。") }
        if (ui.lesson == null) item(key = "library-pages") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.loadActivities(ui.course, ui.activityPage - 1) }, enabled = !ui.locked && ui.activityPage > 0) { Text("上一批") }
                Text("第 ${ui.activityPage + 1} 批", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { vm.loadActivities(ui.course, ui.activityPage + 1) }, enabled = !ui.locked && ui.activityPage < 1000) { Text("下一批") }
            }
        }
    }
}

@Composable private fun <T> CourseLibraryRow(columns: Int, items: List<T>, content: @Composable (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
        repeat((columns - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

fun courseLibraryColumns(widthDp: Float, fontScale: Float): Int {
    val readableCardWidth = 320f * fontScale.coerceAtLeast(1f)
    return ((widthDp + 14f) / (readableCardWidth + 14f)).toInt().coerceIn(1, 3)
}
