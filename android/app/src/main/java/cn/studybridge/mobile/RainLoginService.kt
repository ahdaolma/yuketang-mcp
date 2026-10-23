package cn.studybridge.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

// Started by the visible login button, only for the bounded QR handshake.
// Switching to WeChat must not demote the process that owns the login socket.
class RainLoginService : Service() {
    companion object { internal const val ATTEMPT = "rain-login-attempt" }
    private val handler = Handler(Looper.getMainLooper())
    private val deadline = Runnable { stopSelf() }
    private var attempt = ""
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("rain-login", "雨课堂扫码登录", NotificationManager.IMPORTANCE_LOW))
        val back = PendingIntent.getActivity(this, 1404, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "rain-login").setSmallIcon(R.drawable.ic_notification).setContentTitle("正在等待微信扫码确认").setContentText("扫码连接保持中，确认后返回雨课伴学；可在 App 中取消。").setContentIntent(back).setOngoing(true).build()
        // Promote immediately, before any network or disk operation.
        startForeground(1404, notification)
        handler.postDelayed(deadline, 240_000)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attempt = intent?.getStringExtra(ATTEMPT).orEmpty()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(deadline)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A surviving process must not keep waiting on an unprotected socket.
        // The attempt tag prevents an old service stop from canceling a retry.
        if (attempt.isNotBlank()) (application as StudyApplication).studyViewModel.rainLoginServiceStopped(attempt)
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
}
