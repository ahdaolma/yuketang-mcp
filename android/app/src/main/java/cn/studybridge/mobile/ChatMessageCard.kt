package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ChatMessageCard(message: Message, identity: String, provider: String, streaming: Boolean, notes: List<Note> = emptyList(), onOpenNote: (String, Boolean) -> Unit = { _, _ -> }, editable: Boolean = true) {
    val user = message.role == "user"
    val reference = user && (message.text.length > 900 || message.text.startsWith("用户选择的笔记原文："))
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val text = message.text.removePrefix("用户选择的笔记原文：\n")
    Surface(
        color = when { reference -> MaterialTheme.colorScheme.surfaceContainerLow; user -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f); else -> Color.Transparent },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = if (user && !reference) 28.dp else 0.dp)
    ) {
        Column(Modifier.padding(horizontal = if (user) 14.dp else 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (reference && (message.source != null || message.text.startsWith("用户选择的笔记原文："))) "参考笔记" else if (user) "你" else if (message.kind == "note_result") "已保存笔记" else provider, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message.source?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            message.image?.let { DataImage(it, message.source ?: "课件") }
            if (reference && !expanded) Text(remember(text) { notePreview(text).take(600) }, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            else if (streaming) Text(text.ifBlank { "正在思考…" }, style = MaterialTheme.typography.bodyLarge)
            else Markdown(text.ifBlank { "正在思考…" })
            if (reference) FeedbackTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起原文" else "展开原文") }
            message.noteRefs.distinctBy { it.noteId }.forEach { ref ->
                ChatNoteCard(ref, notes.find { it.id == ref.noteId }, "$identity:note:${ref.noteId}", onOpenNote, editable)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChatNoteCard(ref: ChatNoteReference, note: Note?, identity: String, onOpen: (String, Boolean) -> Unit, editable: Boolean) {
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    val version by produceState<String?>(null, note) {
        value = null
        value = note?.let { withContext(Dispatchers.Default) { chatNoteHash(it) } }
    }
    val updated = note != null && version != null && version != ref.hash
    OutlinedCard(Modifier.fillMaxWidth().testTag(identity), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("笔记 · ${if (note == null) "当前不可用" else if (updated) "已保存新修改" else "已带入对话"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(note?.title ?: ref.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note == null) Text("这份笔记已删除或移入回收站。", style = MaterialTheme.typography.bodySmall)
            else {
                Text(readableTime(note.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded) {
                    Markdown(note.content.take(12000))
                    if (note.content.length > 12000) Text("笔记较长，可打开笔记查看全文。", style = MaterialTheme.typography.bodySmall)
                } else Text(notePreview(note.content), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (updated) Text("继续提问时会带入最新保存内容及修改摘要。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onOpen(note.id, false) }, enabled = editable) { Text("打开笔记") }
                    OutlinedButton(onClick = { onOpen(note.id, true) }, enabled = editable) { Text("编辑笔记") }
                    FeedbackTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起笔记" else "在对话中展开") }
                }
            }
        }
    }
}
