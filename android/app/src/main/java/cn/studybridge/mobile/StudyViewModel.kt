package cn.studybridge.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiState(val data: Saved = Saved(), val tab: Int = 0, val notice: String = "", val busy: Boolean = false, val operation: String = "", val ready: Boolean = false, val qr: ByteArray? = null, val loginActive: Boolean = false, val courses: List<Course> = emptyList(), val course: Course? = null, val activityPage: Int = 0, val presentations: List<Presentation> = emptyList(), val slides: List<Slide> = emptyList(), val slideTitle: String = "", val pending: Attachment? = null, val noteDraft: String = "")

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault(app)
    private val disk = Mutex()
    private val rain = RainApi()
    private val ai = AiApi()
    private val state = MutableStateFlow(UiState())
    val ui = state.asStateFlow()
    private var job: Job? = null
    private var login: AutoCloseable? = null
    private var storageBlocked = false
    init {
        viewModelScope.launch {
            try { val saved = withContext(Dispatchers.IO) { vault.load() }; state.update { it.copy(data = saved, ready = true) } }
            catch (_: Exception) { storageBlocked = true; state.update { it.copy(ready = true, notice = "本机记录无法解密，已停止写入以保留原记录。请备份原安装后排查系统密钥，勿直接覆盖数据。") } }
        }
    }
    fun notice(value: String) { state.update { it.copy(notice = value) } }
    fun tab(value: Int) { state.update { it.copy(tab = value) } }
    private fun persist() { viewModelScope.launch(Dispatchers.IO) { disk.withLock { if (!storageBlocked) try { vault.save(state.value.data) } catch (_: Exception) { notice("本机记录保存失败，请检查存储空间。") } } } }
    private fun data(change: (Saved) -> Saved) { state.update { it.copy(data = change(it.data)) }; persist() }
    fun saveProvider(p: Provider) {
        try {
            aiEndpoint(p.baseUrl); require(p.name.isNotBlank() && p.model.isNotBlank()) { "请填写名称和模型名称。" }
            data { it.copy(providers = it.providers.filterNot { v -> v.id == p.id } + p, selectedProvider = p.id) }
            notice("模型配置已保存。")
        } catch (e: Exception) { notice(e.message ?: "配置保存失败。") }
    }
    fun selectProvider(id: String) { data { it.copy(selectedProvider = id) } }
    fun removeProvider(id: String) {
        if (state.value.data.providers.size == 1) { notice("请至少保留一个模型配置。") ; return }
        data { val list = it.providers.filterNot { p -> p.id == id }; it.copy(providers = list, selectedProvider = if (it.selectedProvider == id) list.first().id else it.selectedProvider) }
    }
    fun autoNotes(on: Boolean) { data { it.copy(autoNotes = on) } }
    fun savePrompts(tutor: String, notes: String) { data { it.copy(tutorPrompt = tutor.ifBlank { TUTOR_PROMPT }, notesPrompt = notes.ifBlank { NOTES_PROMPT }) }; notice("角色提示词已保存，每次请求都会发送给所选模型。") }
    fun editNote(id: String, text: String) { data { it.copy(notes = it.notes.map { n -> if (n.id == id) n.copy(content = text) else n }) } }
    fun deleteNote(id: String) { data { it.copy(notes = it.notes.filterNot { n -> n.id == id }) } }
    fun newChat() { if (state.value.busy) return; val c = Conversation(); data { it.copy(conversations = listOf(c) + it.conversations, currentChat = c.id) }; state.update { it.copy(pending = null) } }
    fun selectChat(id: String) { if (!state.value.busy) { data { it.copy(currentChat = id) }; state.update { it.copy(pending = null) } } }
    fun deleteChat(id: String) { if (state.value.busy) return; data { val list = it.conversations.filterNot { c -> c.id == id }.ifEmpty { listOf(Conversation()) }; it.copy(conversations = list, currentChat = if (it.currentChat == id) list.first().id else it.currentChat) } }
    private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.busy || !state.value.ready || storageBlocked) return
        state.update { it.copy(busy = true, operation = label, notice = "") }
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { notice("已停止。") ; throw e }
            catch (e: Exception) { notice(e.message?.take(240) ?: "操作失败，请重试。") }
            finally { state.update { it.copy(busy = false, operation = "", noteDraft = "") }; persist() }
        }
    }
    fun stop() { job?.cancel() }
    fun testProvider(p: Provider) = operation("检查模型连接") { val count = withContext(Dispatchers.IO) { ai.test(p) }; notice("连接成功，服务商返回 $count 个模型；实际生成仍需使用所填模型验证。") }
    fun clearAttachment() { state.update { it.copy(pending = null) } }
    private fun updateChat(id: String, change: (Conversation) -> Conversation) { state.update { u -> u.copy(data = u.data.copy(conversations = u.data.conversations.map { if (it.id == id) change(it) else it })) } }
    fun send(text: String): Boolean {
        if (text.isBlank() || state.value.busy || !state.value.ready || storageBlocked) return false
        val s = state.value; val p = s.data.provider(); val c = s.data.chat(); val a = s.pending
        if (p.key.isBlank()) { notice("请先在模型设置中填写 API Key。") ; tab(3); return false }
        if (!p.images && (a?.image != null || c.messages.any { it.image != null })) { notice("此对话包含课件图片，请选择并开启支持图片的模型，或新建纯文字对话。") ; return false }
        val user = Message("user", text + (a?.text?.takeIf { it.isNotBlank() }?.let { "\n\n课件原文：\n$it" } ?: ""), a?.image, a?.label)
        val history = c.messages.takeLast(24) + user
        updateChat(c.id) { it.copy(title = if (it.messages.isEmpty()) text.take(24) else it.title, messages = it.messages + user + Message("assistant", "")) }
        state.update { it.copy(pending = null) }
        operation("正在回答") {
            try {
                ai.stream(p, tutorMessages(s.data.tutorPrompt, history)) { chunk ->
                    updateChat(c.id) { chat -> chat.copy(messages = chat.messages.dropLast(1) + chat.messages.last().copy(text = chat.messages.last().text + chunk)) }
                }
            } finally { updateChat(c.id) { it.copy(messages = it.messages.filterNot { m -> m.role == "assistant" && m.text.isBlank() }) } }
        }
        return true
    }
    fun region(region: String) {
        if (state.value.busy || region == state.value.data.region) return
        login?.close(); data { it.copy(region = region, session = "", username = "") }
        state.update { it.copy(qr = null, loginActive = false, courses = emptyList(), course = null, presentations = emptyList(), slides = emptyList()) }
    }
    fun startLogin() {
        if (storageBlocked || !state.value.ready) return
        login?.close(); val region = state.value.data.region
        state.update { it.copy(qr = null, loginActive = true, notice = "正在获取微信二维码…") }
        login = rain.login(region,
            onQr = { bytes -> state.update { it.copy(qr = bytes, notice = "用微信扫一扫识别二维码并确认。若不允许相册识别，需要另一台设备展示二维码。") } },
            onSuccess = { session, name -> viewModelScope.launch { if (state.value.data.region == region) { data { it.copy(session = session, username = name) }; state.update { it.copy(loginActive = false, qr = null, notice = "已连接：$name。可以读取课程。") } } } },
            onError = { message -> state.update { it.copy(loginActive = false, qr = null, notice = message) } }
        )
    }
    fun cancelLogin() { login?.close(); state.update { it.copy(loginActive = false, qr = null) } }
    fun logout() { cancelLogin(); data { it.copy(session = "", username = "") }; state.update { it.copy(courses = emptyList(), course = null, presentations = emptyList(), slides = emptyList(), pending = null) } }
    fun loadCourses() = operation("读取课程") { val list = withContext(Dispatchers.IO) { rain.courses(state.value.data) }; state.update { it.copy(courses = list, course = null, slides = emptyList(), presentations = emptyList(), notice = if (list.isEmpty()) "接口未返回课程。" else "已读取 ${list.size} 门课程；不保证包含全部历史学期。") } }
    fun loadActivities(course: Course, page: Int = 0) = operation("读取课程活动") {
        val list = withContext(Dispatchers.IO) { rain.presentations(state.value.data, course, page) }
        state.update { it.copy(course = course, activityPage = page, presentations = list, slides = emptyList(), notice = if (list.isEmpty()) "此批活动未返回明确的课件编号，可能尚未适配。可翻页继续查找。" else "找到 ${list.size} 份课件。") }
    }
    fun loadSlides(id: String) = operation("读取课件目录") { val (title, slides) = withContext(Dispatchers.IO) { rain.slides(state.value.data, id) }; state.update { it.copy(slideTitle = title, slides = slides, notice = "选择一页添加到对话。") } }
    fun attach(slide: Slide) = operation("读取课件页面") {
        val a = withContext(Dispatchers.IO) { rain.attachment(state.value.slideTitle, slide) }
        state.update { it.copy(pending = a, tab = 0, notice = "已添加 ${a.label}。发送问题时会交给所选模型。") }
        if (state.value.data.autoNotes) generateNote(a)
    }
    fun notes() = operation("整理学习笔记") { generateNote(state.value.pending) }
    private suspend fun generateNote(a: Attachment?) {
        val s = state.value.data; val p = s.provider()
        val input = a?.let { listOf(Message("user", it.text.ifBlank { "请根据课件图片整理笔记。" }, it.image, it.label)) } ?: s.chat().messages.takeLast(24)
        require(input.isNotEmpty()) { "先选择课件页面或进行对话，再整理笔记。" }
        val source = a?.label ?: "对话：${s.chat().title}"
        val text = StringBuilder()
        state.update { it.copy(operation = "自动整理笔记", noteDraft = "正在阅读资料…") }
        ai.stream(p, noteMessages(s.notesPrompt, input, source)) { chunk -> text.append(chunk); state.update { it.copy(noteDraft = text.toString()) } }
        require(text.isNotBlank()) { "模型未生成笔记。" }
        val note = Note(title = a?.label ?: s.chat().title, content = text.toString(), source = source, model = "${p.name} / ${p.model}")
        data { it.copy(notes = listOf(note) + it.notes) }
        notice("笔记已保存，可在笔记页编辑和导出。")
    }
    override fun onCleared() { login?.close(); super.onCleared() }
}
