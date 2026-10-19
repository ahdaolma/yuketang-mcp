package cn.studybridge.mobile

import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import okio.Buffer
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

val RAIN_HOSTS = linkedMapOf("standard" to "www.yuketang.cn", "hetang" to "pro.yuketang.cn", "changjiang" to "changjiang.yuketang.cn", "huanghe" to "huanghe.yuketang.cn")
val RAIN_NAMES = mapOf("standard" to "雨课堂", "hetang" to "荷塘雨课堂", "changjiang" to "长江雨课堂", "huanghe" to "黄河雨课堂")
private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
private val MEDIA_DOMAINS = listOf("yuketang.cn", "xuetangx.com", "myqcloud.com", "qcloud.com", "qpic.cn", "weixin.qq.com", "aliyuncs.com", "alicdn.com")
fun numericId(id: String): String { require(id.matches(Regex("[0-9]{1,24}"))) { "课件编号必须为数字。" }; return id }
fun allowedMedia(url: String): Boolean = runCatching { val u = url.toHttpUrl(); u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && MEDIA_DOMAINS.any { u.host == it || u.host.endsWith(".$it") } }.getOrDefault(false)
fun readBounded(response: Response, max: Int = 8 * 1024 * 1024): ByteArray {
    val source = response.body?.source() ?: error("服务器返回空内容。")
    val buffer = Buffer()
    while (buffer.size <= max) {
        if (source.read(buffer, minOf(8192L, max + 1L - buffer.size)) == -1L) return buffer.readByteArray()
    }
    error("服务器数据超过大小上限。")
}

