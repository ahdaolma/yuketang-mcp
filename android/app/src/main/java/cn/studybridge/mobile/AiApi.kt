package cn.studybridge.mobile

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun aiEndpoint(raw: String, operation: String = "chat/completions"): HttpUrl {
    val u = raw.trim().toHttpUrlOrNull() ?: error("API 地址格式不正确。")
    require(u.isHttps && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null) { "请填写不含凭据或参数的 HTTPS API 地址。" }
    val base = u.toString().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/responses").trimEnd('/')
    return (base + "/" + operation).toHttpUrlOrNull() ?: error("API 地址格式不正确。")
}

// Preserve whole SSE events, including multiple data lines and a final unterminated event.
class SseEvents(private val accept: (String) -> Unit) {
    private val data = mutableListOf<String>()
    fun line(value: String?) {
        if (value == null || value.isEmpty()) {
            if (data.isNotEmpty()) { accept(data.joinToString("\n")); data.clear() }
        } else if (value.startsWith("data:")) data.add(value.substring(5).removePrefix(" "))
    }
}

class AiApi(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()) {
    private fun checkKey(key: String) { require(key.isNotBlank() && key.none { it.isISOControl() || it.isWhitespace() }) { "API Key 为空或包含空白字符，请重新粘贴完整密钥。" } }
    suspend fun stream(p: Provider, messages: List<Message>, onText: (String) -> Unit) {
        require(p.key.isNotBlank() && p.model.isNotBlank()) { "请先在模型设置中填写 API Key 和模型名称。" }
        validateProviderDestination(p)
        checkKey(p.key)
        require(p.images || messages.none { it.image != null }) { "当前模型未开启图片输入。请切换支持图片的模型，或新建纯文字对话。" }
        val responses = p.protocol == "responses"
        val body = if (responses) responseBody(p, messages) else JSONObject().put("model", p.model).put("stream", true).put("messages", JSONArray(messages.map { m ->
            val text = if (m.source == null) m.text else "来源：${m.source}\n${m.text}"
            val content: Any = m.image?.let { JSONArray().put(JSONObject().put("type", "text").put("text", text)).put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", it))) } ?: text
            JSONObject().put("role", m.role).put("content", content)
        }))
        val request = Request.Builder().url(aiEndpoint(p.baseUrl, if (responses) "responses" else "chat/completions")).header("Authorization", "Bearer ${p.key.trim()}").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("模型连接失败，请检查 API 地址和网络。")) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (!r.isSuccessful && p.chatgptAccountId.isNotBlank()) throw chatGptResponseFailure(r)
                            check(r.isSuccessful) { when (r.code) { 401, 403 -> if (p.chatgptAccountId.isNotBlank()) "ChatGPT 授权已失效或模型无权限，请重新登录或重新选择模型。" else "模型鉴权失败，请检查 API Key。"; 429 -> if (p.chatgptAccountId.isNotBlank()) "ChatGPT 套餐或本应用额度受限，请点击“管理用量”检查。" else "模型请求受限或余额不足，请在服务商处核对。"; else -> "模型返回 HTTP ${r.code}，请核对地址和模型名称。" } }
                            val source = r.body?.source() ?: error("模型未返回内容。")
                            if (r.header("Content-Type", "")!!.contains("application/json")) {
                                val result = JSONObject(String(readBounded(r, 1_000_000), Charsets.UTF_8))
                                val message = if (responses) {
                                    check(result.optString("status") == "completed") { "模型未完整生成，请重试。" }
                                    (result.optJSONArray("output") ?: JSONArray()).objects().flatMap { (it.optJSONArray("content") ?: JSONArray()).objects() }.filter { it.optString("type") == "output_text" }.joinToString("\n") { it.optString("text") }
                                } else result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                                check(message.isNotBlank()) { "模型未返回可显示的文字。" }; onText(message)
                            } else {
                                var length = 0; var done = false; var terminated = false
                                val batcher = TextBatcher(emit = onText)
                                val parser = SseEvents { event ->
                                    if (responses) {
                                        val chunk = JSONObject(event)
                                        when (chunk.optString("type")) {
                                            "response.output_text.delta" -> { val delta = chunk.optString("delta"); length += delta.length; check(length <= 1_000_000) { "生成内容过长。" }; batcher.append(delta) }
                                            "response.completed" -> { done = true; terminated = true }
                                            "response.failed", "response.incomplete", "error" -> {
                                                val code = chunk.optJSONObject("response")?.optJSONObject("error")?.optString("code") ?: chunk.optJSONObject("error")?.optString("code") ?: chunk.optString("code")
                                                error(if (p.chatgptAccountId.isNotBlank() && code in listOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable")) "ChatGPT 套餐或应用额度暂不可用，请点击“管理用量”查看。" else "模型未完成本次生成，请核对权限后重试；已生成部分不算完整笔记。")
                                            }
                                        }
                                    } else if (event == "[DONE]") { done = true; terminated = true } else {
                                        val chunk = JSONObject(event)
                                        check(!chunk.has("error")) { "模型返回生成错误，请核对配置或重试。" }
                                        val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                                        choice?.optional("finish_reason")?.let { reason ->
                                            check(reason == "stop") { "模型未完整生成，可能达到输出限制，请缩小范围后重试。" }
                                            terminated = true
                                        }
                                        val delta = choice?.optJSONObject("delta")?.optional("content") ?: ""
                                        if (delta.isNotEmpty()) { length += delta.length; check(length <= 1_000_000) { "生成内容过长。" }; batcher.append(delta) }
                                    }
                                }
                                try { while (continuation.isActive && !done) { val line = source.readUtf8Line(); parser.line(line); if (line == null) break } }
                                finally { if (continuation.isActive) batcher.flush() }
                                check(length > 0) { "模型未返回可显示的文字。" }
                                check(terminated) { "模型连接提前结束，内容尚未完整生成，请重试。" }
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    fun models(p: Provider): List<String> = modelChoices(p).map { it.id }
    fun modelChoices(p: Provider): List<ModelChoice> {
        validateProviderDestination(p)
        checkKey(p.key)
        val request = Request.Builder().url(aiEndpoint(p.baseUrl, "models")).header("Authorization", "Bearer ${p.key.trim()}").build()
        return client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { if (p.chatgptAccountId.isNotBlank()) "ChatGPT 模型列表读取失败（HTTP ${r.code}），请核对账号授权。" else "模型列表接口返回 HTTP ${r.code}；可核对地址或手动填写模型名称。" }
            parseModelChoices(JSONObject(String(readBounded(r, 1_000_000), Charsets.UTF_8)), p.chatgptAccountId.isNotBlank())
        }
    }
}

fun responseBody(p: Provider, messages: List<Message>): JSONObject {
    val input = messages.filter { it.role != "system" }.map { m ->
        val text = if (m.source == null) m.text else "来源：${m.source}\n${m.text}"
        val content = JSONArray().put(JSONObject().put("type", if (m.role == "assistant") "output_text" else "input_text").put("text", text))
        if (m.image != null && m.role == "user") content.put(JSONObject().put("type", "input_image").put("image_url", m.image))
        JSONObject().put("role", m.role).put("content", if (m.role == "assistant") text else content)
    }
    return JSONObject().put("model", p.model).put("stream", true).put("store", false).put("instructions", messages.filter { it.role == "system" }.joinToString("\n\n") { it.text }).put("input", JSONArray(input))
}

data class ModelChoice(val id: String, val name: String)
fun parseModelChoices(o: JSONObject, chatgpt: Boolean): List<ModelChoice> {
    val array = o.optJSONArray(if (chatgpt) "models" else "data") ?: error("模型列表结构无法识别，请重新授权或核对服务商。")
    val list = (0 until minOf(array.length(), 2000)).mapNotNull { index ->
        val m = array.optJSONObject(index) ?: return@mapNotNull null
        if (chatgpt && m.optString("visibility") != "list") return@mapNotNull null
        val id = m.optional(if (chatgpt) "slug" else "id")?.takeIf { it.length <= 200 && it.none(Char::isISOControl) } ?: return@mapNotNull null
        ModelChoice(id, if (chatgpt) m.optional("display_name") ?: id else id)
    }.distinctBy { it.id }
    return if (chatgpt) list else list.sortedBy { it.id }
}

class ChatGptRequestFailure(val status: Int, val errorCode: String, val requestId: String, message: String) : IllegalStateException(message)
fun chatGptResponseFailure(r: Response): ChatGptRequestFailure {
    val body = runCatching { JSONObject(String(readBounded(r, 262144), Charsets.UTF_8)) }.getOrNull()
    val code = body?.optJSONObject("error")?.optString("code") ?: ""
    val message = when (code) {
        "subscription_sharing_user_not_eligible" -> "此账号、工作区或地区当前不支持套餐请求；重新登录不能解除这项限制。"
        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 套餐或本应用额度受限，请点击“管理用量”查看。"
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "ChatGPT 套餐服务暂不可用，账号已保留，请稍后重试。"
        "subscription_sharing_unsupported_capability" -> "当前套餐接口不支持本次模型或输入能力，请选择其他可用模型。"
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "此 ChatGPT 注册的权限不允许本次请求，请检查套餐授权。"
        else -> when (r.code) {
            401 -> "ChatGPT 授权未被接受，请检查账号授权状态。"
            403 -> "ChatGPT 请求受账号、地区或服务权限限制，请核对授权；不会自动切换为 API 付费。"
            429 -> "ChatGPT 请求达到限制，请点击“管理用量”查看。"
            503 -> "ChatGPT 套餐服务暂不可用，登录已保留，请稍后重试。"
            else -> "ChatGPT 请求失败（HTTP ${r.code}），登录资料已保留。"
        }
    }
    return ChatGptRequestFailure(r.code, code.take(120), r.header("x-request-id") ?: r.header("openai-request-id") ?: "", message)
}
