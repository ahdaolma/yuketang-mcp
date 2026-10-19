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
        var persisted = false; var answers = 0
        override suspend fun call(action: String, payload: JSONObject): JSONObject {
            actions.add(action)
            return when (action) {
                "prepare" -> JSONObject().put("ok", prepare)
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
}
