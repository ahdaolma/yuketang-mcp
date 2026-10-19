package cn.studybridge.mobile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun noteSummariesPreserveScientificComparisonSigns() {
        val preview = notePreview("# 阅读重点\n\n**pH > 7.4**，范围为 0 < x < 1。<p>继续比较。</p>")
        assertTrue(preview.contains("pH > 7.4")); assertTrue(preview.contains("0 < x < 1"))
        assertFalse(preview.contains("<p>")); assertFalse(preview.contains("**"))
    }
    @Test fun endpointKeepsCustomPrefixWithoutDuplicatingCompletionPath() {
        assertEquals("https://api.deepseek.com/chat/completions", aiEndpoint("https://api.deepseek.com/").toString())
        assertEquals("https://example.com/v1/chat/completions", aiEndpoint("https://example.com/v1/chat/completions/").toString())
        assertEquals("https://example.com/custom/v1/models", aiEndpoint("https://example.com/custom/v1", "models").toString())
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com?key=secret", "https://example.com/#fragment").forEach { assertTrue(runCatching { aiEndpoint(it) }.isFailure) }
    }
    @Test fun ssePreservesMultilineEventsAndFlushesAtEof() {
        val events = mutableListOf<String>()
        val parser = SseEvents(events::add)
        listOf(": ping", "data: first", "data: second", "", "data: [DONE]").forEach(parser::line)
        parser.line(null)
        assertEquals(listOf("first\nsecond", "[DONE]"), events)
    }
    @Test fun mediaAllowlistRejectsLookalikeAndCredentialUrls() {
        assertTrue(allowedMedia("https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=test-only"))
        assertFalse(allowedMedia("https://weixin.qq.com.evil.example/image.png"))
        assertFalse(allowedMedia("http://mp.weixin.qq.com/qr"))
        assertFalse(allowedMedia("https://user:pass@mp.weixin.qq.com/qr"))
        assertFalse(allowedMedia("https://mp.weixin.qq.com:8443/qr"))
    }
    @Test fun boundedResponsesReadShortBodiesAndRejectOversize() {
        fun response(text: String) = Response.Builder().request(Request.Builder().url("https://example.com").build()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
        response("short").use { assertArrayEquals("short".toByteArray(), readBounded(it, 5)) }
        response("too long").use { assertTrue(runCatching { readBounded(it, 5) }.isFailure) }
    }
    @Test fun onlyExplicitPresentationIdsAreSelected() {
        val data = JSONObject("""{"classroom_id":1,"courseware_id":2,"lesson_id":3,"nested":[{"presentation_id":123},{"presentationId":"456"},{"presentation_id":"invalid"}]}""")
        assertEquals(listOf("123", "456"), presentationRefs(data))
    }
    @Test fun localSerializationPreservesPromptsNotesAndProviderSelection() {
        val p = Provider(name = "Test", key = "test-only", model = "unit")
        val saved = Saved(providers = listOf(p), selectedProvider = p.id, notes = listOf(Note(title = "第一节", content = "# 笔记", source = "课件 · 第 2 页", model = "unit")), autoNotes = true, tutorPrompt = "自定义助教", notesPrompt = "自定义笔记")
        assertEquals(saved, savedFromJson(saved.toJson()))
    }
    @Test fun builtInOrCustomRolesAreSystemMessagesForBothTasks() {
        val history = listOf(Message("user", "题目", source = "课件 · 第 1 页"))
        assertEquals(TUTOR_PROMPT, tutorMessages("", history).first().text)
        val tutor = tutorMessages("自定义定位", history)
        assertEquals("system", tutor.first().role); assertEquals("自定义定位", tutor.first().text)
        assertEquals(history, tutor.drop(2))
        val notes = noteMessages("", history, "课件 · 第 1 页")
        assertEquals("system", notes.first().role); assertEquals(NOTES_PROMPT, notes.first().text)
        assertTrue(notes.last().text.contains("第 1 页"))
    }
    @Test fun streamedRequestsCarryRoleImageAndOnlyTheConfiguredKey() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("data: {\"choices\":[{\"delta\":{\"content\":\"学习\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"笔记\"}}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val p = Provider(key = "unit-test-key", model = "test-model", baseUrl = "https://example.com/v1", images = true)
        val input = tutorMessages("内置助教定位", listOf(Message("user", "解释这页", "data:image/png;base64,dGVzdA==", "课件 · 第 1 页")))
        val output = StringBuilder()
        AiApi(client).stream(p, input) { output.append(it) }
        assertEquals("学习笔记", output.toString())
        assertEquals("Bearer unit-test-key", captured!!.header("Authorization"))
        assertNull(captured!!.header("Cookie"))
        val buffer = okio.Buffer(); captured!!.body!!.writeTo(buffer)
        val b = JSONObject(buffer.readUtf8()); assertEquals("test-model", b.getString("model"))
        val messages = b.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("内置助教定位", messages.getJSONObject(0).getString("content"))
        val blocks = messages.getJSONObject(2).getJSONArray("content")
        assertTrue(blocks.getJSONObject(0).getString("text").contains("第 1 页"))
        assertEquals("image_url", blocks.getJSONObject(1).getString("type"))
    }
    @Test fun textOnlyModelRefusesImagesBeforeNetworkCall() = runBlocking {
        var called = false
        val client = OkHttpClient.Builder().addInterceptor { called = true; error("unexpected network") }.build()
        val result = runCatching { AiApi(client).stream(Provider(key = "test-only", images = false, model = "unit", baseUrl = "https://example.com/v1"), listOf(Message("user", "test", "data:image/png;base64,AA=="))) {} }
        assertTrue(result.isFailure); assertFalse(called)
    }
    @Test fun malformedKeyIsRejectedWithoutDisplayingTheKey() = runBlocking {
        val secret = "do-not-display\ninvalid-key"
        val result = runCatching { AiApi().stream(Provider(key = secret, model = "unit", baseUrl = "https://example.com/v1"), listOf(Message("user", "test"))) {} }
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message!!.contains("do-not-display"))
    }
}
