package cn.studybridge.mobile

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inspector.WindowInspector
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Runs only in cloud instrumentation. All HTTP/SSE data is synthetic. */
@RunWith(AndroidJUnit4::class)
class ClassFollowServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as StudyApplication
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(30)
        }
        assertTrue(message, condition())
    }
    private fun overlayPermission(mode: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW $mode")).use { it.readBytes() }
        main { ClassFollowService.refreshPermission(app) }
        waitFor("overlay permission did not become $mode") { ClassFollowService.canDrawOverlays(app) == (mode == "allow") }
    }

    private class Fixture(val vm: StudyViewModel, val store: ViewModelStore, val page: LivePage, val requested: CountDownLatch, val release: CountDownLatch, val original: Saved)
    private fun fixture(): Fixture {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("class-system-circle", android.content.Context.MODE_PRIVATE).edit().putFloat("xFraction", .88f).putFloat("yFraction", .35f).commit()
        val original = Vault(app).load()
        val page = LivePage("12", 2)
        // An ended lesson with a durable pending page exercises the production
        // writer without connecting to rain's attendance/socket endpoints.
        val run = ClassRun(LiveLesson("42", "后台课堂夹具", "7"), providerId = "service-fixture", pending = listOf(page), ended = true)
        val provider = Provider("service-fixture", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model", true)
        Vault(app).save(Saved(providers = listOf(provider), selectedProvider = provider.id, session = "unit-test-session-123", notes = listOf(Note(run.noteId, "后台课堂夹具", "# 原笔记\n\n", "课堂", "测试模型")), classRun = run))
        val rain = RainApi(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"code\":0,\"data\":{\"title\":\"后台课堂夹具\",\"slideList\":[{\"index\":2,\"text\":\"当前页原文\",\"visible\":true}]}}".toResponseBody()).build()
        }.build())
        val requested = CountDownLatch(1); val release = CountDownLatch(1); val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        main {
            vm = noteTestViewModel(app, store, rain, fixtureAiApi("后台真正生成并保存的第二页讲解", beforeResponse = { requested.countDown(); check(release.await(60, TimeUnit.SECONDS)) }))
            app.installClassFixture(vm)
        }
        waitFor("fixture did not load") { vm.ui.value.ready }
        assertTrue(vm.ui.value.classUi.paused)
        assertFalse("a restored classroom must not start its service/attendance", app.classFollowStatus.value.serviceRunning)
        return Fixture(vm, store, page, requested, release, original)
    }
    private fun start(fixture: Fixture) {
        main { fixture.vm.resumeClass() }
        waitFor("production writer did not start: ${fixture.vm.ui.value.classUi.status}") { fixture.requested.count == 0L || fixture.vm.ui.value.classUi.paused && app.classFollowStatus.value.error.isNotBlank() }
        assertEquals(fixture.vm.ui.value.classUi.status, 0L, fixture.requested.count)
        assertTrue(app.classFollowStatus.value.serviceRunning)
    }
    private fun home(scenario: ActivityScenario<WebFixtureActivity>) {
        scenario.onActivity { it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        waitFor("fixture Activity did not leave the foreground") { !app.appVisible.value }
    }
    private fun cleanup(fixture: Fixture) {
        fixture.release.countDown()
        main { fixture.vm.endClass(); app.stopService(Intent(app, ClassFollowService::class.java)) }
        waitFor("class service did not stop") { !app.classFollowStatus.value.serviceRunning }
        main { fixture.store.clear(); app.installClassFixture(null) }
        runBlocking { fixture.vm.awaitClassPersistence() }
        Vault(app).save(fixture.original)
    }

    @Test fun actualWriterSavesAfterHomeAndActivityDestruction() {
        val scenario = ActivityScenario.launch(WebFixtureActivity::class.java)
        val fixture = fixture()
        try {
            start(fixture)
            home(scenario)
            scenario.close()
            assertTrue("Activity destruction must retain the service-owned runtime", app.classFollowStatus.value.serviceRunning)
            assertSame(fixture.vm, app.studyViewModel)
            assertFalse(fixture.vm.ui.value.classUi.paused)
            fixture.release.countDown()
            waitFor("background writer did not checkpoint its page") { fixture.vm.ui.value.data.classRun?.completed?.contains(fixture.page.key) == true }
            val saved = Vault(app).load()
            assertTrue(saved.classRun!!.completed.contains(fixture.page.key))
            assertTrue(saved.notes.single().content.contains("后台真正生成并保存的第二页讲解"))
            assertTrue(saved.classRun!!.pending.isEmpty())
            waitFor("completed notebook should release its service") { !app.classFollowStatus.value.serviceRunning }
        } finally { scenario.close(); cleanup(fixture) }
    }

    @Test fun systemWindowAcrossHomeDistinguishesDragLongPressPauseAndResume() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val scenario = ActivityScenario.launch(WebFixtureActivity::class.java)
        val fixture = fixture()
        try {
            overlayPermission("allow")
            start(fixture)
            assertFalse("the in-app control must not be duplicated", app.classFollowStatus.value.overlayVisible)
            home(scenario)
            waitFor("system overlay did not appear over Home") { app.classFollowStatus.value.overlayVisible }
            lateinit var circle: ClassOverlayView
            main { circle = WindowInspector.getGlobalWindowViews().filterIsInstance<ClassOverlayView>().single() }
            fun touch(action: Int, x: Float, y: Float, time: Long, down: Long) {
                val event = MotionEvent.obtain(down, time, action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            fun center(): Pair<Float, Float> {
                var result = 0f to 0f
                main { val position = IntArray(2); circle.getLocationOnScreen(position); result = position[0] + circle.width / 2f to position[1] + circle.height / 2f }
                return result
            }
            val origin = center(); val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, origin.first, origin.second, down, down)
            touch(MotionEvent.ACTION_MOVE, origin.first - 95f, origin.second + 65f, down + 80, down)
            touch(MotionEvent.ACTION_UP, origin.first - 95f, origin.second + 65f, down + 120, down)
            instrumentation.waitForIdleSync()
            assertNotEquals(origin, center())
            assertFalse("drag must not pause the writer", fixture.vm.ui.value.classUi.paused)
            val moved = center(); val hold = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, moved.first, moved.second, hold, hold)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 120L)
            touch(MotionEvent.ACTION_UP, moved.first, moved.second, SystemClock.uptimeMillis(), hold)
            assertFalse("long press must have no pause/navigation action", fixture.vm.ui.value.classUi.paused)
            assertFalse(app.appVisible.value)
            advancedUiShot(app, "sample-class-system-circle-over-home")
            val tap = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, moved.first, moved.second, tap, tap)
            touch(MotionEvent.ACTION_UP, moved.first, moved.second, tap + 60, tap)
            waitFor("circle click did not pause") { fixture.vm.ui.value.classUi.paused }
            assertTrue(app.classFollowStatus.value.serviceRunning)
            assertEquals(listOf(fixture.page), fixture.vm.ui.value.data.classRun!!.pending)
            val resume = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, moved.first, moved.second, resume, resume)
            touch(MotionEvent.ACTION_UP, moved.first, moved.second, resume + 60, resume)
            waitFor("circle click did not resume") { !fixture.vm.ui.value.classUi.paused }
            fixture.release.countDown()
            waitFor("resumed writer did not save") { fixture.vm.ui.value.data.classRun!!.completed.contains(fixture.page.key) }
        } finally { scenario.close(); cleanup(fixture); overlayPermission("deny") }
    }

    @Test fun deniedOrRevokedOverlayKeepsWriterAndNotificationControlsAndServiceStopPauses() {
        val scenario = ActivityScenario.launch(WebFixtureActivity::class.java)
        val fixture = fixture()
        try {
            overlayPermission("deny")
            start(fixture)
            home(scenario)
            assertFalse(app.classFollowStatus.value.overlayVisible)
            assertFalse(fixture.vm.ui.value.classUi.paused)
            overlayPermission("allow")
            waitFor("overlay grant was not observed") { app.classFollowStatus.value.overlayVisible }
            overlayPermission("deny")
            waitFor("revoked window was not removed") { !app.classFollowStatus.value.overlayVisible }
            assertTrue(app.classFollowStatus.value.serviceRunning)
            assertFalse("overlay revocation must not lose classroom work", fixture.vm.ui.value.classUi.paused)
            val notification = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.notification.channelId == "class-follow" }.notification
            notification.actions.single { it.title.toString() == "暂停" }.actionIntent.send()
            waitFor("notification pause was not applied") { fixture.vm.ui.value.classUi.paused }
            assertEquals(listOf(fixture.page), Vault(app).load().classRun!!.pending)
            waitFor("paused notification was not updated") {
                app.getSystemService(NotificationManager::class.java).activeNotifications.any { item -> item.notification.channelId == "class-follow" && item.notification.actions.any { it.title.toString() == "继续" } }
            }
            app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.notification.channelId == "class-follow" }.notification.actions.single { it.title.toString() == "继续" }.actionIntent.send()
            waitFor("notification resume was not applied") { !fixture.vm.ui.value.classUi.paused }
            main { app.stopService(Intent(app, ClassFollowService::class.java)) }
            waitFor("unexpected service termination did not become paused/stopped") { !app.classFollowStatus.value.serviceRunning && fixture.vm.ui.value.classUi.paused }
            assertEquals(listOf(fixture.page), fixture.vm.ui.value.data.classRun!!.pending)
            assertTrue(fixture.vm.ui.value.classUi.status.contains("服务已停止"))
        } finally { scenario.close(); cleanup(fixture); overlayPermission("deny") }
    }

    @Test fun notificationEndStopsRuntimeAndPreservesSavedNotebook() {
        val scenario = ActivityScenario.launch(WebFixtureActivity::class.java)
        val fixture = fixture()
        try {
            start(fixture)
            home(scenario)
            app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.notification.channelId == "class-follow" }.notification.actions.single { it.title.toString() == "结束本节" }.actionIntent.send()
            waitFor("notification end did not stop the runtime") { !app.classFollowStatus.value.serviceRunning && !fixture.vm.ui.value.classUi.running }
            assertTrue(fixture.vm.ui.value.data.classRun!!.ended)
            assertTrue(fixture.vm.ui.value.data.classRun!!.pending.isEmpty())
            assertEquals("# 原笔记\n\n", fixture.vm.ui.value.data.notes.single().content)
        } finally { scenario.close(); cleanup(fixture) }
    }
}
