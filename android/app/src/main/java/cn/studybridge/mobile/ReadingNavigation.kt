package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable fun JumpDialog(title: String, labels: List<String>, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(120) }, label = { Text("输入序号或关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val choices = remember(labels, query) { labels.withIndex().filter { query.isBlank() || "${it.index + 1}" == query.trim() || it.value.contains(query.trim(), true) } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                if (choices.isEmpty()) item { Text("没有匹配的内容") }
                itemsIndexed(choices, key = { _, item -> item.index }) { _, item -> TextButton(onClick = { onSelect(item.index) }, modifier = Modifier.fillMaxWidth()) { Text("${item.index + 1}. ${item.value}", Modifier.fillMaxWidth()) } }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消跳转") } })
}

// Code blocks may contain lines resembling headings; they are not destinations.
fun readingHeadings(markdown: String): List<Pair<Int, String>> {
    val result = mutableListOf<Pair<Int, String>>()
    var offset = 0; var fence = ""
    for (line in markdown.lineSequence()) {
        val trimmed = line.trimStart()
        val marker = Regex("^(`{3,}|~{3,})(.*)$").matchEntire(trimmed)
        if (marker != null) {
            val m = marker.groupValues[1]
            if (fence.isEmpty()) fence = m else if (m.first() == fence.first() && m.length >= fence.length && marker.groupValues[2].isBlank()) fence = ""
        } else if (fence.isEmpty()) Regex("^#{1,3} +(.+)$").matchEntire(line)?.let { result.add(offset to it.groupValues[1].take(160)) }
        offset += line.length + 1
    }
    return result
}
