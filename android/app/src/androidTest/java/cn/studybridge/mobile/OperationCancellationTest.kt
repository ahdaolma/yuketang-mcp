package cn.studybridge.mobile

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Cloud-only transport and ViewModel checks; fixtures use no real account. */
class OperationCancellationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private class Transport : AutoCloseable {
        private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val calls = CopyOnWriteArrayList<Call>()
        val bodyCalls = CopyOnWriteArrayList<Call>()
        val bodyStarted = CountDownLatch(1)
        val client: OkHttpClient = defaultRainClient().newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls.add(call) }
            })
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = requireNotNull(response.body)
                var started = false
                // OkHttp's responseBodyStart fires after the first socket read
                // returns. Signal before that read, while the delayed body is
                // still blocked, so cancellation cannot race an already-done call.
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (!started) { started = true; bodyCalls.add(chain.call()); bodyStarted.countDown() }
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }
            .build()
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun stopInterruptsRainBodyAndAllowsNewTaskWithoutLateDeckMutation() {
        Transport().use { transport ->
            transport.server.enqueue(MockResponse().setBody("""{"code":0,"data":{"title":"晚到课件","slides":[{"index":1,"text":"旧内容"}]}}""").setBodyDelay(4, TimeUnit.SECONDS))
            transport.server.enqueue(MockResponse().setBody("""{"code":0,"data":{"title":"新课件","slides":[{"index":1,"text":"新内容"}]}}"""))
            val app = noteTestApplication(compose.activity.applicationContext)
            Vault(app).save(Saved(session = "fixture-session-123", sessionVerified = true))
            val store = ViewModelStore(); lateinit var vm: StudyViewModel
            try {
                compose.runOnUiThread { vm = noteTestViewModel(app, store, RainApi(transport.client)) }
                compose.setContent { Text("取消资料读取") }; compose.waitUntil(5000) { vm.ui.value.ready }
                compose.runOnIdle { vm.loadSlides("12") }
                assertTrue(transport.bodyStarted.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { vm.stop() }
                compose.waitUntil(1000) { !vm.ui.value.busy }
                assertTrue(transport.calls.first().isCanceled())
                compose.runOnIdle { vm.loadSlides("13") }
                compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentationId == "13" }
                assertEquals("新课件", vm.ui.value.slideTitle)
                assertEquals("新内容", vm.ui.value.slides.single().text)
                assertFalse(transport.calls.last().isCanceled())
            } finally { compose.runOnIdle { store.clear() } }
        }
    }

    @Test fun stopCancelsModelHeadersAndBodyAndSessionVerificationWithoutLateResults() {
        Transport().use { transport ->
            transport.server.enqueue(MockResponse().setBody("""{"data":[{"id":"late-model"}]}""").setHeadersDelay(4, TimeUnit.SECONDS))
            transport.server.enqueue(MockResponse().setBody("""{"code":0,"data":{"id":88,"name":"晚到账号"}}""").setBodyDelay(4, TimeUnit.SECONDS))
            transport.server.enqueue(MockResponse().setBody("""{"data":[{"id":"late-body-model"}]}""").setBodyDelay(4, TimeUnit.SECONDS))
            transport.server.enqueue(MockResponse().setBody("""{"data":[{"id":"current-model"}]}"""))
            val p = Provider("cancel-fixture", "模型", "https://fixture.example/v1", "fixture-key", "fixture-model")
            val app = noteTestApplication(compose.activity.applicationContext)
            Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id, session = "fixture-session-123", sessionVerified = false))
            val store = ViewModelStore(); lateinit var vm: StudyViewModel
            try {
                compose.runOnUiThread { vm = noteTestViewModel(app, store, RainApi(transport.client), AiApi(transport.client)) }
                compose.setContent { Text("取消模型和账号验证") }; compose.waitUntil(5000) { vm.ui.value.ready }
                compose.runOnIdle { vm.testProvider(p) }
                assertNotNull(transport.server.takeRequest(5, TimeUnit.SECONDS))
                compose.runOnIdle { vm.stop() }
                compose.waitUntil(1000) { !vm.ui.value.busy }
                assertTrue(transport.calls.first().isCanceled()); assertTrue(vm.ui.value.modelList.isEmpty())
                compose.runOnIdle { vm.verifyLogin() }
                assertNotNull(transport.server.takeRequest(5, TimeUnit.SECONDS))
                assertTrue(transport.bodyStarted.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { vm.stop() }
                compose.waitUntil(1000) { !vm.ui.value.busy }
                assertTrue(transport.calls[1].isCanceled()); assertFalse(vm.ui.value.data.sessionVerified)
                compose.runOnIdle { vm.testProvider(p) }
                compose.waitUntil(5000) { transport.bodyCalls.size >= 2 }
                compose.runOnIdle { vm.stop() }
                compose.waitUntil(1000) { !vm.ui.value.busy }
                assertTrue(transport.calls[2].isCanceled()); assertTrue(vm.ui.value.modelList.isEmpty())
                compose.runOnIdle { vm.testProvider(p) }
                compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.modelList == listOf("current-model") }
                assertFalse(vm.ui.value.data.sessionVerified)
            } finally { compose.runOnIdle { store.clear() } }
        }
    }

    @Test fun cancellingOneRainReadLeavesAnotherReadersCallAlive() = runBlocking {
        Transport().use { transport ->
            transport.server.enqueue(MockResponse().setBody("""{"code":0,"data":{"title":"停止的读取","slides":[]}}""").setBodyDelay(4, TimeUnit.SECONDS))
            transport.server.enqueue(MockResponse().setBody("""{"code":0,"data":{"title":"独立预览","slides":[]}}""").setBodyDelay(1, TimeUnit.SECONDS))
            val api = RainApi(transport.client)
            val saved = Saved(session = "fixture-session-123")
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { api.read { slides(saved, "12") } }
            assertNotNull(transport.server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(transport.bodyStarted.await(5, TimeUnit.SECONDS))
            val preview = async(start = CoroutineStart.UNDISPATCHED) { api.read { slides(saved, "13") } }
            assertNotNull(transport.server.takeRequest(5, TimeUnit.SECONDS))
            stopped.cancelAndJoin()
            assertTrue(transport.calls.first().isCanceled())
            assertFalse(transport.calls.last().isCanceled())
            assertEquals("独立预览", preview.await().first)
        }
    }

    @Test fun closingOrBackingOutCancelsInitialAttachmentAndExplanationReadsBeforeGeneration() {
        compose.setContent { Text("读取阶段的资料取消") }
        for (explain in listOf(false, true)) {
            val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
            val generations = AtomicInteger()
            val imageClient = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: java.io.IOException) { finished.countDown() }
            }).addInterceptor { chain ->
                started.countDown(); check(release.await(30, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(byteArrayOf().toResponseBody()).build()
            }.build()
            val p = Provider("page-cancel-fixture", "模型", "https://fixture.example/v1", "fixture-key", "fixture-model", images = true)
            val app = noteTestApplication(compose.activity.applicationContext)
            Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id, autoNotes = true))
            val store = ViewModelStore(); lateinit var vm: StudyViewModel
            try {
                compose.runOnUiThread { vm = noteTestViewModel(app, store, RainApi(imageClient), fixtureAiApi("已完成解释", inspect = { generations.incrementAndGet() })) }
                compose.waitUntil(5000) { vm.ui.value.ready }
                compose.runOnIdle {
                    vm.openMaterials(); vm.materialPane("pages")
                    val page = Slide(1, "https://rain.yuketang.cn/late.png", "原页文字")
                    if (explain) vm.explainPage(page) else vm.attach(page)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { if (explain) vm.backMaterials() else vm.closeMaterials() }
                compose.waitUntil(1000) { !vm.ui.value.busy }
                assertNull(vm.ui.value.pending); assertEquals(0, generations.get()); assertTrue(vm.ui.value.data.notes.isEmpty())
                release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { assertNull(vm.ui.value.pending); assertEquals(0, generations.get()) }
                // The successful transition releases browser ownership before its
                // own close, so explicitly started explanation remains alive.
                compose.runOnIdle { vm.explainPage(Slide(2, "", "继续解释的正文")) }
                compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 1 }
                assertEquals(1, generations.get())
            } finally { release.countDown(); compose.runOnIdle { store.clear() }; imageClient.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun delayedJsonBodyCannotAppendToANewAnswerAfterStop() {
        compose.setContent { Text("停止后的晚到 JSON") }
        for (protocol in listOf("chat", "responses")) {
            val started = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
            val requests = AtomicInteger()
            fun json(text: String) = if (protocol == "responses") """{"status":"completed","output":[{"content":[{"type":"output_text","text":"$text"}]}]}"""
                else """{"choices":[{"message":{"content":"$text"}}]}"""
            val media = "application/json".toMediaType()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val response = if (requests.incrementAndGet() == 1) {
                    val bytes = json("晚到的旧回答").toByteArray()
                    val delayed = object : ForwardingSource(Buffer().write(bytes)) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            started.countDown(); check(release.await(30, TimeUnit.SECONDS))
                            return super.read(sink, byteCount)
                        }
                        override fun close() { try { super.close() } finally { closed.countDown() } }
                    }.buffer()
                    object : ResponseBody() {
                        override fun contentType() = media
                        override fun contentLength() = bytes.size.toLong()
                        override fun source() = delayed
                    }
                } else json("当前问题的回答").toResponseBody(media)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type", "application/json").body(response).build()
            }.build()
            val p = Provider("json-cancel-fixture", "模型", "https://fixture.example/v1", "fixture-key", "fixture-model", protocol = protocol)
            val app = noteTestApplication(compose.activity.applicationContext)
            Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id))
            val store = ViewModelStore(); lateinit var vm: StudyViewModel
            try {
                compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = AiApi(client)) }
                compose.waitUntil(5000) { vm.ui.value.ready }
                compose.runOnIdle { assertTrue(vm.send("旧问题")) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                compose.runOnIdle { vm.stop() }; compose.waitUntil(1000) { !vm.ui.value.busy }
                compose.runOnIdle { assertTrue(vm.send("当前问题")) }
                compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.data.chat().messages.lastOrNull()?.text == "当前问题的回答" }
                release.countDown(); assertTrue(closed.await(5, TimeUnit.SECONDS))
                compose.runOnIdle {
                    assertEquals(listOf("当前问题的回答"), vm.ui.value.data.chat().messages.filter { it.role == "assistant" }.map { it.text })
                    assertFalse(vm.ui.value.busy)
                }
            } finally { release.countDown(); compose.runOnIdle { store.clear() }; client.dispatcher.executorService.shutdownNow() }
        }
    }
}
