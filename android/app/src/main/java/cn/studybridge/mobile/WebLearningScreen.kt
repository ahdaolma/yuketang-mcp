package cn.studybridge.mobile

import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import cn.studybridge.mobile.FeedbackButton as Button
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import cn.studybridge.mobile.FeedbackFilledTonalButton as FilledTonalButton
import cn.studybridge.mobile.FeedbackIconButton as IconButton
import cn.studybridge.mobile.FeedbackFilledIconButton as FilledIconButton
import cn.studybridge.mobile.FeedbackFilterChip as FilterChip
import cn.studybridge.mobile.FeedbackSwitch as Switch
import cn.studybridge.mobile.FeedbackDropdownMenuItem as DropdownMenuItem
import cn.studybridge.mobile.FeedbackNavigationBarItem as NavigationBarItem
import cn.studybridge.mobile.FeedbackCard as Card
import cn.studybridge.mobile.FeedbackOutlinedCard as OutlinedCard

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable fun WebLearningScreen(ui: UiState, vm: StudyViewModel, web: ChatGptWebSession, active: Boolean, revision: Int) {
    var menu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        chooser?.onReceiveValue(uris.filter { it.scheme == "content" }.take(10).toTypedArray()); chooser = null
    }
    DisposableEffect(web, picker) {
        web.view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { web.loading(newProgress) }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!isChatGptPage(view.url ?: "")) { callback.onReceiveValue(null); return true }
                chooser?.onReceiveValue(null); chooser = callback
                picker.launch(arrayOf("image/*", "application/pdf", "text/plain")); return true
            }
        }
        onDispose { chooser?.onReceiveValue(null); chooser = null; web.view.webChromeClient = null }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("清除待处理标记？") }, text = { Text("网页中的消息不会删除。请先检查并保存已有回答；清除后再次处理同一页可能重复发送并消耗额度。") }, confirmButton = { TextButton(onClick = { vm.discardWebPending(); discard = false }) { Text("确认清除") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("返回检查") } })
    Column(Modifier.fillMaxSize()) {
        if (active) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ChatGPT 网页", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = vm::saveWebAnswer, enabled = !ui.locked) { Text("保存回答") }
                if (ui.busy) TextButton(onClick = vm::stop) { Text("停止") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "网页选项") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("刷新网页") }, enabled = !ui.locked, onClick = { web.view.reload(); menu = false })
                        DropdownMenuItem(text = { Text("回到 ChatGPT 首页") }, enabled = !ui.locked, onClick = { web.load(); menu = false })
                        DropdownMenuItem(text = { Text("在系统浏览器打开") }, enabled = !ui.locked, onClick = { vm.openLink(webReturnUrl(web.pageUrl)); menu = false })
                        if (ui.data.webPending != null) {
                            DropdownMenuItem(text = { Text("打开待完成的对话") }, enabled = !ui.locked, onClick = { web.load(ui.data.webPending.url); menu = false })
                            DropdownMenuItem(text = { Text("清除待处理标记…") }, enabled = !ui.locked, onClick = { discard = true; menu = false })
                        }
                    }
                }
                IconButton(onClick = { vm.tab(3) }) { Icon(Icons.Default.Settings, "设置") }
            }
            if (web.progress < 100) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.data.webPending != null && !ui.locked) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("有一份待保存的网页回答", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::saveWebAnswer) { Text("恢复回答") }
                }
            }
            if (ui.classUi.running) Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.classUi.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { if (ui.classUi.paused) vm.resumeClass() else vm.pauseClass() }, enabled = !ui.busy) { Text(if (ui.classUi.paused) "继续" else "暂停") }
            }
            ui.pending?.let { a -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = vm::fillWebAttachment, enabled = !ui.locked) { Text("带入课件") }
                    IconButton(onClick = vm::clearAttachment, enabled = !ui.locked) { Icon(Icons.Default.Close, "移除课件") }
                }
            } }
            if (web.error.isNotBlank()) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(web.error, style = MaterialTheme.typography.bodyMedium)
                Row { TextButton(onClick = { web.view.reload() }, enabled = !ui.locked) { Text("重试") }; TextButton(onClick = { vm.openLink(CHATGPT_WEB) }, enabled = !ui.locked) { Text("使用系统浏览器") } }
            }
        }
        // Keep this WebView alive across native tabs so the current conversation is not reloaded.
        AndroidView(factory = { web.view }, modifier = Modifier.weight(1f).fillMaxWidth(), update = {
            @Suppress("UNUSED_VARIABLE") val pageRevision = revision
            it.visibility = if (active) View.VISIBLE else View.INVISIBLE
            it.importantForAccessibility = if (active) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
    }
}
