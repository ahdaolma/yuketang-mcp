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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class NoteFolder(val id: String = newId(), val name: String)

fun Saved.withNoteFolder(id: String?, name: String): Saved {
    val value = name.trim()
    require(value.isNotEmpty() && value.length <= 30) { "分类名称请填写 1–30 个字。" }
    require(value !in setOf("全部", "未分类")) { "请换一个分类名称。" }
    require(noteFolders.none { it.id != id && it.name.equals(value, ignoreCase = true) }) { "已有同名分类。" }
    require(id == null || noteFolders.any { it.id == id }) { "分类已不存在。" }
    return copy(noteFolders = if (id == null) noteFolders + NoteFolder(name = value) else noteFolders.map { if (it.id == id) it.copy(name = value) else it })
}

fun Saved.withoutNoteFolder(id: String) = copy(noteFolders = noteFolders.filterNot { it.id == id }, notes = notes.map { if (it.folderId == id) it.copy(folderId = "") else it })
fun Saved.effectiveFolder(note: Note) = note.folderId.takeIf { id -> noteFolders.any { it.id == id } } ?: ""
fun Saved.folderName(note: Note) = noteFolders.find { it.id == note.folderId }?.name ?: "未分类"

@Composable fun NoteFolderBar(ui: UiState, vm: StudyViewModel, selected: String?, onSelect: (String?) -> Unit) {
    var manager by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.data.noteFolders, selected) { if (selected != null && selected.isNotEmpty() && ui.data.noteFolders.none { it.id == selected }) onSelect("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FeedbackFilterChip(selected == null, { onSelect(null) }, label = { Text("全部 ${ui.data.notes.size}") }) }
            item { FeedbackFilterChip(selected == "", { onSelect("") }, label = { Text("未分类 ${ui.data.notes.count { ui.data.effectiveFolder(it).isEmpty() }}") }) }
            items(ui.data.noteFolders, key = { it.id }) { folder ->
                FeedbackFilterChip(selected == folder.id, { onSelect(folder.id) }, label = { Text("${folder.name} ${ui.data.notes.count { it.folderId == folder.id }}", maxLines = 1) })
            }
        }
        FeedbackIconButton(onClick = { manager = true }) { Icon(Icons.Outlined.CreateNewFolder, "管理笔记分类") }
    }
    if (manager && !editing && deleting == null) AlertDialog(onDismissRequest = { manager = false }, title = { Text("笔记分类") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = expandedListHeight(320.dp)).verticalScroll(rememberScrollState())) {
            if (ui.data.noteFolders.isEmpty()) Text("按课程、主题或复习计划建立分类。长按笔记可移动分类。", style = MaterialTheme.typography.bodyMedium)
            ui.data.noteFolders.forEach { folder -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(folder.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                FeedbackIconButton(onClick = { editId = folder.id; name = folder.name; editing = true }, enabled = !ui.locked) { Icon(Icons.Outlined.Edit, "重命名分类 ${folder.name}") }
                FeedbackIconButton(onClick = { deleting = folder.id }, enabled = !ui.locked) { Icon(Icons.Outlined.DeleteOutline, "删除分类 ${folder.name}") }
            } }
        }
    }, confirmButton = { FeedbackTextButton(onClick = { editId = null; name = ""; editing = true }, enabled = !ui.locked) { Text("新建分类") } }, dismissButton = { FeedbackTextButton(onClick = { manager = false }) { Text("关闭") } })
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(if (editId == null) "新建分类" else "重命名分类") }, text = {
        OutlinedTextField(name, { name = it.take(30) }, label = { Text("分类名称") }, singleLine = true)
    }, confirmButton = { FeedbackTextButton(onClick = { if (vm.saveNoteFolder(editId, name)) editing = false }, enabled = !ui.locked && name.isNotBlank()) { Text("保存分类") } }, dismissButton = { FeedbackTextButton(onClick = { editing = false }) { Text("取消") } })
    val folder = ui.data.noteFolders.find { it.id == deleting }
    if (folder != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除分类？") }, text = {
        Text("删除“${folder.name}”后，其中的 ${ui.data.notes.count { it.folderId == folder.id }} 份笔记会回到未分类。笔记内容会完整保留。")
    }, confirmButton = { FeedbackTextButton(onClick = { if (vm.deleteNoteFolder(folder.id)) deleting = null }, enabled = !ui.locked) { Text("只删除分类") } }, dismissButton = { FeedbackTextButton(onClick = { deleting = null }) { Text("保留分类") } })
}

// The same explicit entry is available through long press, the overflow button,
// and accessibility. A destructive action always opens a separate confirmation.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun NoteListCard(note: Note, onOpen: () -> Unit, onActions: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().testTag("笔记卡片 ${note.id}").combinedClickable(onClick = onOpen, onLongClickLabel = "笔记操作", onLongClick = onActions).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(note.title, Modifier.weight(1f).padding(start = if (note.pinned) 6.dp else 0.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FeedbackIconButton(onClick = onActions, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "笔记操作 ${note.title}") }
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
        "move" -> AlertDialog(onDismissRequest = onDismiss, title = { Text("移动到分类") }, text = {
            Column(Modifier.heightIn(max = expandedListHeight(320.dp)).verticalScroll(rememberScrollState())) {
                Text("当前：${ui.data.folderName(note)}", style = MaterialTheme.typography.bodySmall)
                FeedbackDropdownMenuItem(text = { Text("未分类") }, enabled = !ui.locked, onClick = { if (vm.moveNote(note.id, "")) onDismiss() })
                ui.data.noteFolders.forEach { folder -> FeedbackDropdownMenuItem(text = { Text(folder.name) }, enabled = !ui.locked, onClick = { if (vm.moveNote(note.id, folder.id)) onDismiss() }) }
                if (ui.data.noteFolders.isEmpty()) Text("可在笔记列表右侧的文件夹按钮中新建分类。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { FeedbackTextButton(onClick = onDismiss) { Text("取消") } })
        "delete" -> AlertDialog(onDismissRequest = onDismiss, title = { Text("删除这份笔记？") }, text = { Text("“${note.title}”将移到回收站，可以恢复。") },
            confirmButton = { FeedbackTextButton(onClick = { if (vm.deleteNote(note.id)) onDismiss() }, enabled = removable, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除笔记") } },
            dismissButton = { FeedbackTextButton(onClick = onDismiss) { Text("保留笔记") } })
    }
}
