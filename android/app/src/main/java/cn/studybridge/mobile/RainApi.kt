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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

val RAIN_HOSTS = linkedMapOf("standard" to "www.yuketang.cn", "hetang" to "pro.yuketang.cn", "changjiang" to "changjiang.yuketang.cn", "huanghe" to "huanghe.yuketang.cn")
val RAIN_NAMES = mapOf("standard" to "雨课堂", "hetang" to "荷塘雨课堂", "changjiang" to "长江雨课堂", "huanghe" to "黄河雨课堂")
internal const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
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

class RainApi(private val client: OkHttpClient = defaultRainClient()) {

    private fun host(region: String) = RAIN_HOSTS[region] ?: error("请选择雨课堂版本。")
    suspend fun probe(region: String): String {
        val hostname = host(region)
        val status = try { withContext(Dispatchers.IO) { client.newCall(Request.Builder().url("https://$hostname/").header("User-Agent", UA).build()).execute().use { it.code } } }
        catch (_: java.io.IOException) { return "站点连接失败，请检查手机网络与雨课堂版本。" }
        return suspendCancellableCoroutine { continuation ->
            val timer = Timer(true); val lock = Any(); var socket: WebSocket? = null
            fun finish(message: String) { synchronized(lock) { if (!continuation.isActive) return; timer.cancel(); socket?.close(1000, "probe complete"); continuation.resume("站点 HTTP $status；$message") } }
            continuation.invokeOnCancellation { timer.cancel(); socket?.cancel() }
            timer.schedule(object : TimerTask() { override fun run() { finish("登录诊断超时，请稍后重试。") } }, 25000)
            socket = client.newWebSocket(Request.Builder().url("wss://$hostname/wsapp/").header("Origin", "https://$hostname").header("User-Agent", UA).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { if (continuation.isActive) webSocket.send(JSONObject().put("op", "requestlogin").put("role", "web").put("version", 1.4).put("type", "qrcode").put("from", "web").toString()) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!continuation.isActive) return
                    try { val d = JSONObject(text); if (d.optString("op") == "requestlogin") { image(d.getString("ticket")); finish("微信通道与二维码图片正常。仍需你实际扫码确认。") } }
                    catch (_: Exception) { finish("微信通道已连接，但二维码图片读取失败。") }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish("微信登录通道连接失败，请核对版本或网络。") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "probe complete"); finish("微信登录通道提前关闭。") }
            })
            if (!continuation.isActive) socket?.cancel()
        }
    }
    fun api(region: String, session: String, path: String, params: Map<String, String> = emptyMap(), authorization: String = "", payload: JSONObject? = null, onAuthorization: (String) -> Unit = {}): JSONObject {
        require(session.matches(Regex("[A-Za-z0-9._-]{8,512}"))) { "请先登录雨课堂。" }
        val u = ("https://" + host(region) + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(u).header("Cookie", "sessionid=$session").header("User-Agent", UA).header("Referer", "https://${u.host}/v2/web/index").apply {
            if (authorization.isNotBlank()) header("Authorization", "Bearer $authorization")
            if (payload != null) post(payload.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        return client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "雨课堂返回 HTTP ${r.code}，请核对登录或课件权限。" }
            r.header("Set-Auth")?.takeIf { token -> token.length <= 8192 && token.none { it.isISOControl() || it.isWhitespace() } }?.let(onAuthorization)
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
        val merged = linkedMapOf<String, Course>()
        list.objects().forEach {
            val teacher = it.optJSONObject("teacher")?.optional("name").orEmpty()
            val term = it.optional("term").orEmpty()
            val course = Course(numericId(it.optString("classroom_id")), it.optJSONObject("course")?.optional("name") ?: it.optString("name", "未命名课程"), listOf(teacher, term).filter(String::isNotBlank).joinToString(" · "), teacher, term)
            val old = merged[course.id]
            merged[course.id] = old?.copy(teacher = old.teacher.ifBlank { teacher }, term = old.term.ifBlank { term }, detail = old.detail.ifBlank { course.detail }) ?: course
        }
        return merged.values.toList()
    }
    fun presentations(s: Saved, course: Course, page: Int): List<Presentation> {
        val d = api(s.region, s.session, "/v2/api/web/logs/learn/${numericId(course.id)}", mapOf("actype" to "-1", "page" to page.toString(), "offset" to "50", "sort" to "-1"))
        val activities = d.optJSONArray("activities") ?: error("课程活动结构需要适配。")
        return activityPresentations(activities).map { it.copy(teacher = it.teacher.ifBlank { course.teacher }) }
    }
    fun lessonPresentations(s: Saved, lesson: Presentation): List<Presentation> {
        val d = api(s.region, s.session, "/api/v3/classroom-report/student/lesson-info", mapOf("lesson_id" to numericId(lesson.lessonId)))
        val ids = d.optJSONArray("presentationIds") ?: error("这节课未返回课件目录，可能未开放课后回顾。")
        val title = d.optional("lessonName") ?: lesson.title
        val perIdTimes = presentationOwnedTimes(d)
        return mergePresentations((0 until ids.length()).map {
            val id = numericId(ids.get(it).toString())
            lessonPresentation(id, "$title · 课件 ${it + 1}", lesson, perIdTimes[id].orEmpty())
        })
    }
    fun slides(s: Saved, id: String, lessonId: String = "", authorization: String = "", onAuthorization: (String) -> Unit = {}): Pair<String, List<Slide>> {
        val d = if (lessonId.isBlank()) api(s.region, s.session, "/api/v3/lesson/presentation/fetch", mapOf("presentation_id" to numericId(id)), authorization, onAuthorization = onAuthorization)
        else api(s.region, s.session, "/api/v3/classroom-report/student/ppt", mapOf("lesson_id" to numericId(lessonId), "presentationId" to numericId(id), "front_time" to System.currentTimeMillis().toString()))
        return (d.optional("title") ?: d.optional("name") ?: "课件 $id") to parseSlides(d, lessonId.isNotBlank())
    }
    fun onLessons(s: Saved): List<LiveLesson> {
        val list = api(s.region, s.session, "/api/v3/classroom/on-lesson").optJSONArray("onLessonClassrooms") ?: error("正在上课列表结构需要适配。")
        return list.objects().map { LiveLesson(numericId(it.optString("lessonId")), it.optString("courseName", "正在上课"), numericId(it.optString("classroomId"))) }.distinctBy { it.id }
    }
    fun enterLesson(s: Saved, lesson: LiveLesson): LessonAccess {
        var authorization = ""
        val d = api(s.region, s.session, "/api/v3/lesson/checkin", payload = JSONObject().put("source", 5).put("lessonId", numericId(lesson.id)), onAuthorization = { authorization = it })
        val token = d.optional("lessonToken") ?: error("未取得课堂连接令牌，请先在雨课堂确认已进入这节课。")
        val uid = d.optional("identityId") ?: api(s.region, s.session, "/api/v3/user/basic-info").optional("id") ?: error("未取得课堂身份。")
        return LessonAccess(token, uid, authorization)
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
    fun login(region: String, onQr: (ByteArray) -> Unit, onSuccess: (String, String) -> Unit, onError: (String) -> Unit, onStep: (String) -> Unit = {}, timeoutMillis: Long = 180_000): AutoCloseable {
        val timer = Timer(true)
        val lock = Any()
        var finished = false
        var socket: WebSocket? = null
        var exchange: okhttp3.Call? = null
        fun active() = synchronized(lock) { !finished }
        fun update(action: () -> Unit) { synchronized(lock) { if (!finished) action() } }
        fun finish(action: () -> Unit = {}) { synchronized(lock) { if (finished) return; finished = true; timer.cancel(); exchange?.cancel(); socket?.close(1000, "finished"); action() } }
        fun qr(ws: WebSocket) { if (active()) ws.send(JSONObject().put("op", "requestlogin").put("role", "web").put("version", 1.4).put("type", "qrcode").put("from", "web").toString()) }
        socket = client.newWebSocket(Request.Builder().url("wss://${host(region)}/wsapp/").header("Origin", "https://${host(region)}").header("User-Agent", UA).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                update { onStep("微信通道已连接，正在获取二维码") }
                qr(webSocket)
                // Keep the ticket the user saved. Requesting another QR while
                // they are in WeChat can invalidate that image without notice.
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active()) return
                var stage = "二维码获取失败。"
                try {
                    val d = JSONObject(text)
                    when (d.optString("op")) {
                        "requestlogin" -> { val bytes = image(d.getString("ticket")).second; update { onStep("二维码已生成，等待微信确认"); onQr(bytes) } }
                        "loginsuccess" -> {
                            // The session HTTP exchange still belongs to this
                            // bounded attempt, even after WeChat confirmation.
                            update { onStep("微信已确认，正在获取雨课堂登录记录") }
                            stage = "微信已确认，但雨课堂会话获取失败。"
                            val url = "https://${host(region)}/pc/web_login".toHttpUrl()
                            val request = Request.Builder().url(url).header("User-Agent", UA).post(JSONObject().put("UserID", d.get("UserID")).put("Auth", d.get("Auth")).toString().toRequestBody("application/json".toMediaType())).build()
                            val call = client.newCall(request)
                            synchronized(lock) {
                                // Cancellation can win while constructing the
                                // exchange; never start it after the lease ends.
                                if (finished) { call.cancel(); return }
                                exchange = call
                            }
                            val session = try { call.execute().use { r ->
                                check(r.isSuccessful)
                                r.headers.values("Set-Cookie").mapNotNull { Cookie.parse(url, it) }.find { it.name == "sessionid" }?.value ?: error("missing session")
                            } } finally { synchronized(lock) { if (exchange === call) exchange = null } }
                            check(session.matches(Regex("[A-Za-z0-9._-]{8,512}")))
                            update { onStep("已取得雨课堂登录记录，正在加密保存") }
                            finish { onSuccess(session, d.optional("Name") ?: "已确认账号") }
                        }
                    }
                } catch (_: Exception) { finish { onError(stage) } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish { onError("雨课堂登录连接失败，请检查网络和版本。") } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish { onError("登录通道已关闭，请重新开始。") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "closed") }
        })
        synchronized(lock) { if (finished) socket?.close(1000, "finished") else timer.schedule(object : TimerTask() { override fun run() { finish { onError("本次扫码等待已结束，请重新获取二维码。") } } }, timeoutMillis) }
        return AutoCloseable { finish() }
    }
}

