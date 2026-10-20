package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId() = UUID.randomUUID().toString()
data class Provider(val id: String = newId(), val name: String = "模型配置", val baseUrl: String = "", val key: String = "", val model: String = "", val images: Boolean = false, val protocol: String = "chat", val chatgptAccountId: String = "")
data class Message(val role: String, val text: String, val image: String? = null, val source: String? = null)
data class Conversation(val id: String = newId(), val title: String = "新对话", val messages: List<Message> = emptyList())
data class Note(val id: String = newId(), val title: String, val content: String, val source: String, val model: String, val createdAt: Long = System.currentTimeMillis(), val materialId: String = "", val pinned: Boolean = false, val folderId: String = "", val classNotebook: ClassNotebookBinding? = null, val annotations: List<NoteAnnotation> = emptyList())
data class Saved(val providers: List<Provider> = listOf(Provider()), val selectedProvider: String = "", val conversations: List<Conversation> = listOf(Conversation()), val currentChat: String = "", val notes: List<Note> = emptyList(), val autoNotes: Boolean = false, val region: String = "standard", val session: String = "", val username: String = "", val tutorPrompt: String = TUTOR_PROMPT, val notesPrompt: String = NOTES_PROMPT, val sessionVerified: Boolean = false, val classRun: ClassRun? = null, val chatgptAccounts: List<ChatGptAccount> = emptyList(), val chatgptHostId: String = "", val chatgptDraftClients: List<String> = emptyList(), val chatgptWelcomeShown: Boolean = false, val learnerProfile: String = "", val materials: List<StudyMaterial> = emptyList(), val downloadTreeUri: String = "", val downloadDirectoryName: String = "", val noteFolders: List<NoteFolder> = emptyList(), val noteDrafts: List<NoteDraft> = emptyList(), val noteReadingPositions: List<NoteReadingPosition> = emptyList(), val superFocusNotes: Boolean = false) {
    fun provider() = providers.find { it.id == selectedProvider } ?: providers.first()
    fun chat() = conversations.find { it.id == currentChat } ?: conversations.first()
}
data class Course(val id: String, val title: String, val detail: String = "")
data class Presentation(val id: String, val title: String, val lessonId: String = "", val isLesson: Boolean = false, val recordedAt: String = "")
data class Slide(val page: Int, val cover: String, val text: String)
data class Attachment(val image: String?, val label: String, val text: String, val imageRef: String = "")

