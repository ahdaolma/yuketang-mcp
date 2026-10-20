package cn.studybridge.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

data class NotePagePart(val title: String, val start: Int, val end: Int)
fun notePageParts(text: String): List<NotePagePart> {
    val pages = readingHeadings(text).filter { (position, title) -> text.startsWith("## ", position) && Regex("第\\s*\\d+\\s*页").containsMatchIn(title) }
    if (pages.isNotEmpty()) return pages.mapIndexed { index, page -> NotePagePart(page.second, page.first, pages.getOrNull(index + 1)?.first ?: text.length) }
    return noteReadingSegments(text).mapIndexed { index, segment ->
        NotePagePart("阅读段 ${index + 1} · ${notePreview(text.substring(segment.start, minOf(segment.end, segment.start + 160))).take(36)}", segment.start, segment.end)
    }
}
fun removeNotePages(text: String, selected: Set<Int>): String {
    val pages = notePageParts(text)
    require(selected.isNotEmpty() && selected.all { it in pages.indices }) { "请选择仍然存在的页面。" }
    val result = StringBuilder(text)
    selected.sortedDescending().forEach { result.delete(pages[it].start, pages[it].end) }
    return result.toString()
}

@Composable fun NotePageDeletion(note: Note, vm: StudyViewModel, enabled: Boolean, onDismiss: () -> Unit) {
    // Bind every range and selection to the original content. A concurrent
    // update must never delete offsets from a different version of the note.
    val snapshot = remember(note.id) { note.content }
    val pages = remember(snapshot) { notePageParts(snapshot) }
    var selected by remember(note.id) { mutableStateOf(setOf<Int>()) }
    var confirming by remember(note.id) { mutableStateOf(false) }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("确认删除所选内容？") }, text = {
        Text("将删除“${note.title}”中的：\n${selected.sorted().joinToString("\n") { pages[it].title }}\n\n这些页的正文和图片引用会一起删除，其余内容保留。此操作无法撤销。", Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
    }, confirmButton = { FeedbackTextButton(onClick = { if (vm.deleteNotePages(note.id, snapshot, selected)) onDismiss() }, enabled = enabled && selected.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认删除所选页") } }, dismissButton = { FeedbackTextButton(onClick = { confirming = false }) { Text("返回选择") } })
    else AlertDialog(onDismissRequest = onDismiss, title = { Text("选择要删除的页") }, text = {
        Column(Modifier.heightIn(max = 360.dp)) {
            Text("按课件页标题识别；没有页码的笔记按阅读段选择。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(pages) { index, page -> Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { selected = if (index in selected) selected - index else selected + index }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(index in selected, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(page.title, style = MaterialTheme.typography.titleSmall)
                        Text(notePreview(snapshot.substring(page.start, minOf(page.end, page.start + 220))), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                } }
            }
        }
    }, confirmButton = { FeedbackTextButton(onClick = { confirming = true }, enabled = enabled && selected.isNotEmpty()) { Text("删除所选 ${selected.size} 页…") } }, dismissButton = { FeedbackTextButton(onClick = onDismiss) { Text("取消") } })
}
