package cn.studybridge.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

// Only keeps the local OAuth callback alive while the user is in the system browser.
class AuthorizationService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("chatgpt-auth", "ChatGPT 账号授权", NotificationManager.IMPORTANCE_LOW))
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "chatgpt-auth").setSmallIcon(cn.studybridge.mobile.R.drawable.ic_launcher).setContentTitle("正在等待 ChatGPT 授权").setContentText("完成浏览器登录后返回雨课伴学；可在 App 中取消。").setContentIntent(back).setOngoing(true).build()
        startForeground(1403, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
}
