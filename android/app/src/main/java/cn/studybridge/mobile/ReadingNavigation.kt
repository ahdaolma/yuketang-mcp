package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun SlidePageControls(slides: List<Slide>, selected: Int, onSelected: (Int) -> Unit) {
    if (slides.isEmpty()) return
    var jump by remember { mutableStateOf(false) }
    if (jump) JumpDialog("跳转到课件页", slides.map { "第 ${it.page} 页 · ${notePreview(it.text).take(55)}" }, { jump = false }) { onSelected(slides[it].page); jump = false }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TextButton(onClick = { jump = true }) { Text("页码跳转") }
        TextButton(onClick = { onSelected(0) }, enabled = selected != 0) { Text(if (selected == 0) "共 ${slides.size} 页" else "查看全部 ${slides.size} 页") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JumpDialog(title: String, labels: List<String>, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    StudyModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("取消跳转") }
            }
            OutlinedTextField(query, { query = it.take(120) }, label = { Text("输入序号或关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val choices = remember(labels, query) { labels.withIndex().filter { query.isBlank() || "${it.index + 1}" == query.trim() || it.value.contains(query.trim(), true) } }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = expandedListHeight(360.dp)).testTag("跳转选择列表"), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (choices.isEmpty()) item { Text("没有匹配的内容") }
                itemsIndexed(choices, key = { _, item -> item.index }) { _, item -> StudyChoiceRow(onClick = { onSelect(item.index) }) { Text("${item.index + 1}. ${item.value}", maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) } }
            }
        }
    }
}

// Code blocks may contain lines resembling headings; they are not destinations.
fun readingHeadings(markdown: String): List<Pair<Int, String>> {
    val result = mutableListOf<Pair<Int, String>>()
    var fence = ""
    val fencePattern = Regex("^(`{3,}|~{3,})(.*)$")
    val headingPattern = Regex("^#{1,3} +(.+)$")
    for (range in noteSourceLines(markdown)) {
        val line = markdown.substring(range.start, range.contentEnd)
        val trimmed = line.trimStart()
        val marker = fencePattern.matchEntire(trimmed)
        if (marker != null) {
            val m = marker.groupValues[1]
            if (fence.isEmpty()) fence = m else if (m.first() == fence.first() && m.length >= fence.length && marker.groupValues[2].isBlank()) fence = ""
        } else if (fence.isEmpty()) headingPattern.matchEntire(line)?.let { result.add(range.start to it.groupValues[1].take(160)) }
    }
    return result
}
