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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable fun NotebookLibraryScreen(ui: UiState, vm: StudyViewModel, onOpen: (Note) -> Unit, onActions: (Note) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var review by rememberSaveable { mutableStateOf<String?>(null) }
    var quick by rememberSaveable { mutableStateOf("all") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var moving by remember { mutableStateOf(false) }
    val sorted = remember(ui.data.notes, ui.data.noteSort, ui.data.noteModifiedTimes) { libraryNotes(ui.data, "", null, null, null) }
    val searched by produceState(sorted, sorted, query) {
        if (query.isNotBlank()) kotlinx.coroutines.delay(140)
        val result = if (query.isBlank()) sorted else withContext(Dispatchers.Default) { val ids = findNotes(sorted, query).map { it.id }.toSet(); sorted.filter { it.id in ids } }
        withContext(Dispatchers.Main.immediate) { value = result }
    }
    val visible = remember(searched, selectedFolder, ui.data.noteFolders, tag, review, quick) {
        val scope = selectedFolder?.let { ui.data.folderScope(it) }
        searched.filter { (scope == null || ui.data.effectiveFolder(it) in scope) && it.matchesQuickView(quick) && (tag == null || it.tags.any { value -> value.equals(tag, true) }) && (review == null || it.reviewState == review) }
    }
    LaunchedEffect(selectedFolder, quick, query, tag, review) { selectedIds = emptyList() }
    LaunchedEffect(ui.data.notes.map { it.id }) { selectedIds = selectedIds.filter { id -> ui.data.notes.any { it.id == id } } }
    LaunchedEffect(ui.data.noteFolders, selectedFolder) { if (selectedFolder != null && selectedFolder != "" && ui.data.noteFolders.none { it.id == selectedFolder }) selectedFolder = "" }
    fun clearFilters() { query = ""; selectedFolder = null; tag = null; review = null; quick = "all" }
    if (moving) NotebookPicker(ui.data, selectedFolder, "移动到分类", allowAll = false, onCreate = { name, parent -> vm.createNotebookTarget(name, parent) }, canCreate = !ui.locked, onClose = { moving = false }) { destination ->
        if (destination != null && vm.moveNotes(selectedIds.toSet(), destination)) {
            vm.notice("已移动 ${selectedIds.size} 份笔记")
            selectedIds = emptyList(); moving = false; selecting = false
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebarWidth = if (density.fontScale > 1.4f) 300.dp else 256.dp
        val wide = maxWidth >= maxOf(840.dp, sidebarWidth + 480.dp) && maxHeight >= 360.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Surface(Modifier.width(sidebarWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) { NotebookSidebar(ui, vm, selectedFolder, { selectedFolder = it }, Modifier.fillMaxSize()) }
                VerticalDivider(Modifier.fillMaxHeight(), thickness = .5.dp)
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 920.dp).fillMaxSize().testTag("笔记列表").padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                    item {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(if (selecting) "已选 ${selectedIds.size} 份" else if (query.isBlank()) "${visible.size} 份笔记" else "找到 ${visible.size} 份", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selecting) {
                                FeedbackTextButton(onClick = { selectedIds = visible.map { it.id } }, enabled = !ui.locked && visible.isNotEmpty()) { Text("全选当前结果") }
                                FeedbackTextButton(onClick = { vm.organizeKnowledge(null, selectedIds.toSet()) }, enabled = !ui.locked && selectedIds.isNotEmpty()) { Text("整理知识总览") }
                                FeedbackIconButton(onClick = { moving = true }, enabled = !ui.locked && selectedIds.isNotEmpty()) { Icon(Icons.Outlined.DriveFileMove, "移动所选笔记") }
                                FeedbackIconButton(onClick = { selecting = false; selectedIds = emptyList() }) { Icon(Icons.Outlined.Close, "退出多选") }
                            } else {
                                FeedbackIconButton(onClick = { vm.createManualNote(selectedFolder.orEmpty()) }, enabled = !ui.locked) { Icon(Icons.Outlined.Add, "新建笔记") }
                                FeedbackTextButton(onClick = vm::notes, enabled = !ui.locked) { Text("生成笔记") }
                                NotebookTools(ui, vm, selectedFolder, tag, review) { nextTag, nextReview -> tag = nextTag; review = nextReview }
                                FeedbackIconButton(onClick = { selecting = true }, enabled = !ui.locked && ui.data.notes.isNotEmpty()) { Icon(Icons.Outlined.Checklist, "多选笔记") }
                                FeedbackIconButton(onClick = { vm.tab(3) }) { Icon(Icons.Outlined.Settings, "设置") }
                            }
                        }
                    }
                    item { OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("搜索笔记") }, placeholder = { Text("标题、正文、标签或来源") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = if (query.isNotBlank()) ({ FeedbackIconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除笔记搜索") } }) else null) }
                    if (!wide) item { NoteFolderBar(ui, vm, selectedFolder) { selectedFolder = it } }
                    else item { Text(if (selectedFolder == null) "全部笔记" else ui.data.folderPath(selectedFolder.orEmpty()), style = MaterialTheme.typography.titleSmall) }
                    if (!selecting && !selectedFolder.isNullOrBlank() && ui.data.noteFolders.any { it.id == selectedFolder }) item {
                        StudyPanel {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("串起这一章的知识", style = MaterialTheme.typography.titleSmall)
                                Text("将原笔记另存为有来源的知识总览，或围绕当前笔记本提问。原笔记与备注保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FeedbackOutlinedButton(onClick = { vm.organizeKnowledge(selectedFolder) }, enabled = !ui.locked) { Text("整理章节总览") }
                                    FeedbackTextButton(onClick = { selectedFolder?.let(vm::startCourseChat) }, enabled = !ui.locked) { Text("围绕本章提问") }
                                }
                            }
                        }
                    }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        NOTE_QUICK_VIEWS.forEach { (id, label) -> FeedbackFilterChip(quick == id, { quick = id }, label = { Text(label) }) }
                    } }
                    if (tag != null || review != null) item { FeedbackTextButton(onClick = { tag = null; review = null }) { Text("清除筛选：" + listOfNotNull(tag?.let { "#$it" }, review?.let { NOTE_REVIEW_STATES[it] }).joinToString(" · ")) } }
                    if (visible.isEmpty() && ui.data.notes.isNotEmpty()) item { StudyEmpty(Icons.Outlined.Search, if (query.isBlank()) "这个位置暂无符合条件的笔记" else "没有找到相关笔记", "可以切换笔记本，或清除当前筛选后重新查找。") { FeedbackTextButton(onClick = ::clearFilters) { Text("查看全部笔记") } } }
                    if (ui.noteDraft.isNotBlank()) item { StudyPanel { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("正在整理", style = MaterialTheme.typography.titleSmall); Text(ui.noteDraft.takeLast(1600), maxLines = 8, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) } } }
                    if (ui.data.notes.isEmpty()) item { StudyEmpty(Icons.Outlined.AutoStories, "还没有笔记", "上课模式会按课程整理课堂笔记，也可以新建自己的笔记或从学习页保存回答。") { FeedbackTextButton(onClick = { vm.tab(0) }) { Text("去学习") } } }
                    items(visible, key = { it.id }) { note ->
                        NoteListCard(note, { onOpen(note) }, { onActions(note) }, selecting, note.id in selectedIds, { selectedIds = if (note.id in selectedIds) selectedIds - note.id else selectedIds + note.id }) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(ui.data.folderPath(note.folderId), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(note.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(remember(note.content) { notePreview(note.content) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(remember(note.createdAt) { "创建于 " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(note.createdAt)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                NotebookStatus(note)
                                KnowledgeOverviewStatus(note, ui.data)
                            }
                        }
                    }
                }
            }
        }
    }
}