fun JSONObject.optional(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun Saved.toJson(): JSONObject = JSONObject().apply {
    put("providers", JSONArray(providers.map { JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl).put("key", it.key).put("model", it.model).put("images", it.images).put("protocol", it.protocol).put("chatgptAccountId", it.chatgptAccountId) }))
    put("superFocusNotes", superFocusNotes); put("selectedProvider", selectedProvider); put("currentChat", currentChat); put("autoNotes", autoNotes)
    put("region", region); put("session", session); put("username", username)
    put("sessionVerified", sessionVerified); put("classRun", classRun?.json())
    put("chatgptAccounts", JSONArray(chatgptAccounts.map { it.json() })); put("chatgptHostId", chatgptHostId); put("chatgptDraftClients", JSONArray(chatgptDraftClients)); put("chatgptWelcomeShown", chatgptWelcomeShown)
    put("materials", JSONArray(materials.map { it.json() })); put("downloadTreeUri", downloadTreeUri); put("downloadDirectoryName", downloadDirectoryName); put("learnerProfile", learnerProfile); put("tutorPrompt", tutorPrompt); put("notesPrompt", notesPrompt)
    put("conversations", JSONArray(conversations.map { c -> JSONObject().put("id", c.id).put("title", c.title).put("messages", JSONArray(c.messages.map { m -> JSONObject().put("role", m.role).put("text", m.text).put("image", m.image).put("source", m.source) })) }))
    put("noteReadingPositions", JSONArray(noteReadingPositions.map { JSONObject().put("noteId", it.noteId).put("index", it.index).put("scroll", it.scroll).put("anchor", it.anchor).put("contentHash", it.contentHash) }))
    put("noteDrafts", JSONArray(noteDrafts.map { JSONObject().put("noteId", it.noteId).put("content", it.content).put("updatedAt", it.updatedAt).put("baseContent", it.baseContent) }))
    put("noteFolders", JSONArray(noteFolders.map { JSONObject().put("id", it.id).put("name", it.name) }))
    put("notes", JSONArray(notes.map { JSONObject().put("id", it.id).put("title", it.title).put("content", it.content).put("source", it.source).put("model", it.model).put("createdAt", it.createdAt).put("materialId", it.materialId).put("pinned", it.pinned).put("folderId", it.folderId).put("classNotebook", it.classNotebook?.json()).put("annotations", JSONArray(it.annotations.map { a -> a.json() })) }))
}
fun savedFromJson(o: JSONObject): Saved = Saved(
    providers = o.getJSONArray("providers").objects().map { Provider(it.getString("id"), it.getString("name"), it.getString("baseUrl"), it.getString("key"), it.getString("model"), it.optBoolean("images", false), it.optString("protocol", "chat"), it.optString("chatgptAccountId")) }.map { p -> if (p.name == "DeepSeek" && p.key.isBlank() && p.baseUrl == "https://api.deepseek.com" && p.model == "deepseek-flash") p.copy(name = "模型配置", baseUrl = "", model = "", images = false) else p }.ifEmpty { listOf(Provider()) },
    superFocusNotes = o.optBoolean("superFocusNotes", false), selectedProvider = o.optString("selectedProvider"),
    conversations = o.getJSONArray("conversations").objects().map { c -> Conversation(c.getString("id"), c.getString("title"), c.getJSONArray("messages").objects().map { Message(it.getString("role"), it.getString("text"), it.optional("image"), it.optional("source")) }) }.ifEmpty { listOf(Conversation()) },
    currentChat = o.optString("currentChat"), notes = (o.optJSONArray("notes") ?: JSONArray()).objects().map { Note(it.getString("id"), it.getString("title"), it.getString("content"), it.getString("source"), it.getString("model"), it.getLong("createdAt"), it.optString("materialId"), it.optBoolean("pinned"), it.optString("folderId"), it.optJSONObject("classNotebook")?.let(::classNotebookBindingFromJson), (it.optJSONArray("annotations") ?: JSONArray()).objects().map(::noteAnnotationFromJson)) },
    noteReadingPositions = (o.optJSONArray("noteReadingPositions") ?: JSONArray()).objects().map { NoteReadingPosition(it.getString("noteId"), it.optInt("index").coerceAtLeast(0), it.optInt("scroll").coerceAtLeast(0), it.optString("anchor"), it.optString("contentHash")) },
    noteDrafts = (o.optJSONArray("noteDrafts") ?: JSONArray()).objects().map { NoteDraft(it.getString("noteId"), it.getString("content"), it.getLong("updatedAt"), if (it.isNull("baseContent")) null else it.getString("baseContent")) },
    noteFolders = (o.optJSONArray("noteFolders") ?: JSONArray()).objects().map { NoteFolder(it.getString("id"), it.getString("name")) }.distinctBy { it.id },
    materials = (o.optJSONArray("materials") ?: JSONArray()).objects().map(::materialFromJson), downloadTreeUri = o.optString("downloadTreeUri"), downloadDirectoryName = o.optString("downloadDirectoryName"),
    learnerProfile = o.optString("learnerProfile").take(LEARNER_PROFILE_LIMIT), autoNotes = o.optBoolean("autoNotes"), region = o.optString("region", "standard"), session = o.optString("session"), username = o.optString("username"), tutorPrompt = upgradedTutorPrompt(o.optString("tutorPrompt", TUTOR_PROMPT)), notesPrompt = upgradedNotesPrompt(o.optString("notesPrompt", NOTES_PROMPT)), sessionVerified = o.optBoolean("sessionVerified", o.optString("session").isNotBlank()), classRun = o.optJSONObject("classRun")?.let(::classRunFromJson)?.let { if (it.providerId == "chatgpt-web") it.copy(ended = true, pending = emptyList()) else it }, chatgptAccounts = (o.optJSONArray("chatgptAccounts") ?: JSONArray()).objects().map(::chatGptAccountFromJson), chatgptHostId = o.optString("chatgptHostId"), chatgptDraftClients = (o.optJSONArray("chatgptDraftClients") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }, chatgptWelcomeShown = o.optBoolean("chatgptWelcomeShown")
).let { migrateLegacyWebNotes(o, it) }

// Only explicitly named presentation IDs are accepted; lesson/courseware IDs are different.
fun presentationRefs(value: Any?): List<String> {
    val found = linkedSetOf<String>()
    fun walk(v: Any?, depth: Int) {
        if (depth > 12) return
        when (v) {
            is JSONObject -> v.keys().forEach { k ->
                val child = v.opt(k)
                val id = child?.toString()
                if (k in listOf("presentation_id", "presentationId") && id != null && id.matches(Regex("[0-9]{1,24}"))) found.add(id)
                else walk(child, depth + 1)
            }
            is JSONArray -> (0 until v.length()).forEach { walk(v.opt(it), depth + 1) }
            is String -> if (v.length <= 100000 && v.trimStart().startsWith("{")) runCatching { walk(JSONObject(v), depth + 1) }
        }
    }
    walk(value, 0)
    return found.toList()
}

fun Saved.providerReady(p: Provider): Boolean = if (p.chatgptAccountId.isBlank()) p.key.isNotBlank() else chatgptAccounts.any { it.id == p.chatgptAccountId && it.planGranted && it.connected }
fun Saved.noteWritable(id: String): Boolean = classRun?.let { it.noteId != id || it.ended && it.pending.isEmpty() } != false
fun validateProviderDestination(p: Provider) {
    if (p.chatgptAccountId.isNotBlank()) require(p.baseUrl.trimEnd('/') == CHATGPT_API && p.protocol == "responses") { "ChatGPT 账号只能连接 OpenAI 官方 Responses 接口。" }
}
