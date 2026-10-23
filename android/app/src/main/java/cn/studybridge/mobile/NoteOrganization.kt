package cn.studybridge.mobile

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// The same explicit entry is available through long press, the overflow button,
// and accessibility. A destructive action always opens a separate confirmation.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun NoteListCard(note: Note, onOpen: () -> Unit, onActions: () -> Unit, selectionMode: Boolean = false, selected: Boolean = false, onSelect: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (selectionMode && selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().testTag("笔记卡片 ${note.id}").semantics { if (selectionMode) this.selected = selected }.combinedClickable(onClick = if (selectionMode) onSelect else onOpen, onLongClickLabel = "笔记操作", onLongClick = if (selectionMode) onSelect else onActions).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) { Checkbox(selected, null); Spacer(Modifier.width(8.dp)) }
                if (note.pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(note.title, Modifier.weight(1f).padding(start = if (note.pinned) 6.dp else 0.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!selectionMode) FeedbackIconButton(onClick = onActions, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "笔记操作 ${note.title}") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable fun NoteListActions(ui: UiState, vm: StudyViewModel, id: String?, initialAction: String = "options", onDismiss: () -> Unit) {
    val note = ui.data.notes.find { it.id == id }
    var action by rememberSaveable(id) { mutableStateOf(initialAction) }
    var title by rememberSaveable(id) { mutableStateOf(note?.title ?: "") }
    LaunchedEffect(id, note) { if (id != null && note == null) onDismiss() }
    if (note == null) return
    val writable = ui.noteEditable(note.id)
    val removable = !ui.locked && ui.data.noteWritable(note.id)
    when (action) {
        "options" -> AlertDialog(onDismissRequest = onDismiss, title = { Text(note.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = expandedListHeight(340.dp)).verticalScroll(rememberScrollState())) {
                FeedbackDropdownMenuItem(text = { Text("编辑笔记") }, enabled = writable, onClick = { vm.noteReader.open(note); vm.noteReader.beginEditing(note); onDismiss() })
                FeedbackDropdownMenuItem(text = { Text("重命名笔记") }, enabled = writable, onClick = { action = "rename" })
                FeedbackDropdownMenuItem(text = { Text(if (note.pinned) "取消置顶" else "置顶笔记") }, enabled = !ui.locked, onClick = { vm.toggleNotePin(note.id); onDismiss() })
                FeedbackDropdownMenuItem(text = { Text("移动到分类") }, enabled = !ui.locked, onClick = { action = "move" })
                HorizontalDivider()
                FeedbackDropdownMenuItem(text = { Text("按页删除内容…") }, enabled = removable, onClick = { action = "pages" })
                FeedbackDropdownMenuItem(text = { Text("删除笔记…", color = MaterialTheme.colorScheme.error) }, enabled = removable, onClick = { action = "delete" })
            }
        }, confirmButton = { FeedbackTextButton(onClick = onDismiss) { Text("关闭") } })
        "pages" -> NotePageDeletion(note, vm, removable, onDismiss)
        "rename" -> AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名笔记") }, text = {
            OutlinedTextField(title, { title = it.take(120) }, label = { Text("笔记标题") }, singleLine = true)
        }, confirmButton = { FeedbackTextButton(onClick = { if (vm.renameNote(note.id, title)) onDismiss() }, enabled = writable && title.isNotBlank()) { Text("保存标题") } }, dismissButton = { FeedbackTextButton(onClick = onDismiss) { Text("取消") } })
        "move" -> NotebookPicker(ui.data, note.folderId, "移动到分类", allowAll = false, onClose = onDismiss) { destination ->
            if (destination != null && vm.moveNote(note.id, destination)) onDismiss()
        }
        "delete" -> AlertDialog(onDismissRequest = onDismiss, title = { Text("删除这份笔记？") }, text = { Text("“${note.title}”将移到回收站，可以恢复。") },
            confirmButton = { FeedbackTextButton(onClick = { if (vm.deleteNote(note.id)) onDismiss() }, enabled = removable, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除笔记") } },
            dismissButton = { FeedbackTextButton(onClick = onDismiss) { Text("保留笔记") } })
    }
}
