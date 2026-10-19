package cn.studybridge.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import kotlin.coroutines.resume

const val CHATGPT_WEB = "https://chatgpt.com/"
const val WEB_PROVIDER_ID = "chatgpt-web"

fun isChatGptPage(url: String): Boolean = runCatching {
    val u = URI(url)
    u.scheme == "https" && u.host == "chatgpt.com" && u.userInfo == null && u.port in listOf(-1, 443)
}.getOrDefault(false)

fun allowedWebNavigation(url: String): Boolean = runCatching {
    val u = URI(url)
    u.scheme == "https" && u.userInfo == null && u.port in listOf(-1, 443) && u.host in setOf(
        "chatgpt.com", "auth.openai.com", "auth0.openai.com", "accounts.google.com",
        "appleid.apple.com", "login.microsoftonline.com", "login.live.com"
    )
}.getOrDefault(false)

// A durable send intent prevents resubmission after cancellation, navigation or process death.
data class WebPending(val marker: String, val label: String, val url: String = CHATGPT_WEB, val pageKey: String = "", val noteId: String = "", val noteSource: String = "") {
    fun json() = JSONObject().put("marker", marker).put("label", label).put("url", url).put("pageKey", pageKey).put("noteId", noteId).put("noteSource", noteSource)
}
fun webPendingFromJson(o: JSONObject) = WebPending(o.getString("marker"), o.getString("label"), webReturnUrl(o.optString("url", CHATGPT_WEB)), o.optString("pageKey"), o.optString("noteId"), o.optString("noteSource"))

fun recoverWebAnswer(saved: Saved, content: String, fallbackId: String = newId(), receivedAt: Long = System.currentTimeMillis()): Saved {
    require(content.isNotBlank())
    val pending = saved.webPending
    val note = pending?.noteId?.takeIf { it.isNotBlank() }?.let { id -> saved.notes.find { it.id == id } }
    val run = saved.classRun?.takeIf { it.noteId == pending?.noteId }
    if (note != null && pending != null) {
        if (run != null && pending.pageKey in run.completed) return saved.copy(webPending = null)
        require(run == null || run.ended || run.pending.any { it.key == pending.pageKey }) { "待保存页与课堂记录不一致，请先核对。" }
        val updated = run?.copy(completed = (run.completed + pending.pageKey).distinct(), pending = run.pending.filterNot { it.key == pending.pageKey })
        return saved.copy(notes = saved.notes.map { if (it.id == note.id) it.copy(content = it.content + "## ${pending.label}\n\n$content\n\n---\n\n", source = pending.noteSource.ifBlank { it.source }) else it }, classRun = updated ?: saved.classRun, webPending = null)
    }
    val label = pending?.label ?: "ChatGPT 网页笔记"
    return saved.copy(notes = listOf(Note(fallbackId, title = label, content = content, source = label, model = "ChatGPT 网页 · 网页中选择的模式", createdAt = receivedAt)) + saved.notes, webPending = null)
}

// Only bind a pending intent to a conversation that visibly contains its marker.
// Once bound, visiting another chat must never move the recovery link.
suspend fun pendingConversationUrl(pending: WebPending, page: WebPagePort): String? {
    if (webReturnUrl(pending.url) != CHATGPT_WEB) return null
    val safe = webReturnUrl(page.pageUrl)
    if (safe == CHATGPT_WEB) return null
    val answer = page.call("answer", JSONObject().put("marker", pending.marker))
    return safe.takeIf { answer.optBoolean("matchedUser") && !answer.optBoolean("otherUser") }
}

interface WebPagePort {
    val pageUrl: String get() = CHATGPT_WEB
    suspend fun call(action: String, payload: JSONObject = JSONObject()): JSONObject
}
fun webReturnUrl(url: String): String = runCatching {
    if (!isChatGptPage(url)) return CHATGPT_WEB
    val path = URI(url).path
    if (path.matches(Regex("/c/[a-fA-F0-9-]{16,64}"))) "https://chatgpt.com$path" else CHATGPT_WEB
}.getOrDefault(CHATGPT_WEB)