class RainApi {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private fun host(region: String) = RAIN_HOSTS[region] ?: error("请选择雨课堂版本。")
    fun api(region: String, session: String, path: String, params: Map<String, String> = emptyMap()): JSONObject {
        require(session.matches(Regex("[A-Za-z0-9._-]{8,512}"))) { "请先登录雨课堂。" }
        val u = ("https://" + host(region) + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(u).header("Cookie", "sessionid=$session").header("User-Agent", UA).header("Referer", "https://${u.host}/v2/web/index").build()
        return client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "雨课堂返回 HTTP ${r.code}，请核对登录或课件权限。" }
            val bytes = readBounded(r)
            check(bytes.size <= 8 * 1024 * 1024) { "雨课堂数据超过大小上限。" }
            val b = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { error("雨课堂未返回可识别的数据，请重新登录。") }
            check(!b.has("code") || b.optInt("code", -1) in listOf(0, 200)) { "雨课堂拒绝读取此内容。" }
            check(!b.has("status") || b.optInt("status", -1) in listOf(0, 200)) { "雨课堂接口状态异常。" }
            check(b.optBoolean("success", true)) { "雨课堂拒绝读取此内容。" }
            b.optJSONObject("data") ?: error("雨课堂接口结构需要适配。")
        }
    }
    fun courses(s: Saved): List<Course> {
        val list = api(s.region, s.session, "/v2/api/web/courses/list", mapOf("identity" to "2")).optJSONArray("list") ?: error("课程列表结构需要适配。")
        return list.objects().map { Course(numericId(it.optString("classroom_id")), it.optJSONObject("course")?.optString("name")?.takeIf(String::isNotBlank) ?: it.optString("name", "未命名课程")) }
    }
    fun presentations(s: Saved, course: Course, page: Int): List<Presentation> {
        val d = api(s.region, s.session, "/v2/api/web/logs/learn/${numericId(course.id)}", mapOf("actype" to "-1", "page" to page.toString(), "offset" to "50", "sort" to "-1"))
        val activities = d.optJSONArray("activities") ?: error("课程活动结构需要适配。")
        return activities.objects().flatMap { a -> presentationRefs(a).map { Presentation(it, a.optional("title") ?: a.optional("name") ?: "课件 $it") } }.distinctBy { it.id }
    }
    fun slides(s: Saved, id: String): Pair<String, List<Slide>> {
        val d = api(s.region, s.session, "/api/v3/lesson/presentation/fetch", mapOf("presentation_id" to numericId(id)))
        val list = d.optJSONArray("slides") ?: error("此课件未开放、缺少课堂授权，或接口需要适配。")
        return (d.optional("title") ?: "课件 $id") to list.objects().mapIndexed { i, slide -> Slide(i + 1, slide.optString("cover"), slide.optional("text") ?: slide.optJSONObject("problem")?.toString() ?: "") }
    }
    fun image(url: String): Pair<String, ByteArray> {
        var current = url.toHttpUrl()
        repeat(4) {
            require(allowedMedia(current.toString())) { "此图片域名尚未适配。" }
            // Account cookies are only attached to api(); never to images or redirects.
            client.newCall(Request.Builder().url(current).build()).execute().use { r ->
                if (r.isRedirect) { current = current.resolve(r.header("Location") ?: error("图片跳转失败。")) ?: error("图片跳转失败。") }
                else {
                    check(r.isSuccessful) { "课件图片读取失败。" }
                    val bytes = readBounded(r)
                    check(bytes.size <= 8 * 1024 * 1024) { "图片超过大小上限。" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "图片无法识别或尺寸过大。" }
                    val mime = bounds.outMimeType ?: error("图片格式不支持。")
                    check(mime in listOf("image/jpeg", "image/png", "image/gif", "image/webp")) { "图片格式不支持。" }
                    return mime to bytes
                }
            }
        }
        error("图片跳转次数过多。")
    }
    fun attachment(title: String, slide: Slide): Attachment {
        val image = if (slide.cover.isBlank()) null else image(slide.cover).let { (mime, bytes) -> "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) }
        check(image != null || slide.text.isNotBlank()) { "此页没有可读取的图片或文字。" }
        return Attachment(image, "$title · 第 ${slide.page} 页", slide.text)
    }
    fun login(region: String, onQr: (ByteArray) -> Unit, onSuccess: (String, String) -> Unit, onError: (String) -> Unit): AutoCloseable {
        val timer = Timer(true)
        val lock = Any()
        var finished = false
        var socket: WebSocket? = null
        fun active() = synchronized(lock) { !finished }
        fun finish(action: () -> Unit = {}) { synchronized(lock) { if (finished) return; finished = true; timer.cancel(); socket?.close(1000, "finished"); action() } }
        fun qr(ws: WebSocket) { if (active()) ws.send(JSONObject().put("op", "requestlogin").put("role", "web").put("version", 1.4).put("type", "qrcode").put("from", "web").toString()) }
        socket = client.newWebSocket(Request.Builder().url("wss://${host(region)}/wsapp/").header("Origin", "https://${host(region)}").header("User-Agent", UA).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                qr(webSocket)
                if (active()) runCatching { timer.schedule(object : TimerTask() { override fun run() { qr(webSocket) } }, 50000, 50000) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active()) return
                var stage = "二维码获取失败。"
                try {
                    val d = JSONObject(text)
                    when (d.optString("op")) {
                        "requestlogin" -> { val bytes = image(d.getString("ticket")).second; if (active()) onQr(bytes) }
                        "loginsuccess" -> {
                            stage = "微信已确认，但雨课堂会话获取失败。"
                            val url = "https://${host(region)}/pc/web_login".toHttpUrl()
                            val request = Request.Builder().url(url).header("User-Agent", UA).post(JSONObject().put("UserID", d.get("UserID")).put("Auth", d.get("Auth")).toString().toRequestBody("application/json".toMediaType())).build()
                            val session = client.newCall(request).execute().use { r ->
                                check(r.isSuccessful)
                                r.headers.values("Set-Cookie").mapNotNull { Cookie.parse(url, it) }.find { it.name == "sessionid" }?.value ?: error("missing session")
                            }
                            stage = "雨课堂账号验证失败，请重新授权。"
                            val user = api(region, session, "/api/v3/user/basic-info")
                            check(user.optional("id") != null)
                            finish { onSuccess(session, user.optString("name", "已连接账号")) }
                        }
                    }
                } catch (_: Exception) { finish { onError(stage) } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish { onError("雨课堂登录连接失败，请检查网络和版本。") } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish { onError("登录通道已关闭，请重新开始。") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "closed") }
        })
        timer.schedule(object : TimerTask() { override fun run() { finish { onError("二维码已过期，请重新开始。") } } }, 180000)
        return AutoCloseable { finish() }
    }
}
