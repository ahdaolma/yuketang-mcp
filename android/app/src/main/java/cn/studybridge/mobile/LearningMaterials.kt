package cn.studybridge.mobile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import cn.studybridge.mobile.FeedbackIconButton as IconButton
import cn.studybridge.mobile.FeedbackCard as Card
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun MaterialsButton(ui: UiState, vm: StudyViewModel) {
    IconButton(onClick = vm::openMaterials, enabled = !ui.locked) { Icon(Icons.Outlined.FolderOpen, "学习资料") }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun LearningMaterialsHost(ui: UiState, vm: StudyViewModel) {
    var importKind by rememberSaveable { mutableStateOf("courseware") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importMaterial(uri, importKind) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) vm.downloadDeck(uri) else vm.cancelDeckDownload() }
    var from by rememberSaveable(ui.slideTitle) { mutableStateOf("1") }
    var to by rememberSaveable(ui.slideTitle) { mutableStateOf("") }
    fun launchImport(kind: String) { importKind = kind; importer.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*", "text/*", "application/octet-stream")) }
    if (!ui.materialsOpen) return
    BackHandler { vm.closeMaterials() }
    ModalBottomSheet(onDismissRequest = vm::closeMaterials, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("学习资料", style = MaterialTheme.typography.titleLarge); if (ui.materialPane != "home") TextButton(onClick = { vm.materialPane("home") }, enabled = !ui.locked) { Text("返回资料") } } }
            if (ui.materialPane == "home") {
                item { StudySectionLabel("选择资料") }
                item { MaterialRow("雨课堂课件", "选择课程、查看原名与时间，下载或讲解", !ui.locked, vm::browseRainMaterials) }
                item { MaterialRow("本机资料", "${ui.data.materials.size} 份已导入文件", !ui.locked, { vm.materialPane("files") }) }
                item { MaterialRow("已有笔记", "选择笔记，带入 AI 继续讲解", !ui.locked, { vm.materialPane("notes") }) }
                item { StudySectionLabel("导入资料") }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { launchImport("courseware") }, enabled = !ui.locked) { Text("导入课件") }; OutlinedButton(onClick = { launchImport("notes") }, enabled = !ui.locked) { Text("导入笔记") } } }
                item { Text("支持 PDF、PPTX、图片、DOCX、Markdown、TXT。PPTX/DOCX 提取文字和图片；复杂版式建议转为 PDF。每份最多 50 MB。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (ui.materialPane == "files") {
                if (ui.data.materials.isEmpty()) item { Text("还没有导入资料。") }
                items(ui.data.materials, key = { it.id }) { m -> MaterialRow(m.title, "${m.format.uppercase()} · ${if (m.kind == "notes") "笔记" else "课件"} · ${m.pages} 页/段\n导入：${readableTime(m.importedAt)}" + if (m.modifiedAt > 0) "\n文件修改：${readableTime(m.modifiedAt)}" else "", !ui.locked, { vm.openMaterial(m) }) }
            }
            if (ui.materialPane == "notes") {
                if (ui.data.notes.isEmpty()) item { Text("还没有笔记，可先导入笔记或保存 AI 讲解。") }
                items(ui.data.notes, key = { it.id }) { n -> MaterialRow(n.title, "笔记 · ${readableTime(n.createdAt)}", !ui.locked, { vm.explainNote(n) }) }
            }
            if (ui.materialPane == "rain") {
                if (ui.slides.isEmpty()) {
                    item { Row { TextButton(onClick = vm::allCourses, enabled = !ui.locked) { Text("课程列表") }; TextButton(onClick = vm::loadCourses, enabled = !ui.locked) { Text("刷新") } } }
                    if (ui.course == null) items(ui.courses, key = { it.id }) { c -> MaterialRow(c.title, c.detail, !ui.locked, { vm.loadActivities(c) }) }
                    else {
                        item { Text(ui.course.title, style = MaterialTheme.typography.titleMedium) }
                        items(ui.presentations, key = { "${it.lessonId}:${it.id}" }) { p -> MaterialRow(p.title, (if (p.isLesson) "课堂记录" else "课件") + " · " + p.recordedAt.ifBlank { "雨课堂未提供时间" }, !ui.locked, { vm.openPresentation(p) }) }
                        item { Row { TextButton(onClick = { vm.loadActivities(ui.course, ui.activityPage - 1) }, enabled = !ui.locked && ui.activityPage > 0) { Text("上一批") }; TextButton(onClick = { vm.loadActivities(ui.course, ui.activityPage + 1) }, enabled = !ui.locked && ui.activityPage < 1000) { Text("下一批") } } }
                    }
                }
            }
            if ((ui.materialPane == "rain" || ui.materialPane == "pages") && ui.slides.isNotEmpty()) {
                item { Text(ui.slideTitle, style = MaterialTheme.typography.titleMedium); Text(ui.slideRecordedAt.ifBlank { "雨课堂未提供时间" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (ui.localDocument == null) item { OutlinedButton(onClick = { vm.prepareDeckDownload()?.let { name -> if (ui.data.downloadTreeUri.isBlank()) pdf.launch(name) else vm.downloadDeck() } }, enabled = !ui.locked) { Text("下载课件 PDF") }; Text("按已开放页面重建 PDF，并非原始 PPT。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, label = { Text("开始页/段") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !ui.locked); OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, label = { Text("结束页/段") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !ui.locked) }; TextButton(onClick = { vm.closeMaterials(); vm.batchNotes(from, to) }, enabled = !ui.locked && from.isNotBlank() && to.isNotBlank()) { Text("连续讲解并保存 · 每次最多 50 页/段") } }
                items(ui.slides, key = { it.page }) { page -> StudyPanel { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("第 ${page.page} ${if (ui.localDocument?.material?.format in listOf("txt", "md", "docx")) "段" else "页"}", style = MaterialTheme.typography.titleSmall); if (page.text.isNotBlank()) Text(notePreview(page.text), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { TextButton(onClick = { vm.attach(page) }, enabled = !ui.locked) { Text("带入学习") }; TextButton(onClick = { vm.explainPage(page) }, enabled = !ui.locked) { Text("讲解并保存") } } } } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable private fun MaterialRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable fun DownloadDirectorySettings(ui: UiState, vm: StudyViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.setDownloadDirectory(uri) }
    StudySectionLabel("文件与下载")
    StudyPanel { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("下载文件夹", style = MaterialTheme.typography.titleSmall); Text(ui.data.downloadDirectoryName.ifBlank { "每次询问保存位置" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("课件 PDF 与 Markdown 笔记共用此位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Row { TextButton(onClick = { picker.launch(ui.data.downloadTreeUri.takeIf(String::isNotBlank)?.let(Uri::parse)) }, enabled = !ui.locked) { Text("选择文件夹") }; if (ui.data.downloadTreeUri.isNotBlank()) TextButton(onClick = { vm.setDownloadDirectory(null) }, enabled = !ui.locked) { Text("每次询问") } } } }
}