// No networking API, Cookie extraction or OAuth credential is used by this workflow.
class WebStudyDriver(private val page: WebPagePort, private val wait: suspend () -> Unit = { delay(1000) }, private val attempts: Int = 180) {
    suspend fun fill(prompt: String, attachment: Attachment?) {
        val mime = attachment?.image?.substringAfter("data:")?.substringBefore(';')
        val extension = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
        val payload = JSONObject().put("text", prompt).put("image", attachment?.image ?: "").put("fileName", "rain-slide.$extension")
        require(page.call("prepare", payload).optBoolean("ok")) { "网页输入框或附件入口不可用，或已有未发送的内容；请在网页检查。" }
    }
    suspend fun generate(prompt: String, attachment: Attachment, marker: String, onMatched: suspend () -> Unit = {}, beforeSend: suspend () -> Unit): String {
        fill("$prompt\n\n资料来源：${attachment.label}\n${attachment.text}\n\n本页对应标记：$marker", attachment)
        var ready = false
        for (i in 0 until 30) {
            wait()
            val s = page.call("ready", JSONObject().put("image", attachment.image != null).put("marker", marker))
            if (s.optBoolean("ready")) { ready = true; break }
        }
        check(ready) { "网页附件或发送按钮尚未就绪。请检查上传、登录或额度提示，再手动处理草稿。" }
        beforeSend() // Must reach encrypted storage BEFORE the only click.
        check(page.call("send", JSONObject().put("marker", marker)).optBoolean("sent")) { "网页未确认发送，请检查草稿；不会自动重复发送。" }
        var previous = ""; var stable = 0; var matched = false
        repeat(attempts) {
            wait()
            val s = page.call("answer", JSONObject().put("marker", marker))
            check(!matched || !s.optBoolean("otherUser")) { "网页对话发生变化，已暂停；请检查待完成回答。" }
            if (s.optBoolean("matchedUser")) { matched = true; onMatched() }
            val text = s.optString("markdown")
            stable = if (!s.optBoolean("otherUser") && s.optBoolean("finished") && text.isNotBlank() && text == previous) stable + 1 else 0
            previous = text
            if (stable >= 2) return text
        }
        error("未确认网页回答已完成，已暂停并保留待处理标记；请检查网页的登录、用量或生成状态。")
    }
    suspend fun capture(marker: String = ""): String {
        val first = page.call("answer", JSONObject().put("marker", marker))
        require(first.optBoolean("finished") && !first.optBoolean("otherUser")) { "没有找到对应的完整网页回答，或仍在生成；请核对当前对话。" }
        wait()
        val next = page.call("answer", JSONObject().put("marker", marker))
        val text = next.optString("markdown")
        require(next.optBoolean("finished") && !next.optBoolean("otherUser") && text.isNotBlank() && text == first.optString("markdown")) { "网页回答尚未稳定，请稍后再保存。" }
        return text
    }
}

@SuppressLint("SetJavaScriptEnabled")
class ChatGptWebSession(context: Context, private val changed: () -> Unit, private val external: (String) -> Unit) : WebPagePort {
    override val pageUrl get() = view.url ?: CHATGPT_WEB
    var progress = 0; private set
    var error = ""; private set
    private val script = context.assets.open("chatgpt-page.js").bufferedReader().use { it.readText() }
    val view = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (allowedWebNavigation(url)) return false
                if (request.hasGesture() && url.startsWith("https://")) external(url)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { this@ChatGptWebSession.error = ""; this@ChatGptWebSession.progress = 0; changed() }
            override fun onPageFinished(view: WebView, url: String?) { this@ChatGptWebSession.progress = 100; CookieManager.getInstance().flush(); changed() }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { changed() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                if (request.isForMainFrame) { this@ChatGptWebSession.error = "网页加载失败，请检查网络后重试，或在系统浏览器打开。"; this@ChatGptWebSession.progress = 100; changed() }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) { this@ChatGptWebSession.error = "网页响应失败（HTTP ${response.statusCode}）。可刷新，或在系统浏览器核对访问情况。"; changed() }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); this@ChatGptWebSession.error = "网页证书验证失败，连接已停止。"; changed() }
        }
    }
    fun load(url: String = CHATGPT_WEB) { require(allowedWebNavigation(url)); view.loadUrl(url) }
    fun loading(value: Int) { progress = value; changed() }
    override suspend fun call(action: String, payload: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        check(error.isBlank()) { error }
        check(isChatGptPage(view.url ?: "")) { "请先在学习页打开并登录 ChatGPT 网页，选择普通 Chat 对话。" }
        if (action == "prepare") {
            withTimeout(5000) { while (!view.isShown) delay(16) }
            view.requestFocus()
        }
        withTimeout(5000) { suspendCancellableCoroutine { continuation ->
            // The script checks window.location again. Nothing is injected into login or external origins.
            view.evaluateJavascript("(function(){if(location.protocol!=='https:'||location.hostname!=='chatgpt.com')return JSON.stringify({ok:false});$script;return JSON.stringify(rainStudyPage(${JSONObject.quote(action)},$payload));})()") { value ->
                if (continuation.isActive) continuation.resume(runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrElse { JSONObject() })
            }
        } }
    }
    fun destroy() { view.stopLoading(); view.webChromeClient = null; view.destroy() }
}
