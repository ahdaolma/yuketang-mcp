package cn.studybridge.mobile

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Real TLS/WebSocket, foreground service and HOME/return lifecycle; all QR images,
// account responses and files are synthetic and isolated from the app's records.
class RainLoginBackgroundTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private class IsolatedApplication(base: Context, directory: File) : Application() {
        init { attachBaseContext(object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }) }
    }
    @Suppress("DEPRECATION") private fun foreground(context: Context) = context.getSystemService(ActivityManager::class.java).getRunningServices(100).any {
        it.service.className == RainLoginService::class.java.name && it.foreground
    }
    private fun returnToApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        compose.waitUntil(10000) { compose.activityRule.scenario.state == Lifecycle.State.RESUMED }
    }
    @Test fun savedQrSurvivesBackgroundBeyondOldRefreshIntervalAndConfirmationPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "login-fixture-${newId()}").apply { mkdirs() }
        val app = IsolatedApplication(context, directory)
        val ownerApplication = context.applicationContext as StudyApplication
        val originalOwner = ownerApplication.studyViewModel
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        val socket = AtomicReference<WebSocket>()
        val requests = AtomicInteger()
        val png = Base64.decode(syntheticSlideImage().substringAfter(','), Base64.DEFAULT)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/wsapp/" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JSONObject(text).optString("op") == "requestlogin") {
                            requests.incrementAndGet()
                            webSocket.send("""{"op":"requestlogin","ticket":"https://www.yuketang.cn/fixture-qr.png"}""")
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                })
                "/fixture-qr.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                "/pc/web_login" -> MockResponse().setHeader("Set-Cookie", "sessionid=fixture-session-123; Path=/; Secure; HttpOnly").setBody("{}")
                "/api/v3/user/basic-info" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":0,"data":{"id":88,"name":"扫码测试"}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val client = defaultRainClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).addInterceptor { chain ->
            // Redirect every fixture request before any real network connection.
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        lateinit var vm: StudyViewModel
        try {
            compose.runOnIdle { vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = StudyViewModel(app, RainApi(client)) as T
            })[StudyViewModel::class.java]; ownerApplication.installClassFixture(vm) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { vm.tab(1); vm.startLogin() }
            compose.waitUntil(10000) { vm.ui.value.qr != null }
            val firstQr = requireNotNull(vm.ui.value.qr).clone()
            compose.activityRule.scenario.recreate()
            assertSame("MainActivity must retain the application's login owner", vm, ownerApplication.studyViewModel)
            assertArrayEquals("Activity recreation must keep the same saved QR", firstQr, vm.ui.value.qr)
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("微信登录二维码").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("微信登录二维码").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { vm.rainLoginServiceStopped("superseded-fixture-attempt") }
            assertTrue("An old service callback must not cancel the current attempt", vm.ui.value.loginActive)
            compose.waitUntil(5000) { foreground(context) }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            compose.waitUntil(5000) { compose.activityRule.scenario.state == Lifecycle.State.CREATED }
            val started = SystemClock.elapsedRealtime()
            // The old implementation silently replaced the saved ticket at 50s.
            compose.waitUntil(56_000) { SystemClock.elapsedRealtime() - started >= 53_000 }
            assertTrue("QR login must remain active while another app is foreground", vm.ui.value.loginActive)
            assertTrue("QR handshake needs its bounded foreground lease", foreground(context))
            assertArrayEquals(firstQr, vm.ui.value.qr)
            assertEquals("A saved QR must never be silently replaced", 1, requests.get())
            assertTrue(socket.get().send("""{"op":"loginsuccess","UserID":88,"Auth":"fixture-only","Name":"扫码测试"}"""))
            compose.waitUntil(10000) { !vm.ui.value.loginActive && vm.ui.value.data.sessionVerified && !vm.ui.value.busy }
            assertEquals("fixture-session-123", Vault(app).load().session)
            compose.waitUntil(5000) { !foreground(context) }
            returnToApp()
            compose.activityRule.scenario.recreate()
            assertSame(vm, ownerApplication.studyViewModel)
            assertEquals("fixture-session-123", vm.ui.value.data.session)
            compose.runOnIdle { vm.logout(); vm.startLogin() }
            compose.waitUntil(10000) { vm.ui.value.qr != null && requests.get() == 2 }
            val canceledSocket = socket.get()
            compose.runOnIdle { vm.cancelLogin() }
            compose.waitUntil(5000) { !foreground(context) }
            canceledSocket.send("""{"op":"loginsuccess","UserID":88,"Auth":"stale-fixture"}""")
            assertFalse(vm.ui.value.loginActive)
            assertNull(vm.ui.value.qr)
            assertTrue(vm.ui.value.data.session.isBlank())
            compose.runOnIdle { vm.startLogin() }
            compose.waitUntil(10000) { vm.ui.value.qr != null && requests.get() == 3 && foreground(context) }
            val stoppedSocket = socket.get()
            context.stopService(Intent(context, RainLoginService::class.java))
            compose.waitUntil(5000) { !vm.ui.value.loginActive && vm.ui.value.notice.contains("后台扫码连接已停止") }
            assertNull("An unexpected service stop must not leave a stale QR waiting", vm.ui.value.qr)
            assertTrue(vm.ui.value.notice.contains("后台扫码连接已停止"))
            stoppedSocket.send("""{"op":"loginsuccess","UserID":88,"Auth":"after-service-stop"}""")
            compose.activityRule.scenario.recreate()
            assertTrue(vm.ui.value.data.session.isBlank())
            assertFalse(vm.ui.value.loginActive)
        } finally {
            try { returnToApp() } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear(); ownerApplication.installClassFixture(originalOwner) }
            }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
