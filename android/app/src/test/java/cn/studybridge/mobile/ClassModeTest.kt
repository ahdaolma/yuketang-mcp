package cn.studybridge.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClassModeTest {
    @Test fun confirmationSurvivesRestartBeforeProfileVerification() {
        val received = rememberLogin(Saved(), "standard", "unit-session-123", "已确认账号")
        assertFalse(received.sessionVerified)
        val restarted = savedFromJson(received.toJson())
        assertEquals("unit-session-123", restarted.session)
        assertTrue(loginStatusText(restarted, false, "").contains("待验证"))
        assertFalse(verifyRememberedLogin(restarted, "stale-session", "旧账号").sessionVerified)
        assertTrue(verifyRememberedLogin(restarted, restarted.session, "测试账号").sessionVerified)
    }
    @Test fun lessonActivityIsResolvedWithoutTreatingLessonAsPresentation() {
        val activities = JSONArray("""[{"type":14,"courseware_id":42,"id":7,"title":"遗传学"},{"type":19,"courseware_id":99},{"presentation_id":123,"title":"课件"}]""")
        val list = activityPresentations(activities)
        assertEquals(2, list.size)
        assertTrue(list[0].isLesson); assertEquals("42", list[0].lessonId)
        assertEquals("lesson:42", list[0].id)
        assertEquals("123", list[1].id)
        assertEquals(listOf("456"), presentationRefs(JSONObject("""{"content":"{\"presentation_id\":456}"}""")))
    }
    @Test fun reportUsesItsOwnEndpointsAndPreservesVisiblePageNumbers() {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); paths.add(r.url.encodedPath)
            assertEquals("sessionid=test-session-123", r.header("Cookie"))
            val data = when {
                r.url.encodedPath.endsWith("lesson-info") -> """{"presentationIds":[987],"lessonName":"细胞遗传"}"""
                else -> {
                    assertEquals("42", r.url.queryParameter("lesson_id")); assertEquals("987", r.url.queryParameter("presentationId"))
                    """{"slideList":[{"index":2,"cover":"https://rain.yuketang.cn/2.png","visible":true},{"index":3,"cover":"https://rain.yuketang.cn/3.png","visible":false},{"index":5,"text":"已开放文字","visible":true}]}"""
                }
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{\"code\":0,\"data\":$data}".toResponseBody()).build()
        }.build()
        val api = RainApi(client); val s = Saved(session = "test-session-123")
        val p = api.lessonPresentations(s, Presentation("lesson:42", "本节", "42", true)).single()
        val slides = api.slides(s, p.id, p.lessonId).second
        assertEquals(listOf(2, 5), slides.map { it.page })
        assertEquals(listOf("/api/v3/classroom-report/student/lesson-info", "/api/v3/classroom-report/student/ppt"), paths)
        assertEquals(listOf(2, 5), selectNotePages("2", "5", slides).map { it.page })
    }
    @Test fun teacherFramesTrackPresentationChangesAndIgnoreNonNavigation() {
        val frames = LessonFrames()
        assertEquals(listOf(LivePage("12", 1), LivePage("12", 2)), frames.pages(JSONObject("""{"op":"hello","timeline":[{"type":"slide","pres":12,"si":1},{"type":"slide","pres":12,"si":2}],"presentation":12,"slideindex":2}""")))
        assertEquals(listOf(LivePage("12", 3)), frames.pages(JSONObject("""{"op":"slidenav","slide":{"si":3}}""")))
        assertEquals(listOf(LivePage("13", 1)), frames.pages(JSONObject("""{"op":"showpresentation","presentation":13,"slideindex":1}""")))
        assertTrue(frames.pages(JSONObject("""{"op":"newdanmu","presentation":999,"slideindex":4}""")).isEmpty())
        assertTrue(frames.pages(JSONObject("""{"op":"slide","slideindex":0}""")).isEmpty())
    }
    @Test fun repeatedPagesAndReconnectDoNotDuplicateCompletedOrQueuedPages() {
        val page1 = LivePage("12", 1); val page2 = LivePage("12", 2)
        val run = ClassRun(LiveLesson("42", "课程", "7"), providerId = "model", completed = listOf(page1.key), pending = listOf(page2))
        val merged = enqueueClassPages(run, listOf(page1, page2, page2, LivePage("13", 1)))
        assertEquals(listOf(page2, LivePage("13", 1)), merged.pending)
        val restored = savedFromJson(Saved(classRun = merged).toJson()).classRun!!
        assertEquals(merged, restored)
        assertEquals(restored, enqueueClassPages(restored, listOf(page1, page2)))
    }
    @Test fun notebookFailureAndCancellationRetainOnlyCompletedCheckpoints() = runBlocking {
        val checkpoints = mutableListOf<NotebookProgress>()
        val pages = (1..3).map { Slide(it, "", "原文 $it") }
        var count = 0
        val failed = runCatching { buildNotebook("课程", pages, { Attachment(null, "第 ${it.page} 页", it.text) }, { if (++count == 2) error("模拟网络失败"); "有效讲解" }, { checkpoints.add(it) }) }
        assertTrue(failed.isFailure); assertEquals(1, checkpoints.size)
        assertTrue(checkpoints.single().markdown.contains("已完成 1/3"))
        assertFalse(checkpoints.single().markdown.contains("## 第 2 页"))
        count = 0; checkpoints.clear()
        val canceled = runCatching { buildNotebook("课程", pages, { Attachment(null, "页码", it.text) }, { if (++count == 2) throw CancellationException(); "讲解" }, { checkpoints.add(it) }) }
        assertTrue(canceled.exceptionOrNull() is CancellationException); assertEquals(1, checkpoints.size)
        assertTrue(runCatching { selectNotePages("3", "1", pages) }.isFailure)
        assertTrue(runCatching { selectNotePages("1", "51", (1..51).map { Slide(it, "", "") }) }.isFailure)
    }
    @Test fun batcherCoalescesTokenBurstsAndFlushesFinalRemainder() {
        var time = 1L; val output = mutableListOf<String>()
        val batch = TextBatcher({ time }, output::add)
        batch.append("首"); batch.append("页"); batch.append("讲解")
        assertEquals(listOf("首"), output)
        time += 90_000_000L; batch.append("内容"); batch.append("结尾"); batch.flush()
        assertEquals(listOf("首", "页讲解内容", "结尾"), output)
    }
    @Test fun responsesStreamsRequireCompletionAndUseImageInputWithoutCookies() = runBlocking {
        var terminal = "response.completed"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); assertEquals("/v1/responses", r.url.encodedPath); assertNull(r.header("Cookie"))
            val buffer = okio.Buffer(); r.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
            assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream")); assertTrue(body.getString("instructions").contains("定位"))
            assertEquals("input_image", body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
            val events = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"笔记\"}\n\n" + if (terminal.isEmpty()) "" else "data: {\"type\":\"$terminal\"}\n\n"
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(events.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val p = Provider(baseUrl = "https://api.openai.com/v1", key = "unit-test", model = "chosen-by-user", protocol = "responses")
        val messages = listOf(Message("system", "定位"), Message("user", "解释", "data:image/png;base64,AA=="))
        val out = StringBuilder(); AiApi(client).stream(p, messages) { out.append(it) }; assertEquals("笔记", out.toString())
        terminal = "response.failed"; assertTrue(runCatching { AiApi(client).stream(p, messages) {} }.isFailure)
        terminal = ""; assertTrue(runCatching { AiApi(client).stream(p, messages) {} }.isFailure)
    }
    @Test fun chatStreamCannotSilentlyAcceptTruncatedContent() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { c -> Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("data: {\"choices\":[{\"delta\":{\"content\":\"半段\"}}]}\n\n".toResponseBody("text/event-stream".toMediaType())).build() }.build()
        assertTrue(runCatching { AiApi(client).stream(Provider(key = "test-only"), listOf(Message("user", "解释"))) {} }.isFailure)
    }
}