internal fun defaultRainClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

fun activityPresentations(activities: org.json.JSONArray): List<Presentation> = mergePresentations(activities.objects().flatMap { a ->
    val title = a.optional("title") ?: a.optional("name") ?: "课堂记录"
    val teacher = a.optJSONObject("teacher")?.optional("name").orEmpty()
    val owned = presentationOwnedTimes(a)
    val refs = presentationRefs(a).map { Presentation(it, title, teacher = teacher, times = activityTimes(a, false) + owned[it].orEmpty()) }
    // A type-14 courseware_id denotes a lesson, never a presentation.
    val lessonId = a.optional("courseware_id")
    if (a.optInt("type") == 14 && lessonId?.matches(Regex("[0-9]{1,24}")) == true) listOf(Presentation("lesson:$lessonId", title, lessonId, true, teacher = teacher, times = activityTimes(a, true))) else refs
})

fun parseSlides(d: JSONObject, report: Boolean): List<Slide> {
    val list = d.optJSONArray(if (report) "slideList" else "slides") ?: error("课件页面结构无法识别，或尚未开放。")
    return list.objects().mapIndexedNotNull { i, slide ->
        if (report && !slide.optBoolean("visible", false)) null
        else Slide(slide.optInt("index", i + 1).also { require(it > 0) { "课件页码格式需要适配。" } }, slide.optString("cover"), slide.optional("text") ?: slide.optJSONArray("shapes")?.objects()?.mapNotNull { it.optional("Text") }?.joinToString("\n") ?: slide.optJSONObject("problem")?.optional("body") ?: "")
    }.sortedBy { it.page }.distinctBy { it.page }
}
