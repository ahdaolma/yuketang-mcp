package cn.studybridge.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private fun fixture(finish: Boolean = true, draft: String = ""): String = """
        <!doctype html><html><body><main id="turns"></main><form>
        <input type="file" accept="image/png" onchange="const i=document.createElement('img');i.alt='Uploaded image';this.after(i)">
        <textarea id="prompt-textarea">$draft</textarea><button type="button" data-testid="send-button">Send</button></form>
        <script>document.querySelector('button').onclick=function(){
          const area=document.querySelector('textarea'), turns=document.querySelector('main');
          const user=document.createElement('div');user.setAttribute('data-message-author-role','user');user.innerText=area.value;turns.append(user);area.value='';
          const article=document.createElement('article');article.innerHTML='<div data-message-author-role="assistant"><div class="markdown"><h2>知识点</h2><p><strong>重点</strong>说明。</p><table><tr><th>概念</th><th>解释</th></tr><tr><td>A</td><td>B</td></tr></table></div></div>';
          turns.append(article);
          ${if (finish) "article.innerHTML+='<button data-testid=\"copy-turn-action-button\">Copy</button>';" else "article.innerHTML+='<button data-testid=\"stop-button\">Stop</button>';"}
        };</script></body></html>
    """.trimIndent()
    private suspend fun withPage(html: String, block: suspend (ChatGptWebSession) -> Unit) {
        lateinit var page: ChatGptWebSession
        instrumentation.runOnMainSync {
            page = ChatGptWebSession(instrumentation.targetContext, {}, {})
            page.view.loadDataWithBaseURL(CHATGPT_WEB, html, "text/html", "UTF-8", null)
        }
        try {
            repeat(100) { if (page.progress != 100) delay(50) }
            block(page)
        } finally { instrumentation.runOnMainSync { page.destroy() } }
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
    @Test fun actualPageDraftIsPreservedAndIncompleteOutputCannotBeSaved() = runBlocking {
        withPage(fixture(draft = "我还没写完的问题")) { page ->
            assertFalse(page.call("prepare", JSONObject().put("text", "不得覆盖")).optBoolean("ok"))
        }
        withPage(fixture(finish = false)) { page ->
            assertTrue(runCatching { WebStudyDriver(page, { delay(10) }, 4).generate("解释", Attachment(null,"课件",""),"RS-test") {} }.isFailure)
            assertTrue(runCatching { WebStudyDriver(page, { delay(10) }).capture("RS-test") }.isFailure)
        }
    }
}
