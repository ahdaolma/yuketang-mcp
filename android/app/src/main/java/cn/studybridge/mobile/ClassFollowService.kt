package cn.studybridge.mobile

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Owns the lifetime of the application classroom runtime and system window. */
class ClassFollowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as StudyApplication
    private val vm get() = app.studyViewModel
    private lateinit var overlay: ClassOverlayWindow
    private var stopping = false
    private var started = false
    private var permissionWasGranted = false
    private var lastNotification = ""
    private var failure = ""
    private val appOpsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName) handler.post { render(vm.ui.value) }
    }
    // Some vendor systems do not deliver the app-op callback for window changes.
    private val permissionCheck = object : Runnable {
        override fun run() { if (!stopping) { render(vm.ui.value); handler.postDelayed(this, 2_000) } }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = ClassOverlayWindow(this, onToggle = {
            if (vm.ui.value.classUi.paused) vm.resumeClass() else vm.pauseClass()
        }, onFailure = { message -> failure = message; vm.notice(message); publishStatus() })
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "上课跟随与悬浮球", NotificationManager.IMPORTANCE_LOW))
        try {
            // Promotion happens before the VM can enter a classroom or start a stream.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(vm.ui.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification(vm.ui.value))
            app.classServiceStarted()
        } catch (_: Exception) {
            failAndStop("后台上课服务启动失败，请返回应用后重试；待整理页面和已保存笔记已保留。")
            return
        }
        runCatching { getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, appOpsListener) }
        scope.launch { combine(vm.ui, app.appVisible) { ui, _ -> ui }.collect { render(it) } }
        handler.post(permissionCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping) return START_NOT_STICKY
        started = true
        when (intent?.action) {
            ACTION_PAUSE -> vm.pauseClass()
            ACTION_RESUME -> scope.launch { vm.ui.first { it.ready }; if (!stopping) vm.resumeClass() }
            ACTION_END -> vm.endClass()
        }
        render(vm.ui.value)
        return START_NOT_STICKY
    }

    private fun render(ui: UiState) {
        if (stopping) return
        val allowed = canDrawOverlays(this)
        if (permissionWasGranted && !allowed) {
            failure = "悬浮窗权限已关闭；上课继续运行，可从通知栏暂停或结束。"
            vm.notice(failure)
        }
        if (allowed && !permissionWasGranted) failure = ""
        permissionWasGranted = allowed
        if (allowed && ui.classUi.running && !app.appVisible.value) {
            runCatching { overlay.show(ui.classUi.paused); if (overlay.visible) failure = "" }.onFailure {
                overlay.hide(); failure = "悬浮球暂时无法显示；上课继续运行，可从通知栏控制。"; vm.notice(failure)
            }
        } else overlay.hide()
        publishStatus()
        if (started && ui.ready && !ui.busy && !ui.classUi.running) {
            stopNormally()
            return
        }
        val signature = "${ui.classUi.paused}|${ui.classUi.status}|${ui.data.classRun?.completed?.size}|$failure"
        if (signature != lastNotification) {
            lastNotification = signature
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(ui)) }
                .onFailure { failAndStop("后台通知无法更新，上课已暂停；已有笔记与待整理页面已保留。") }
        }
    }

    private fun publishStatus() {
        app.updateClassFollowStatus { it.copy(overlayPermission = canDrawOverlays(this), overlayVisible = overlay.visible, error = failure) }
    }

    private fun notification(ui: UiState): Notification {
        val back = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused = ui.classUi.paused
        fun action(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, ClassFollowService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = failure.ifBlank { ui.classUi.status.ifBlank { "正在准备上课跟随，离开应用后仍可在此控制" } }
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "上课跟随已暂停" else "正在跟随课堂 · 已保存 ${ui.data.classRun?.completed?.size ?: 0} 页")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(back).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "返回课堂", back).build())
            .addAction(Notification.Action.Builder(null, if (paused) "继续" else "暂停", action(if (paused) ACTION_RESUME else ACTION_PAUSE, NOTIFICATION + 1)).build())
            .addAction(Notification.Action.Builder(null, "结束本节", action(ACTION_END, NOTIFICATION + 2)).build()).build()
    }

    private fun stopNormally() { stopping = true; overlay.hide(); app.classServiceStopped(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun failAndStop(reason: String) {
        if (stopping) return
        stopping = true
        failure = reason
        vm.pauseClass(reason)
        app.classServiceStopped(reason)
        overlay.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); runCatching { overlay.reconfigure() }.onFailure { overlay.hide() }; render(vm.ui.value) }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the Activity task must not remove the foreground owner.
        vm.background()
        super.onTaskRemoved(rootIntent)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { failAndStop("系统后台上课时限已到，已暂停并保留待整理页面；回到应用后可手动继续。") }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        val interrupted = !stopping
        stopping = true
        handler.removeCallbacksAndMessages(null)
        runCatching { getSystemService(AppOpsManager::class.java).stopWatchingMode(appOpsListener) }
        if (interrupted) { failure = "后台上课服务已停止，已暂停；回到应用后可手动继续。"; vm.pauseClass(failure) }
        overlay.hide()
        app.classServiceStopped(failure)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "class-follow"
        private const val NOTIFICATION = 1406
        internal const val ACTION_PAUSE = "cn.studybridge.mobile.class.PAUSE"
        internal const val ACTION_RESUME = "cn.studybridge.mobile.class.RESUME"
        internal const val ACTION_END = "cn.studybridge.mobile.class.END"
        fun canDrawOverlays(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        fun overlayPermissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        fun refreshPermission(context: Context) {
            val app = context.applicationContext as? StudyApplication ?: return
            app.updateClassFollowStatus { it.copy(overlayPermission = canDrawOverlays(context)) }
            // The running service observes app visibility and polls revocation.
            // This does not start a service or resume attendance from Settings.
        }
    }
}
