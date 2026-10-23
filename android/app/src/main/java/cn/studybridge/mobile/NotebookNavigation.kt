package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NotebookPanel(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    StudyModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = expandedPanelHeight()).testTag("笔记本面板")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FeedbackIconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭$title") }
            }
            content()
        }
    }
}

@Composable private fun NotebookTree(data: Saved, selected: String?, query: String, expanded: Set<String>, onExpand: (String) -> Unit, allowAll: Boolean, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val roots = data.noteFolders.filter { it.parentId.isEmpty() }
    val visible = roots.filter { root -> root.name.contains(query, true) || data.noteFolders.any { it.parentId == root.id && it.name.contains(query, true) } }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (query.isBlank()) {
            if (allowAll) item { StudyChoiceRow({ onSelect(null) }, selected = selected == null) {
                Icon(Icons.Outlined.LibraryBooks, null); Spacer(Modifier.width(10.dp)); Text("全部笔记", Modifier.weight(1f)); Text("${data.notes.size}", style = MaterialTheme.typography.labelMedium)
            } }
            item { StudyChoiceRow({ onSelect("") }, selected = selected == "") {
                Icon(Icons.Outlined.Inbox, null); Spacer(Modifier.width(10.dp)); Text("未整理", Modifier.weight(1f)); Text("${data.folderNoteCount("")}", style = MaterialTheme.typography.labelMedium)
            } }
        }
        visible.forEach { root ->
            val children = data.noteFolders.filter { it.parentId == root.id }
            val open = root.id in expanded || query.isNotBlank() || children.any { it.id == selected }
            item(key = "folder-${root.id}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StudyChoiceRow({ onSelect(root.id) }, Modifier.weight(1f).testTag("选择分类 ${root.id}"), selected = selected == root.id) {
                        Icon(Icons.Outlined.MenuBook, null); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { Text(root.name, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${data.folderNoteCount(root.id)} 份 · ${children.size} 章", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (children.isNotEmpty()) FeedbackIconButton(onClick = { onExpand(root.id) }) { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "${if (open) "收起" else "展开"}笔记本 ${root.name}") }
                }
            }
            if (open) items(children.filter { query.isBlank() || root.name.contains(query, true) || it.name.contains(query, true) }, key = { "folder-${it.id}" }) { section ->
                StudyChoiceRow({ onSelect(section.id) }, Modifier.padding(start = 20.dp).testTag("选择分类 ${section.id}"), selected = selected == section.id) {
                    Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text(section.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${data.folderNoteCount(section.id)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (visible.isEmpty()) item { Text(if (query.isBlank()) "新建课程笔记本，再按章节或专题整理。" else "没有匹配的笔记本或章节。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun NotebookPicker(data: Saved, selected: String?, title: String = "选择笔记本", allowAll: Boolean = true, onClose: () -> Unit, onSelect: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    NotebookPanel(title, onClose) {
        OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true, label = { Text("搜索笔记本或章节") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        NotebookTree(data, selected, query, expanded, { expanded = if (it in expanded) expanded - it else expanded + it }, allowAll, onSelect, Modifier.weight(1f, fill = false).fillMaxWidth().testTag("笔记本选择列表"))
    }
}

@Composable fun NoteFolderBar(ui: UiState, vm: StudyViewModel, selected: String?, onSelect: (String?) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    var manager by remember { mutableStateOf(false) }
    LaunchedEffect(ui.data.noteFolders, selected) { if (selected != null && selected.isNotEmpty() && ui.data.noteFolders.none { it.id == selected }) onSelect("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedbackCard(onClick = { picker = true }, modifier = Modifier.weight(1f).testTag("当前笔记本")) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.MenuBook, null)
                Column(Modifier.weight(1f)) {
                    Text(if (selected == null) "全部笔记" else ui.data.folderPath(selected), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (selected == null) "${ui.data.noteFolders.count { it.parentId.isEmpty() }} 个笔记本 · ${ui.data.notes.size} 份笔记" else "${ui.data.folderNoteCount(selected)} 份笔记", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.UnfoldMore, "选择笔记本")
            }
        }
        FeedbackIconButton(onClick = { manager = true }) { Icon(Icons.Outlined.CreateNewFolder, "管理笔记分类") }
    }
    if (picker) NotebookPicker(ui.data, selected, onClose = { picker = false }) { onSelect(it); picker = false }
    if (manager) NotebookManager(ui, vm) { manager = false }
}

@Composable fun NotebookSidebar(ui: UiState, vm: StudyViewModel, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var manager by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    Column(modifier.testTag("平板笔记本目录")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("笔记本", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            FeedbackIconButton(onClick = { manager = true }) { Icon(Icons.Outlined.CreateNewFolder, "管理笔记分类") }
        }
        OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, label = { Text("查找笔记本") })
        NotebookTree(ui.data, selected, query, expanded, { expanded = if (it in expanded) expanded - it else expanded + it }, true, onSelect, Modifier.weight(1f).fillMaxWidth())
    }
    if (manager) NotebookManager(ui, vm) { manager = false }
}

@Composable fun NotebookManager(ui: UiState, vm: StudyViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var parent by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    fun edit(folder: NoteFolder?) { editId = folder?.id; parent = folder?.parentId.orEmpty(); name = folder?.name.orEmpty(); editing = true }
    val target = ui.data.noteFolders.find { it.id == deleting }
    if (!editing && target == null) NotebookPanel("笔记本管理", onClose) {
        FeedbackTextButton(onClick = { edit(null) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("新建笔记本") }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.data.noteFolders.isEmpty()) item { Text("一个课程建一个笔记本，里面可继续添加章节或专题。", style = MaterialTheme.typography.bodyMedium) }
            ui.data.noteFolders.filter { it.parentId.isEmpty() }.forEach { root ->
                item(key = root.id) { NotebookManagementRow(root, ui, onRename = { edit(root) }, onDelete = { deleting = root.id }, onAddSection = { edit(null); parent = root.id }) }
                items(ui.data.noteFolders.filter { it.parentId == root.id }, key = { it.id }) { section ->
                    Box(Modifier.padding(start = 18.dp)) { NotebookManagementRow(section, ui, { edit(section) }, { deleting = section.id }) }
                }
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(if (editId != null) "重命名" else if (parent.isEmpty()) "新建笔记本" else "新建章节") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (parent.isNotEmpty()) Text("所属笔记本：${ui.data.folderPath(parent)}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it.take(30) }, label = { Text("分类名称") }, singleLine = true)
        }
    }, confirmButton = { FeedbackTextButton(onClick = { if (vm.saveNoteFolder(editId, name, parent)) editing = false }, enabled = !ui.locked && name.isNotBlank()) { Text("保存分类") } }, dismissButton = { FeedbackTextButton(onClick = { editing = false }) { Text("取消") } })
    if (target != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text(if (target.parentId.isEmpty()) "删除笔记本？" else "删除章节？") }, text = {
        val destination = if (target.parentId.isEmpty()) "未整理" else ui.data.folderPath(target.parentId)
        Text("删除“${target.name}”${if (target.parentId.isEmpty()) "及其章节" else ""}，${ui.data.folderNoteCount(target.id)} 份笔记会移到$destination。笔记正文、图片和草稿均保留。")
    }, confirmButton = { FeedbackTextButton(onClick = { if (vm.deleteNoteFolder(target.id)) deleting = null }, enabled = !ui.locked) { Text("只删除分类") } }, dismissButton = { FeedbackTextButton(onClick = { deleting = null }) { Text("保留分类") } })
}

@Composable private fun NotebookManagementRow(folder: NoteFolder, ui: UiState, onRename: () -> Unit, onDelete: () -> Unit, onAddSection: (() -> Unit)? = null) {
    StudyPanel {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${ui.data.folderNoteCount(folder.id)} 份笔记", style = MaterialTheme.typography.bodySmall) }
            if (onAddSection != null) FeedbackIconButton(onClick = onAddSection, enabled = !ui.locked) { Icon(Icons.Outlined.CreateNewFolder, "新建章节 ${folder.name}") }
            FeedbackIconButton(onClick = onRename, enabled = !ui.locked) { Icon(Icons.Outlined.Edit, "重命名分类 ${folder.name}") }
            FeedbackIconButton(onClick = onDelete, enabled = !ui.locked) { Icon(Icons.Outlined.DeleteOutline, "删除分类 ${folder.name}") }
        }
    }
}
