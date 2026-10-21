package cn.studybridge.mobile

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

@Composable fun ClassFollowSettings(ui: UiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as? StudyApplication
    val fallback = remember { MutableStateFlow(ClassFollowStatus()) }
    val status by (app?.classFollowStatus ?: fallback).collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notifications by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    fun refresh() { ClassFollowService.refreshPermission(context); notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    LaunchedEffect(lifecycle) {
        withContext(Dispatchers.Main.immediate) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
            lifecycle.addObserver(observer)
            try { awaitCancellation() } finally { lifecycle.removeObserver(observer) }
        }
    }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    StudyPanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台跟课与悬浮窗", style = MaterialTheme.typography.titleSmall)
            Text("开始跟课后，切到其他 App 仍继续整理。圆钮单击暂停或继续，拖动移动，长按不操作。", style = MaterialTheme.typography.bodySmall)
            Text(when { status.serviceRunning && !ui.classUi.paused -> "正在后台跟随课堂"; status.serviceRunning -> "跟课已暂停，可用悬浮球继续"; ui.hasClassSession() -> "本节已保留，点击继续后运行"; else -> "开始跟课后启用" }, style = MaterialTheme.typography.labelMedium)
            FeedbackTextButton(onClick = { runCatching { overlay.launch(ClassFollowService.overlayPermissionIntent(context)) }.onFailure { vm.notice("无法打开悬浮窗设置，请在系统应用权限中允许显示在其他应用上层。") } }) { Text(if (status.overlayPermission) "已允许悬浮窗 · 管理" else "允许显示在其他 App 上") }
            if (!notifications && Build.VERSION.SDK_INT >= 33) FeedbackTextButton(onClick = { notification.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通知栏控制") }
            if (!notifications) Text("开启通知后，还能从通知栏返回、暂停或结束本节。", style = MaterialTheme.typography.bodySmall)
            if (status.error.isNotBlank()) Text(status.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
