package cn.studybridge.mobile

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Cloud-only synthetic transport checks; no WeChat/account credentials. */
class RainLoginDeadlineTest {
    private class ExchangeFixture : AutoCloseable {
        val exchangeStarted = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        val steps = CopyOnWriteArrayList<String>()
        val successes = AtomicInteger()
        val errorReceived = CountDownLatch(1)
        private val exchangeCall = AtomicReference<Call>()
        private val exchangeFailed = CountDownLatch(1)
        private val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/wsapp/" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            webSocket.send("""{"op":"loginsuccess","UserID":88,"Auth":"fixture-only"}""")
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    "/pc/web_login" -> {
                        exchangeStarted.countDown()
                        MockResponse().setHeadersDelay(3, TimeUnit.SECONDS)
                            .setHeader("Set-Cookie", "sessionid=fixture-delayed-session; Path=/; Secure; HttpOnly").setBody("{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        private val client = defaultRainClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    if (call.request().url.encodedPath == "/pc/web_login") exchangeCall.set(call)
                }
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call === exchangeCall.get()) exchangeFailed.countDown()
                }
            })
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build()
        fun login(timeoutMillis: Long = 180_000) = RainApi(client).login("standard",
            onQr = { fail("fixture sends confirmation directly") },
            onSuccess = { _, _ -> successes.incrementAndGet() },
            onError = { errors.add(it); errorReceived.countDown() },
            onStep = { steps.add(it) }, timeoutMillis = timeoutMillis)
        fun assertExchangeCanceledPromptly() {
            assertTrue("the actual pending session HTTP Call must be canceled", requireNotNull(exchangeCall.get()).isCanceled())
            assertTrue("cancel must interrupt HTTP I/O before the delayed response", exchangeFailed.await(1, TimeUnit.SECONDS))
        }
        fun awaitTransport() {
            client.dispatcher.executorService.shutdown()
            assertTrue("the delayed response must finish before checking late callbacks", client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS))
        }
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun deadlineStillEndsWaitingAfterWechatConfirmationWhileSessionExchangeIsPending() {
        ExchangeFixture().use { fixture ->
            fixture.login(timeoutMillis = 1_500).use {
                assertTrue("fixture confirmation must reach the session exchange", fixture.exchangeStarted.await(5, TimeUnit.SECONDS))
                assertTrue("confirmation must not disable the bounded login deadline", fixture.errorReceived.await(2, TimeUnit.SECONDS))
                fixture.assertExchangeCanceledPromptly()
                fixture.awaitTransport()
                assertEquals(1, fixture.errors.size)
                assertTrue(fixture.errors.single().contains("等待已结束"))
                assertEquals(0, fixture.successes.get())
                assertFalse(fixture.steps.any { it.startsWith("已取得雨课堂登录记录") })
            }
        }
    }

    @Test fun cancelDuringSessionExchangeSuppressesLateSuccessAndProgress() {
        ExchangeFixture().use { fixture ->
            val login = fixture.login()
            try {
                assertTrue(fixture.exchangeStarted.await(5, TimeUnit.SECONDS))
                login.close()
                fixture.assertExchangeCanceledPromptly()
                val canceledSteps = fixture.steps.toList()
                fixture.awaitTransport()
                assertEquals(canceledSteps, fixture.steps.toList())
                assertTrue(fixture.errors.isEmpty())
                assertEquals(0, fixture.successes.get())
            } finally { login.close() }
        }
    }
}
