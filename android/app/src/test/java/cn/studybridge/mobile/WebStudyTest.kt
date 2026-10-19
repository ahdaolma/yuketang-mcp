package cn.studybridge.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebStudyTest {
    @Test fun webIsDefaultAndOldConfigurationAndPendingIntentSurviveRestart() {
        val p = Provider(name = "已有配置", key = "test-only", model = "saved-model")
        val pending = WebPending("RS-test", "第 2 页", "https://chatgpt.com/c/01234567-89ab-cdef", "42:2", "note")
        val saved = savedFromJson(Saved(providers = listOf(p), webPending = pending).toJson())
        assertTrue(saved.usesWeb); assertEquals(p, saved.provider()); assertEquals(pending, saved.webPending)
        assertFalse(savedFromJson(saved.copy(learningMode = "native").toJson()).usesWeb)
        assertTrue(savedFromJson(saved.toJson().apply { remove("learningMode") }).usesWeb)
    }
    @Test fun onlyExactHttpsOriginsCanHostControlsAndReturnUrlsCannotCarryTokens() {
        assertTrue(isChatGptPage("https://chatgpt.com/c/01234567-89ab-cdef"))
        for (u in listOf("http://chatgpt.com/", "https://chatgpt.com.evil.test/", "https://chatgpt.com@evil.test/", "https://user@chatgpt.com/", "https://chatgpt.com:444/", "file:///private")) {
            assertFalse(u, isChatGptPage(u)); assertFalse(u, allowedWebNavigation(u))
        }
        assertTrue(allowedWebNavigation("https://accounts.google.com/login"))
        assertFalse(isChatGptPage("https://accounts.google.com/login"))
        assertEquals("https://chatgpt.com/c/01234567-89ab-cdef", webReturnUrl("https://chatgpt.com/c/01234567-89ab-cdef?token=never-save#secret"))
        assertEquals(CHATGPT_WEB, webReturnUrl("https://chatgpt.com/auth/callback?code=secret"))
    }
    private class Page(var finish: Boolean = true, var foreign: Boolean = false, var prepare: Boolean = true) : WebPagePort {
        val actions = mutableListOf<String>()
        var persisted = false; var answers = 0; var fileName = ""
        override suspend fun call(action: String, payload: JSONObject): JSONObject {
            actions.add(action)
            return when (action) {
                "prepare" -> { fileName = payload.optString("fileName"); JSONObject().put("ok", prepare) }
                "ready" -> JSONObject().put("ready", true)
                "send" -> { check(persisted); JSONObject().put("sent", true) }
                "answer" -> JSONObject().put("finished", finish).put("otherUser", foreign).put("markdown", if (++answers == 1) "部分内容" else "# 完整笔记\n\n有效讲解")
                else -> error("Unexpected action")
            }
        }
    }
    @Test fun sendsExactlyOnceAfterDurableIntentAndWaitsForStableCompleteAnswer() = runBlocking {
        val page = Page()
        val result = WebStudyDriver(page, {}, 8).generate("角色提示", Attachment(null, "课件第 1 页", "原文"), "RS-test") { page.persisted = true }
        assertTrue(result.startsWith("# 完整笔记")); assertEquals(1, page.actions.count { it == "send" }); assertTrue(page.answers >= 4)
    }
    @Test fun neverResendsOnTruncationChangedConversationOrCanceledPolling() = runBlocking {
        for (p in listOf(Page(finish = false), Page(foreign = true))) {
            assertTrue(runCatching { WebStudyDriver(p, {}, 5).generate("提示", Attachment(null, "课件", ""), "RS-test") { p.persisted = true } }.isFailure)
            assertEquals(1, p.actions.count { it == "send" })
        }
        val p = Page(); var ticks = 0
        val failed = runCatching { WebStudyDriver(p, { if (++ticks > 1) throw CancellationException() }, 5).generate("提示", Attachment(null, "课件", ""), "RS-test") { p.persisted = true } }
        assertTrue(failed.exceptionOrNull() is CancellationException); assertEquals(1, p.actions.count { it == "send" })
    }
    @Test fun preservesHumanDraftAndDoesNotPersistOrClickIfPreparationFails() = runBlocking {
        val p = Page(prepare = false)
        assertTrue(runCatching { WebStudyDriver(p, {}, 3).generate("提示", Attachment(null, "课件", ""), "RS-test") { p.persisted = true } }.isFailure)
        assertFalse(p.persisted); assertFalse("send" in p.actions)
    }
    @Test fun recoveryRequiresCorrespondingFinishedStableAnswer() = runBlocking {
        val p = Page(); p.answers = 5
        assertTrue(WebStudyDriver(p, {}).capture("RS-test").contains("完整笔记"))
        p.foreign = true
        assertTrue(runCatching { WebStudyDriver(p, {}).capture("RS-test") }.isFailure)
        p.foreign = false; p.finish = false
        assertTrue(runCatching { WebStudyDriver(p, {}).capture("RS-test") }.isFailure)
        assertFalse("send" in p.actions)
    }
    @Test fun uploadFilenameMatchesItsRealImageFormat() = runBlocking {
        val p = Page()
        WebStudyDriver(p, {}).fill("解释", Attachment("data:image/jpeg;base64,dGVzdA==", "课件", ""))
        assertEquals("rain-slide.jpg", p.fileName)
    }
    @Test fun recoveryAfterEndingClassAppendsOnceAndDoesNotTouchAnotherClass() {
        val page = LivePage("42", 2)
        val run = ClassRun(LiveLesson("1", "课程", "1"), providerId = WEB_PROVIDER_ID, ended = true)
        val note = Note(run.noteId, "课堂笔记", "已完成内容\n", "课堂", "网页")
        val pending = WebPending("RS-test", "第 2 页", pageKey = page.key, noteId = note.id)
        val saved = Saved(notes = listOf(note), classRun = run, webPending = pending)
        val recovered = recoverWebAnswer(saved, "新讲解")
        assertNull(recovered.webPending); assertTrue(recovered.notes.single().content.contains("新讲解"))
        assertEquals(listOf(page.key), recovered.classRun!!.completed)
        assertEquals(recovered.notes, recoverWebAnswer(recovered.copy(webPending = pending), "不得重复追加").notes)
        val other = ClassRun(LiveLesson("2", "另一课", "2"), providerId = WEB_PROVIDER_ID)
        val separate = recoverWebAnswer(saved.copy(classRun = other), "旧课堂的回答")
        assertEquals(other, separate.classRun); assertTrue(separate.notes.single().content.contains("旧课堂的回答"))
    }
    @Test fun olderUnfinishedNativeClassCanReturnToItsOriginalModeAfterUpgrade() {
        val run = ClassRun(LiveLesson("1", "课程", "1"), providerId = "existing-provider")
        val migrated = Saved(classRun = run)
        assertTrue(migrated.usesWeb); assertTrue(migrated.canUseLearningMode("native"))
        assertFalse(migrated.canUseLearningMode("web"))
        val web = migrated.copy(classRun = run.copy(providerId = WEB_PROVIDER_ID))
        assertTrue(web.canUseLearningMode("web")); assertFalse(web.canUseLearningMode("native"))
        assertTrue(migrated.copy(classRun = run.copy(ended = true)).canUseLearningMode("web"))
    }
}
