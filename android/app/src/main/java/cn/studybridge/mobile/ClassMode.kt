package cn.studybridge.mobile

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

data class LiveLesson(val id: String, val title: String, val classroomId: String)
data class LessonAccess(val token: String, val userId: String, var authorization: String)
data class LivePage(val presentationId: String, val page: Int) { val key get() = "$presentationId:$page" }
data class ClassRun(val lesson: LiveLesson, val noteId: String = newId(), val providerId: String, val startedAt: Long = System.currentTimeMillis(), val completed: List<String> = emptyList(), val pending: List<LivePage> = emptyList(), val ended: Boolean = false)
data class ClassUi(val running: Boolean = false, val paused: Boolean = true, val connected: Boolean = false, val status: String = "", val current: LivePage? = null, val lastPage: LivePage? = null)
sealed interface LiveSignal {
    data class Pages(val pages: List<LivePage>) : LiveSignal
    data object Connected : LiveSignal
    data object Ended : LiveSignal
}

// Teacher navigation uses the server's 1-based slide index; never infer future pages.
class LessonFrames {
    private var currentPresentation = ""
    fun pages(frame: JSONObject): List<LivePage> {
        val out = mutableListOf<LivePage>()
        fun add(pres: String?, index: Int) {
            if (pres != null) { if (!pres.matches(Regex("[0-9]{1,24}"))) return; currentPresentation = pres }
            if (currentPresentation.isNotBlank() && index in 1..100000) out.add(LivePage(currentPresentation, index))
        }
        when (frame.optString("op")) {
            "hello", "fetchtimeline" -> {
                frame.optJSONArray("timeline")?.objects()?.forEach { if (it.has("pres") && it.has("si")) add(it.optional("pres"), it.optInt("si", -1)) }
                add(frame.optional("presentation"), frame.optInt("slideindex", -1))
            }
            "showpresentation", "showfinished", "slide" -> add(frame.optional("presentation"), frame.optInt("slideindex", -1))
            "slidenav" -> frame.optJSONObject("slide")?.let { add(it.optional("pres"), it.optInt("si", -1)) }
            "presentationcreated", "presentationupdated" -> add(frame.optional("presentation"), frame.optInt("slideindex", -1))
        }
        return out.distinctBy { it.key }
    }
}
fun enqueueClassPages(run: ClassRun, pages: List<LivePage>): ClassRun {
    val completed = run.completed.toSet()
    val pending = (run.pending + pages).distinctBy { it.key }.filterNot { it.key in completed }
    require(pending.size <= 500) { "待整理页面超过 500 页，请结束本节课后分段整理。" }
    return run.copy(pending = pending)
}
fun ClassRun.json(): JSONObject = JSONObject().put("lesson", JSONObject().put("id", lesson.id).put("title", lesson.title).put("classroomId", lesson.classroomId)).put("noteId", noteId).put("providerId", providerId).put("startedAt", startedAt).put("completed", JSONArray(completed)).put("pending", JSONArray(pending.map { JSONObject().put("presentation", it.presentationId).put("page", it.page) })).put("ended", ended)
fun classRunFromJson(o: JSONObject): ClassRun {
    val l = o.getJSONObject("lesson")
    val completed = o.optJSONArray("completed") ?: JSONArray()
    return ClassRun(LiveLesson(l.getString("id"), l.getString("title"), l.getString("classroomId")), o.getString("noteId"), o.getString("providerId"), o.getLong("startedAt"), (0 until completed.length()).map { completed.getString(it) }, (o.optJSONArray("pending") ?: JSONArray()).objects().map { LivePage(it.getString("presentation"), it.getInt("page")) }, o.optBoolean("ended"))
}

fun RainApi.followLesson(s: Saved, lesson: LiveLesson, access: LessonAccess) = callbackFlow<LiveSignal> {
    val client = defaultRainClient()
    val host = RAIN_HOSTS[s.region] ?: error("雨课堂版本无效。")
    val parser = LessonFrames()
    val timer = Timer(true)
    val greeted = java.util.concurrent.atomic.AtomicBoolean(false)
    val socket = client.newWebSocket(Request.Builder().url("wss://$host/wsapp/").header("Origin", "https://$host").header("User-Agent", UA).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("op", "hello").put("userid", access.userId).put("role", "student").put("auth", access.token).put("lessonid", lesson.id).toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                check(text.length <= 1_000_000) { "课堂消息过大。" }
                val frame = JSONObject(text)
                if (frame.optString("op") == "lessonfinished" || frame.optString("message") == "lesson finished") {
                    check(trySend(LiveSignal.Ended).isSuccess); close(); return
                }
                if (frame.optString("op") == "hello") { greeted.set(true); timer.cancel(); check(trySend(LiveSignal.Connected).isSuccess) }
                val pages = parser.pages(frame)
                if (pages.isNotEmpty()) check(trySend(LiveSignal.Pages(pages)).isSuccess) { "课堂事件积压，请重新连接以补齐时间线。" }
            } catch (_: Exception) { close(IllegalStateException("课堂消息无法处理，请重新连接；已有笔记已保留。")) }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(IllegalStateException("课堂实时连接中断，请恢复网络后继续。")) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, "closed"); close(IllegalStateException("课堂连接已关闭。")) }
    })
    runCatching { timer.schedule(object : TimerTask() { override fun run() { if (!greeted.get()) { close(IllegalStateException("课堂握手超时，请核对这节课是否仍在进行。")); socket.cancel() } } }, 25000) }
    awaitClose { timer.cancel(); socket.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
