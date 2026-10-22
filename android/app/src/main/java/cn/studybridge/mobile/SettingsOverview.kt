package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

fun settingsPageTitle(page: String) = when (page) { "models" -> "模型与账号"; "notes" -> "笔记生成"; "learning" -> "学习偏好"; "files" -> "文件与下载"; "about" -> "关于雨课伴学"; else -> "设置" }

@Composable fun SettingsOverview(ui: UiState, vm: StudyViewModel) {
    val provider = ui.data.provider()
    val ready = ui.data.providerReady(provider) && provider.model.isNotBlank()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (ready) "学习服务已配置" else "先配置学习服务", style = MaterialTheme.typography.titleMedium)
        Text(if (ready) "${provider.name} · ${provider.model}" else "打开“模型与账号”，连接服务并选择模型后就能开始。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsCategory("模型与账号", if (ready) "已配置 · 更换服务、选择模型或管理授权" else "尚未配置 · 添加服务并选择模型", Icons.Outlined.Tune) { vm.settingsPage("models") }
        SettingsCategory("笔记生成", if (ui.data.superFocusNotes) "超重点模式已开启 · 自动整理" else "普通讲解 · 超重点模式与自动整理", Icons.Outlined.EditNote) { vm.settingsPage("notes") }
        SettingsCategory("学习偏好", "学习基础、讲解偏好与高级提示词", Icons.Outlined.School) { vm.settingsPage("learning") }
        SettingsCategory("文件与下载", ui.data.downloadDirectoryName.ifBlank { "设置笔记与课件的下载位置" }, Icons.Outlined.FolderOpen) { vm.settingsPage("files") }
        SettingsCategory("关于雨课伴学", "版本、项目地址与声明", Icons.Outlined.Info) { vm.settingsPage("about") }
        Text("账号、密钥和文字记录加密保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsCategory(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    FeedbackCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}
