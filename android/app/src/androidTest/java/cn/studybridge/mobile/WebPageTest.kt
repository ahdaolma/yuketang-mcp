package cn.studybridge.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebPageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(finish: Boolean = true, draft: String = "", rich: Boolean = false): String = """
        <!doctype html><html><body><main id="turns"></main><form>
        <input type="file" accept="image/png" onchange="const i=document.createElement('img');i.alt='Uploaded image';this.after(i)">
        ${if (rich) "<div id=\"prompt-textarea\" contenteditable=\"true\">$draft</div>" else "<textarea id=\"prompt-textarea\">$draft</textarea>"}<button type="button" data-testid="send-button">Send</button></form>
        <script>document.querySelector('button').onclick=function(){
          const area=document.querySelector('#prompt-textarea'), turns=document.querySelector('main');
          const user=document.createElement('div');user.setAttribute('data-message-author-role','user');user.innerText=('value' in area ? area.value : area.innerText);turns.append(user);if('value' in area)area.value='';else area.innerText='';
          const article=document.createElement('article');article.innerHTML='<div data-message-author-role="assistant"><div class="markdown"><h2>知识点</h2><p><strong>重点</strong>说明。</p><table><tr><th>概念</th><th>解释</th></tr><tr><td>A</td><td>B</td></tr></table></div></div>';
          turns.append(article);
          ${if (finish) "article.innerHTML+='<button data-testid=\"copy-turn-action-button\">Copy</button>';" else "article.innerHTML+='<button data-testid=\"stop-button\">Stop</button>';"}
        };</script></body></html>
    """.trimIndent()
    private suspend fun withPage(html: String, block: suspend (ChatGptWebSession) -> Unit) {
        lateinit var page: ChatGptWebSession
        val scenario = ActivityScenario.launch(WebFixtureActivity::class.java)
        scenario.onActivity { activity ->
            page = ChatGptWebSession(activity, {}, {})
            activity.setContentView(page.view)
            page.view.loadDataWithBaseURL(CHATGPT_WEB, html, "text/html", "UTF-8", CHATGPT_WEB)
        }
        try {
            repeat(100) { if (page.progress != 100) delay(50) }
            block(page)
        } finally {
            scenario.onActivity { (page.view.parent as? android.view.ViewGroup)?.removeView(page.view); page.destroy() }
            scenario.close()
        }
    }
    @Test fun actualWebViewAttachesImageUsesVisibleSendAndExtractsStructuredMarkdown() = runBlocking {
        withPage(fixture()) { page ->
            val image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j1ioAAAAASUVORK5CYII="
            var beforeSend = false
            val answer = WebStudyDriver(page, { delay(50) }, 20).generate("请讲解", Attachment(image,"课件第 1 页","原文"), "RS-fixture") { beforeSend = true }
            assertTrue(beforeSend); assertTrue(answer.contains("## 知识点")); assertTrue(answer.contains("**重点**")); assertTrue(answer.contains("| 概念 | 解释 |"))
            assertEquals(answer, WebStudyDriver(page, { delay(10) }).capture("RS-fixture"))
            assertFalse(page.call("answer", JSONObject().put("marker", "another-page")).optBoolean("finished"))
        }
    }
    @Test fun learnerProfileFillsVisibleComposerWithoutSendingOrReplacingDraft() = runBlocking {
        for (rich in listOf(false, true)) withPage(fixture(rich = rich)) { page ->
            val prompt = learnerWebPrompt(TUTOR_PROMPT, "PROFILE-beginner：先解释术语")
            val driver = WebStudyDriver(page, { delay(50) }, 20)
            driver.fill(prompt, null)
            assertTrue(page.call("ready", JSONObject().put("marker", "PROFILE-beginner：先解释术语")).optBoolean("ready"))
            assertFalse(page.call("answer", JSONObject().put("marker", "PROFILE-beginner")).optBoolean("matchedUser"))
            assertTrue(runCatching { driver.fill("不可覆盖", null) }.isFailure)
            assertTrue(page.call("ready", JSONObject().put("marker", "PROFILE-beginner：先解释术语")).optBoolean("ready"))
        }
    }
    @Test fun localizedCompletionControlsAndNewTurnContainersCanBeRecoveredWithoutSendingAgain() = runBlocking {
        val pageHtml = """<!doctype html><html><body>
            <section data-turn="user">RS-localized</section>
            <section data-turn="assistant"><div class="markdown"><h2>完整讲解</h2><p>氨基酸与酶活性中心。</p></div>
                <div><button aria-label="复制">copy</button><button aria-label="差评">bad</button></div>
            </section><form><textarea id="prompt-textarea"></textarea><button data-testid="send-button">Send</button></form>
            </body></html>"""
        withPage(pageHtml) { page ->
            val driver = WebStudyDriver(page, { delay(50) }, 20)
            assertTrue(driver.capture("RS-localized").contains("## 完整讲解"))
            assertTrue(driver.awaitAnswer("RS-localized", requireCurrent = true).contains("酶活性中心"))
            assertTrue(runCatching { driver.capture("RS-another-page") }.isFailure)
        }
        withPage(pageHtml.replace("</form>", "<button aria-label=\"停止生成\">Stop</button></form>")) { page ->
            val status = page.call("answer", JSONObject().put("marker", "RS-localized"))
            assertEquals("generating", status.optString("phase")); assertFalse(status.optBoolean("finished"))
            assertTrue(runCatching { WebStudyDriver(page, { delay(10) }).capture("RS-localized") }.isFailure)
        }
    }
    @Test fun actualPageDraftIsPreservedAndIncompleteOutputCannotBeSaved() = runBlocking {
        withPage(fixture(draft = "我还没写完的问题")) { page ->
            assertFalse(page.call("prepare", JSONObject().put("text", "不得覆盖")).optBoolean("ok"))
        }
        withPage(fixture(finish = false)) { page ->
            assertTrue(runCatching { WebStudyDriver(page, { delay(10) }, 4).generate("解释", Attachment(null,"课件",""),"RS-test") {} }.isFailure)
            assertTrue(runCatching { WebStudyDriver(page, { delay(10) }).capture("RS-test") }.isFailure)
        }
    }
    @Test fun actualContentEditableComposerReceivesPromptAndKeepsPageMarker() = runBlocking {
        withPage(fixture(rich = true)) { page ->
            val text = WebStudyDriver(page, { delay(50) }, 20).generate("学习角色提示", Attachment(null,"第 2 页","原文"),"RS-rich") {}
            assertTrue(text.contains("知识点"))
        }
    }
    @Test fun humanAttachmentIsPreservedAndPendingUserCanBeFoundBeforeFirstToken() = runBlocking {
        withPage(fixture().replace("<form>", "<form><img alt='Uploaded image' src='data:image/png;base64,AA=='>")) { page ->
            assertFalse(page.call("prepare", JSONObject().put("text", "不得覆盖附件")).optBoolean("ok"))
        }
        withPage("""<!doctype html><html><body><article><div data-message-author-role="user">RS-waiting</div></article></body></html>""") { page ->
            val status = page.call("answer", JSONObject().put("marker", "RS-waiting"))
            assertTrue(status.optBoolean("matchedUser")); assertFalse(status.optBoolean("finished"))
            assertFalse(page.call("answer", JSONObject().put("marker", "RS-other")).optBoolean("matchedUser"))
        }
    }
}
