package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.studybridge.mobile.FeedbackButton as Button
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import cn.studybridge.mobile.FeedbackFilterChip as FilterChip
import cn.studybridge.mobile.FeedbackTextButton as TextButton

/** Shared by the course tab and the chat material browser; range is always optional. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun MaterialStudyControls(ui: UiState, vm: StudyViewModel) {
    val identity = ui.localDocument?.material?.id ?: "${ui.presentationId}:${ui.slideTitle}"
    var custom by rememberSaveable(identity) { mutableStateOf(false) }
    var from by rememberSaveable(identity) { mutableStateOf(ui.slides.minOfOrNull { it.page }?.toString().orEmpty()) }
    var to by rememberSaveable(identity) { mutableStateOf(ui.slides.maxOfOrNull { it.page }?.toString().orEmpty()) }
    val selection = runCatching {
        if (custom) selectNotePages(from, to, ui.slides)
        else ui.slides.distinctBy { it.page }.sortedBy { it.page }.also { require(it.isNotEmpty()) { "暂无已开放页面。" } }
    }
    val error = selection.exceptionOrNull()?.message
    val modelError = selection.getOrNull()?.let { batchNoteValidation(ui.data, it) }
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("课件讲解选择"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI 讲解", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !custom, onClick = { custom = false }, enabled = !ui.locked, label = { Text("整份课件 · ${ui.slides.size} 页/段") })
            FilterChip(selected = custom, onClick = { custom = true }, enabled = !ui.locked, label = { Text("指定范围") })
        }
        if (custom) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fields: @Composable RowScope.() -> Unit = {
                    OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, label = { Text("开始页/段") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !ui.locked, isError = error != null)
                    OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, label = { Text("结束页/段") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !ui.locked, isError = error != null)
                }
                // Text-field labels retain their natural height even at large system font sizes.
                if (maxWidth.value / androidx.compose.ui.platform.LocalDensity.current.fontScale < 300f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, label = { Text("开始页/段") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !ui.locked, isError = error != null)
                    OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, label = { Text("结束页/段") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !ui.locked, isError = error != null)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = fields)
            }
            Text(error ?: "已选 ${selection.getOrNull()?.size ?: 0} 个已开放页面；指定范围每次跨度最多 50 页。", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else Text("包含全部已开放页面，按原页码顺序逐页生成并保存到笔记。每页会调用模型，可随时停止，已完成内容会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (modelError != null) {
            Text(modelError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.closeMaterials(); vm.tab(3); vm.settingsPage("models") }, enabled = !ui.locked) { Text("打开模型设置") }
        }
        Button(onClick = { if (custom) vm.batchNotes(from, to) else vm.batchAllNotes() }, enabled = !ui.locked && selection.isSuccess && modelError == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), unavailableReason = error ?: modelError) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (custom) "所选页面讲解并保存笔记" else "整份讲解并保存笔记")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun MaterialPageActions(page: Slide, ui: UiState, vm: StudyViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { vm.attach(page) }, enabled = !ui.locked) { Text("带入对话") }
        OutlinedButton(onClick = { vm.explainPage(page) }, enabled = !ui.locked) { Text("本页讲解并保存") }
    }
}
