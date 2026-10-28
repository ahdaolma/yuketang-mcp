package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalKnowledgeLink = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable fun KnowledgeSourceHost(ui: UiState, vm: StudyViewModel, content: @Composable () -> Unit) {
    var sourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = remember { { sourceUrl = it } }
    CompositionLocalProvider(LocalKnowledgeLink provides open) {
        content()
        val url = sourceUrl
        if (url != null && ui.ready) {
            val location = remember(url) { knowledgeLocation(url) }
            val current = ui.data.notes.find { it.id == location?.noteId && ui.data.noteTrash.none { trash -> trash.note.id == it.id } }
            val actual = remember(current, location) { location?.let { knowledgeSourceText(ui.data, it) } }
            ReadingActionSheet("核对笔记来源", { sourceUrl = null }) {
                Text(current?.title ?: "来源当前不可用", style = MaterialTheme.typography.titleMedium)
                Text("来源类型：已保存笔记。AI 整理内容和个人备注不自动等于课件原文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actual == null) Text(if (current == null) "源笔记已删除或不在本机，无法读取这条引用。" else "源笔记正文已修改。这条引用属于旧版本，不会用旧位置指向新正文；可打开最新笔记核对。", color = MaterialTheme.colorScheme.error)
                else {
                    val image by produceState<String?>(null, current, location) {
                        value = withContext(Dispatchers.Default) { knowledgeSourceImage(actual.first, location!!) }
                    }
                    CompositionLocalProvider(LocalNoteFontSize provides ui.data.noteFontSize) {
                        image?.takeUnless { actual.second.contains(it) }?.let { IllustratedMarkdown(it, vm.sourceImages) }
                        IllustratedMarkdown(actual.second, vm.sourceImages)
                    }
                }
                if (current != null) FeedbackOutlinedButton(onClick = {
                    val id = current.id; val fromChat = ui.tab == 0
                    if (vm.openChatNote(id, returnToChat = fromChat)) sourceUrl = null
                }, enabled = !ui.locked) { Text("打开原笔记") }
            }
        }
    }
}

@Composable fun CourseChatScope(ui: UiState, vm: StudyViewModel) {
    val folder = ui.data.chat().notebookId ?: return
    val exists = ui.data.noteFolders.any { it.id == folder }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (exists) "课程范围 · ${ui.data.folderPath(folder)}" else "原课程范围已删除", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FeedbackIconButton(onClick = vm::leaveCourseChatScope, enabled = !ui.locked) { Icon(Icons.Outlined.Close, "退出课程范围") }
        }
    }
}

@Composable fun KnowledgeOverviewStatus(note: Note, saved: Saved) {
    if (note.overviewSources.isEmpty()) return
    val changed by produceState<Int?>(null, note.overviewSources, saved.notes) {
        value = withContext(Dispatchers.Default) {
            note.overviewSources.count { (id, version) -> saved.notes.find { it.id == id }?.let { knowledgeNoteVersion(it) } != version }
        }
    }
    Text(if (changed == null || changed == 0) "知识总览 · ${note.overviewSources.size} 份来源" else "知识总览 · $changed 份来源有新修改或已删除", style = MaterialTheme.typography.labelSmall,
        color = if (changed == null || changed == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
}
