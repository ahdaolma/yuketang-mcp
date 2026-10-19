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
                            check(r.isSuccessful) { when (r.code) { 401, 403 -> "模型鉴权失败，请检查 API Key。"; 429 -> "模型请求受限或余额不足，请在服务商处核对。"; else -> "模型返回 HTTP ${r.code}，请核对地址和模型名称。" } }
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
                                            "response.failed", "response.incomplete", "error" -> error("模型未完成本次生成，请核对额度、模型权限后重试。")
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
    fun models(p: Provider): List<String> {
        checkKey(p.key)
        val request = Request.Builder().url(aiEndpoint(p.baseUrl, "models")).header("Authorization", "Bearer ${p.key.trim()}").build()
        return client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "模型列表接口返回 HTTP ${r.code}。可核对 API 地址；部分服务商不提供模型列表，仍可手填模型名称。" }
            val list = JSONObject(String(readBounded(r, 1_000_000), Charsets.UTF_8)).optJSONArray("data") ?: error("模型列表结构无法识别，可手动填写模型名称。")
            (0 until minOf(list.length(), 2000)).mapNotNull { list.optJSONObject(it)?.optional("id")?.takeIf { id -> id.length <= 200 && id.none(Char::isISOControl) } }.distinct().sorted()
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
