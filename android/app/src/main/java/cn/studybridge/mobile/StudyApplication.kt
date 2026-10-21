package cn.studybridge.mobile

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

data class ClassFollowStatus(
    val serviceRunning: Boolean = false,
    val overlayPermission: Boolean = false,
    val overlayVisible: Boolean = false,
    val error: String = ""
)

/** The classroom owner outlives every Activity and its ViewModelStore. */
class StudyApplication : Application() {
    private var model: StudyViewModel? = null
    val studyViewModel: StudyViewModel
        get() = model ?: StudyViewModel(this).also { model = it }

    private val followStatus = MutableStateFlow(ClassFollowStatus())
    val classFollowStatus = followStatus.asStateFlow()
    private val visible = MutableStateFlow(false)
    internal val appVisible = visible.asStateFlow()
    private var serviceReady: CompletableDeferred<Unit>? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++; visible.value = true }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); visible.value = started > 0 }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    internal fun updateClassFollowStatus(change: (ClassFollowStatus) -> ClassFollowStatus) {
        followStatus.update(change)
    }

    // Call only for an explicit start/resume, while the Activity, overlay or
    // notification action is visible. Promotion must precede attendance/HTTP.
    internal suspend fun awaitClassFollowService() {
        if (followStatus.value.serviceRunning) return
        val pending = serviceReady ?: CompletableDeferred<Unit>().also {
            updateClassFollowStatus { current -> current.copy(error = "") }
            serviceReady = it
            try {
                startForegroundService(Intent(this, ClassFollowService::class.java))
            } catch (error: Exception) {
                it.completeExceptionally(error)
            }
        }
        try { withTimeout(8_000) { pending.await() } }
        catch (error: TimeoutCancellationException) {
            stopService(Intent(this, ClassFollowService::class.java))
            throw IllegalStateException("后台上课服务启动超时，请回到应用重试。", error)
        }
        finally { if (serviceReady === pending) serviceReady = null }
    }

    internal fun classServiceStarted() {
        updateClassFollowStatus { it.copy(serviceRunning = true, error = "") }
        serviceReady?.complete(Unit)
    }

    internal fun classServiceStopped(reason: String = "") {
        updateClassFollowStatus { it.copy(serviceRunning = false, overlayVisible = false, error = reason) }
        serviceReady?.completeExceptionally(IllegalStateException(reason.ifBlank { "后台上课服务已停止" }))
        serviceReady = null
    }

    // Dependency replacement is restricted to an idle application and used by
    // instrumentation to exercise the real service/writer without credentials.
    internal fun installClassFixture(replacement: StudyViewModel?) {
        check(!followStatus.value.serviceRunning)
        check(model?.ui?.value?.classUi?.running != true)
        model = replacement
    }
}
