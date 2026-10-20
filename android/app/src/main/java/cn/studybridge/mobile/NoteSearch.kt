package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NoteSearchHit(val segment: Int, val offset: Int, val snippet: String)
fun searchNoteContent(content: String, query: String, segments: List<NoteReadingSegment> = noteReadingSegments(content)): List<NoteSearchHit> {
    val word = query.trim(); if (word.isEmpty()) return emptyList()
    val result = mutableListOf<NoteSearchHit>()
    var offset = 0; var segment = 0
    while (result.size < 100) {
        val found = content.indexOf(word, offset, ignoreCase = true); if (found < 0) break
        while (segment < segments.lastIndex && found >= segments[segment].end) segment++
        result.add(NoteSearchHit(segment, found, content.substring(maxOf(0, found - 32), minOf(content.length, found + word.length + 70)).replace('\n', ' ')))
        offset = found + word.length
    }
    return result
}

@Composable fun NoteSearchDialog(note: Note, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var query by remember(note.id) { mutableStateOf("") }
    val results by produceState<List<NoteSearchHit>?>(emptyList(), note.content, query) {
        value = null
        value = withContext(Dispatchers.Default) { searchNoteContent(note.content, query) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("笔记内查找") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("查找完整正文") }, singleLine = true)
            Text(when { query.isBlank() -> "输入关键词，点击结果跳转到所在阅读分段。"; results == null -> "正在查找…"; results!!.isEmpty() -> "没有匹配的内容"; else -> "找到 ${results!!.size} 处${if (results!!.size == 100) "（最多显示 100 处）" else ""}" }, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(results.orEmpty(), key = { it.offset }) { hit ->
                    FeedbackTextButton(onClick = { onSelect(hit.segment) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text("第 ${hit.segment + 1} 段", fontWeight = FontWeight.SemiBold); Text(hit.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                    }
                }
            }
        }
    }, confirmButton = { FeedbackTextButton(onClick = onDismiss) { Text("关闭") } })
}
