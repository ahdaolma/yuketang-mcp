package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private val notebookBackupExplanation = "备份只包含笔记、分类、标签、复习状态、关联、草稿、历史版本、回收站，以及引用的原图与图片批注。不会包含雨课堂登录凭据或模型密钥。恢复会合并为可查看的记录，保留现有笔记。请妥善保存这个未加密的文件。"

@Composable fun NotebookTools(ui: UiState, vm: StudyViewModel, folder: String?, tag: String?, review: String?, onFilter: (String?, String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("") }
    val files = rememberNotebookFileActions(ui, vm)
    val writable = !ui.locked && ui.ready && ui.storageError.isBlank() && !files.busy
    Box {
        FeedbackIconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreHoriz, "笔记工具") }
        DropdownMenu(menu, { menu = false }) {
            FeedbackDropdownMenuItem(text = { Text("筛选与排序") }, onClick = { menu = false; page = "filter" })
            FeedbackDropdownMenuItem(text = { Text("从模板新建") }, enabled = writable, onClick = { menu = false; page = "template" })
            FeedbackDropdownMenuItem(text = { Text("导入Markdown") }, enabled = writable, onClick = { menu = false; files.markdown() })
            FeedbackDropdownMenuItem(text = { Text("回收站") }, onClick = { menu = false; page = "trash" })
            FeedbackDropdownMenuItem(text = { Text("备份与恢复") }, onClick = { menu = false; page = "storage" })
        }
    }
    when (page) {
        "filter" -> NotebookDialog("筛选与排序", "笔记筛选面板", { page = "" }) {
            Text("排序", style = MaterialTheme.typography.titleSmall)
            NOTE_SORT_OPTIONS.forEach { (id, label) ->
                NotebookChoice(label, ui.data.noteSort == id, !ui.locked) { vm.updateNotebookState { it.copy(noteSort = id) } }
            }
            HorizontalDivider()
            Text("复习状态", style = MaterialTheme.typography.titleSmall)
            NotebookChoice("全部复习状态", review == null) { onFilter(tag, null) }
            NOTE_REVIEW_STATES.forEach { (id, label) -> NotebookChoice(label, review == id) { onFilter(tag, id) } }
            HorizontalDivider()
            Text("标签", style = MaterialTheme.typography.titleSmall)
            NotebookChoice("全部标签", tag == null) { onFilter(null, review) }
            ui.data.notes.flatMap { it.tags }.distinctBy { it.lowercase() }.sorted().forEach { value ->
                NotebookChoice("#$value", value.equals(tag, true)) { onFilter(value, review) }
            }
            if (ui.data.notes.none { it.tags.isNotEmpty() }) Text("可在笔记的“标签与复习”中添加标签。", style = MaterialTheme.typography.bodySmall)
            FeedbackTextButton(onClick = { onFilter(null, null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清除筛选") }
        }
        "template" -> NotebookDialog("从模板新建", "笔记模板面板", { page = "" }) {
            Text("在当前分类中新建，模板先进入编辑草稿，保存后成为正文。", style = MaterialTheme.typography.bodySmall)
            NOTE_TEMPLATES.forEach { template ->
                FeedbackOutlinedButton(onClick = {
                    if (!vm.ui.value.locked) {
                        val previous = vm.noteReader.id
                        vm.createManualNote(folder.orEmpty())
                        val id = vm.noteReader.id
                        if (id != null && id != previous && vm.renameNote(id, template.title)) {
                            vm.noteReader.draft = template.content
                            page = ""
                        }
                    }
                }, enabled = writable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(template.name) }
            }
        }
        "trash" -> NotebookTrash(ui, vm) { page = "" }
        "storage" -> NotebookDialog("备份与恢复", "笔记备份面板", { page = "" }) {
            NotebookFilePanel(ui, files)
            FeedbackOutlinedButton(onClick = { page = "trash" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("回收站 ${ui.data.noteTrash.size}") }
        }
    }
}

@Composable private fun NotebookChoice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable private fun NotebookDialog(title: String, tag: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }, confirmButton = { FeedbackTextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
}

@Composable fun NotebookNoteDetails(note: Note, ui: UiState, vm: StudyViewModel, onClose: () -> Unit) {
    var tags by rememberSaveable(note.id) { mutableStateOf(note.tags.joinToString("，")) }
    var review by rememberSaveable(note.id) { mutableStateOf(note.reviewState) }
    var links by rememberSaveable(note.id) { mutableStateOf(note.relatedNoteIds) }
    var query by rememberSaveable(note.id) { mutableStateOf("") }
    val editable = ui.noteEditable(note.id)
    AlertDialog(onDismissRequest = onClose, title = { Text("标签与复习") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("笔记详情面板"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(tags, { tags = it.take(600) }, label = { Text("标签，用逗号分隔") }, supportingText = { Text("最多 12 个标签，每个最多 32 个字。") }, modifier = Modifier.fillMaxWidth(), enabled = editable)
            Text("复习状态", style = MaterialTheme.typography.titleSmall)
            NOTE_REVIEW_STATES.forEach { (id, label) -> NotebookChoice(label, review == id, editable) { review = id } }
            HorizontalDivider()
            Text("关联笔记", style = MaterialTheme.typography.titleSmall)
            Text("选中的笔记会互相显示关联，正文与草稿保持独立。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(120) }, label = { Text("查找关联笔记") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val candidates = ui.data.notes.filter { it.id != note.id && (query.isBlank() || it.title.contains(query, true)) }
            candidates.take(100).forEach { target ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("关联选项 ${target.id}").toggleable(target.id in links, enabled = editable, role = Role.Checkbox, onValueChange = { checked -> links = if (checked) (links + target.id).distinct().take(100) else links - target.id }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(target.id in links, onCheckedChange = null, enabled = editable)
                    Text(target.title, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (candidates.isEmpty()) Text("没有可关联的笔记。")
            if (candidates.size > 100) Text("显示前 100 份，请输入标题缩小范围。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { FeedbackTextButton(onClick = {
        if (vm.ui.value.noteEditable(note.id) && vm.updateNotebookState { current -> current.withNoteDetails(note.id, tags.split(Regex("[,，;；\\s]+")), review, links) }) onClose()
    }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存标签与复习") } }, dismissButton = { FeedbackTextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable fun NotebookHistory(note: Note, ui: UiState, vm: StudyViewModel, onClose: () -> Unit) {
    val revisions = ui.data.noteHistory.filter { it.note.id == note.id }.sortedByDescending { it.savedAt }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    NotebookDialog("历史版本", "笔记历史面板", onClose) {
        Text("恢复会新建一份笔记，当前正文、草稿与课堂记录都会保留。", style = MaterialTheme.typography.bodySmall)
        if (revisions.isEmpty()) Text("手动修改正文或标记后，会在这里保留之前的版本。")
        revisions.forEach { revision ->
            Text(date.format(Date(revision.savedAt)), style = MaterialTheme.typography.labelLarge)
            Text(notePreview(revision.note.content).take(600).ifBlank { "空白正文" }, style = MaterialTheme.typography.bodySmall)
            if (revision.note.annotations.isNotEmpty()) Text("含 ${revision.note.annotations.size} 项个人标记与备注", style = MaterialTheme.typography.bodySmall)
            FeedbackOutlinedButton(onClick = {
                if (!vm.ui.value.locked && vm.updateNotebookState { it.restoreNoteRevision(revision.id) }) { vm.notice("历史版本已恢复为独立副本。"); onClose() }
            }, enabled = !ui.locked && ui.ready && ui.storageError.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("恢复历史 ${revision.id}")) { Text("恢复此版本为副本") }
            HorizontalDivider()
        }
    }
}

@Composable fun NotebookRelated(note: Note, ui: UiState, onOpen: (Note) -> Unit, onClose: () -> Unit) {
    val linked = linkedNotes(ui.data, note)
    NotebookDialog("关联笔记", "笔记关联面板", onClose) {
        Text("这里同时显示本笔记的关联与其他笔记指向它的关联。", style = MaterialTheme.typography.bodySmall)
        if (linked.isEmpty()) Text("还没有关联，可在“标签与复习”中添加。")
        linked.forEach { target -> FeedbackOutlinedButton(onClick = { onClose(); onOpen(target) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(target.title, maxLines = 3, overflow = TextOverflow.Ellipsis) } }
    }
}

@Composable fun NotebookStatus(note: Note) {
    val labels = note.tags.map { "#$it" } + if (note.reviewState != "unreviewed") listOfNotNull(NOTE_REVIEW_STATES[note.reviewState]) else emptyList()
    if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable private fun NotebookFilePanel(ui: UiState, files: NotebookFileActions) {
    Text(notebookBackupExplanation, style = MaterialTheme.typography.bodySmall)
    val enabled = ui.ready && ui.storageError.isBlank() && !ui.locked && !files.busy
    FeedbackOutlinedButton(onClick = files.backup, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("导出笔记备份") }
    FeedbackOutlinedButton(onClick = files.restore, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("从备份恢复") }
    if (files.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理笔记文件…", style = MaterialTheme.typography.bodySmall) }
}

@Composable fun NotebookStorage(ui: UiState, vm: StudyViewModel) {
    var trash by rememberSaveable { mutableStateOf(false) }
    val files = rememberNotebookFileActions(ui, vm)
    StudyPanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("笔记文件与恢复", style = MaterialTheme.typography.titleSmall)
            NotebookFilePanel(ui, files)
            FeedbackOutlinedButton(onClick = { trash = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("回收站 ${ui.data.noteTrash.size}") }
        }
    }
    if (trash) NotebookTrash(ui, vm) { trash = false }
}

@Composable private fun NotebookTrash(ui: UiState, vm: StudyViewModel, onClose: () -> Unit) {
    var pending by remember { mutableStateOf<Set<String>?>(null) }
    val writable = ui.ready && ui.storageError.isBlank() && !ui.locked
    val selected = pending
    if (selected != null) {
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("永久删除回收记录？") }, text = { Text("将永久删除选中的 ${selected.size} 份笔记及其历史、草稿和恢复记录。此操作无法撤销；需要保留时请先导出笔记备份。") }, confirmButton = {
            FeedbackTextButton(onClick = { if (!vm.ui.value.locked && vm.updateNotebookState { it.purgeTrashedNotes(selected) }) pending = null }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认永久删除") }
        }, dismissButton = { FeedbackTextButton(onClick = { pending = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留记录") } })
        return
    }
    NotebookDialog("回收站", "笔记回收站面板", onClose) {
        Text("删除的笔记会一直保留在这里，不会自动清空。恢复不会覆盖已有正文。", style = MaterialTheme.typography.bodySmall)
        if (ui.data.noteTrash.isEmpty()) Text("回收站为空。")
        ui.data.noteTrash.sortedByDescending { it.deletedAt }.forEach { item ->
            Text(item.note.title, style = MaterialTheme.typography.titleSmall)
            Text(notePreview(item.note.content).take(240).ifBlank { "空白正文" }, style = MaterialTheme.typography.bodySmall)
            FeedbackOutlinedButton(onClick = { if (!vm.ui.value.locked) vm.updateNotebookState { it.restoreTrashedNote(item.note.id) } }, enabled = writable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("恢复回收 ${item.note.id}")) { Text("恢复笔记") }
            FeedbackTextButton(onClick = { pending = setOf(item.note.id) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp).testTag("永久删除 ${item.note.id}"), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("永久删除…") }
            HorizontalDivider()
        }
        if (ui.data.noteTrash.isNotEmpty()) FeedbackTextButton(onClick = { pending = ui.data.noteTrash.map { it.note.id }.toSet() }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清空回收站…") }
    }
}
