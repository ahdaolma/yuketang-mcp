package cn.studybridge.mobile

import android.app.Application
import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RainCourseCacheTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun switchingTabsUsesCachedAccountAndCoursesAndExplicitRefreshValidatesOnlyWhenNeeded() {
        val directory = File(compose.activity.cacheDir, "course-cache-${newId()}")
        val app = object : Application() { init { attachBaseContext(object : ContextWrapper(compose.activity.applicationContext) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }) } }
        Vault(app).save(Saved(session = "fixture-session-123", username = "合成账号", sessionVerified = false))
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val profiles = AtomicInteger(); val courses = AtomicInteger()
        val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/v3/user/basic-info" -> { profiles.incrementAndGet(); MockResponse().setBody("""{"code":0,"data":{"id":88,"name":"合成账号"}}""") }
                "/v2/api/web/courses/list" -> { courses.incrementAndGet(); MockResponse().setBody("""{"code":0,"data":{"list":[{"classroom_id":123,"course":{"name":"遗传学"}}]}}""") }
                else -> MockResponse().setResponseCode(404)
            } }
            start()
        }
        val client = defaultRainClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        lateinit var vm: StudyViewModel
        try {
            compose.setContent { StudyTheme(false) { Text("课程缓存回归测试") } }
            compose.runOnIdle { vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = StudyViewModel(app, RainApi(client)) as T
            })[StudyViewModel::class.java] }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { repeat(4) { vm.tab(1); vm.tab(0); vm.tab(2) }; vm.tab(1) }
            assertFalse(vm.ui.value.busy)
            assertEquals(0, server.requestCount)
            compose.runOnIdle { vm.loadCourses() }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.courses.size == 1 }
            assertEquals(1, profiles.get()); assertEquals(1, courses.get())
            val cached = vm.ui.value.courses
            compose.runOnIdle { repeat(10) { vm.tab(0); vm.tab(2); vm.tab(1) } }
            assertEquals(cached, vm.ui.value.courses)
            assertTrue(vm.ui.value.data.sessionVerified)
            assertFalse(vm.ui.value.busy)
            assertEquals(2, server.requestCount)
            compose.runOnIdle { vm.loadCourses() }
            compose.waitUntil(5000) { !vm.ui.value.busy && courses.get() == 2 }
            assertEquals(1, profiles.get())
            compose.runOnIdle { vm.verifyLogin() }
            compose.waitUntil(5000) { !vm.ui.value.busy && profiles.get() == 2 }
            assertEquals(cached, vm.ui.value.courses)
        } finally {
            compose.runOnIdle { store.clear() }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown()
        }
    }
}
