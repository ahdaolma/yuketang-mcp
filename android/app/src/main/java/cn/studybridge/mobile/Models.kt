package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId() = UUID.randomUUID().toString()
data class Provider(val id: String = newId(), val name: String = "DeepSeek", val baseUrl: String = "https://api.deepseek.com", val key: String = "", val model: String = "deepseek-flash", val images: Boolean = true)
data class Message(val role: String, val text: String, val image: String? = null, val source: String? = null)
data class Conversation(val id: String = newId(), val title: String = "新对话", val messages: List<Message> = emptyList())
data class Note(val id: String = newId(), val title: String, val content: String, val source: String, val model: String, val createdAt: Long = System.currentTimeMillis())
data class Saved(val providers: List<Provider> = listOf(Provider()), val selectedProvider: String = "", val conversations: List<Conversation> = listOf(Conversation()), val currentChat: String = "", val notes: List<Note> = emptyList(), val autoNotes: Boolean = false, val region: String = "standard", val session: String = "", val username: String = "", val tutorPrompt: String = TUTOR_PROMPT, val notesPrompt: String = NOTES_PROMPT) {
    fun provider() = providers.find { it.id == selectedProvider } ?: providers.first()
    fun chat() = conversations.find { it.id == currentChat } ?: conversations.first()
}
data class Course(val id: String, val title: String)
data class Presentation(val id: String, val title: String)
data class Slide(val page: Int, val cover: String, val text: String)
data class Attachment(val image: String?, val label: String, val text: String)

fun JSONObject.optional(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun Saved.toJson(): JSONObject = JSONObject().apply {
    put("providers", JSONArray(providers.map { JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl).put("key", it.key).put("model", it.model).put("images", it.images) }))
    put("selectedProvider", selectedProvider); put("currentChat", currentChat); put("autoNotes", autoNotes)
    put("region", region); put("session", session); put("username", username)
    put("tutorPrompt", tutorPrompt); put("notesPrompt", notesPrompt)
    put("conversations", JSONArray(conversations.map { c -> JSONObject().put("id", c.id).put("title", c.title).put("messages", JSONArray(c.messages.map { m -> JSONObject().put("role", m.role).put("text", m.text).put("image", m.image).put("source", m.source) })) }))
    put("notes", JSONArray(notes.map { JSONObject().put("id", it.id).put("title", it.title).put("content", it.content).put("source", it.source).put("model", it.model).put("createdAt", it.createdAt) }))
}
fun savedFromJson(o: JSONObject): Saved = Saved(
    providers = o.getJSONArray("providers").objects().map { Provider(it.getString("id"), it.getString("name"), it.getString("baseUrl"), it.getString("key"), it.getString("model"), it.optBoolean("images", false)) }.ifEmpty { listOf(Provider()) },
    selectedProvider = o.optString("selectedProvider"),
    conversations = o.getJSONArray("conversations").objects().map { c -> Conversation(c.getString("id"), c.getString("title"), c.getJSONArray("messages").objects().map { Message(it.getString("role"), it.getString("text"), it.optional("image"), it.optional("source")) }) }.ifEmpty { listOf(Conversation()) },
    currentChat = o.optString("currentChat"), notes = (o.optJSONArray("notes") ?: JSONArray()).objects().map { Note(it.getString("id"), it.getString("title"), it.getString("content"), it.getString("source"), it.getString("model"), it.getLong("createdAt")) },
    autoNotes = o.optBoolean("autoNotes"), region = o.optString("region", "standard"), session = o.optString("session"), username = o.optString("username"), tutorPrompt = o.optString("tutorPrompt", TUTOR_PROMPT), notesPrompt = o.optString("notesPrompt", NOTES_PROMPT)
)

// Only explicitly named presentation IDs are accepted; lesson/courseware IDs are different.
fun presentationRefs(value: Any?): List<String> {
    val found = linkedSetOf<String>()
    fun walk(v: Any?, depth: Int) {
        if (depth > 12) return
        when (v) {
            is JSONObject -> v.keys().forEach { k ->
                val child = v.opt(k)
                if (k in listOf("presentation_id", "presentationId") && child.toString().matches(Regex("[0-9]{1,24}"))) found.add(child.toString())
                else walk(child, depth + 1)
            }
            is JSONArray -> (0 until v.length()).forEach { walk(v.opt(it), depth + 1) }
        }
    }
    walk(value, 0)
    return found.toList()
}
