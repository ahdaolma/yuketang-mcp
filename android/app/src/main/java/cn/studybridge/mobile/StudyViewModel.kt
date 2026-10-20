package cn.studybridge.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiState(val data: Saved = Saved(), val tab: Int = 0, val notice: String = "", val busy: Boolean = false, val operation: String = "", val ready: Boolean = false, val qr: ByteArray? = null, val loginActive: Boolean = false, val courses: List<Course> = emptyList(), val course: Course? = null, val activityPage: Int = 0, val presentations: List<Presentation> = emptyList(), val slides: List<Slide> = emptyList(), val slideTitle: String = "", val presentationId: String = "", val pending: Attachment? = null, val noteDraft: String = "", val modelListFor: String = "", val modelList: List<String> = emptyList(), val loginStage: String = "", val loginSteps: List<String> = emptyList(), val liveLessons: List<LiveLesson> = emptyList(), val classUi: ClassUi = ClassUi(), val chatgptLoginActive: Boolean = false, val chatgptStage: String = "", val chatgptPlanWelcome: Boolean = false, val modelNames: Map<String, String> = emptyMap(), val modelListRequest: String = "") { val locked get() = busy || chatgptLoginActive || (classUi.running && !classUi.paused) }

class StudyViewModel(app: Application) : AndroidViewModel(app) {
    val noteReader = NoteReader()
    private val vault = Vault(app)
    private val disk = Mutex()
    private val rain = RainApi()
    private val ai = AiApi()
    private val chatgpt = ChatGptAuth()
    private val accountLock = Mutex()
    private var webPage: WebPagePort? = null
    private var authJob: Job? = null
    private var authReceiver: LoopbackReceiver? = null
    private var authAttemptId = ""
    private val state = MutableStateFlow(UiState())
    val ui = state.asStateFlow()
    private var job: Job? = null
    private var classFeed: Job? = null
    private var classWriter: Job? = null
    private val classWake = Channel<Unit>(Channel.CONFLATED)
    private var login: AutoCloseable? = null
    private var storageBlocked = false
    private var loginAttempt = newId()
    private var lastResumeCheck = 0L
    init {
        viewModelScope.launch {
            try { val saved = withContext(Dispatchers.IO) { vault.load() }; state.update { it.copy(data = saved, ready = true) } }
            catch (_: Exception) { storageBlocked = true; state.update { it.copy(ready = true, notice = "本机记录无法解密，已停止写入以保留原记录。请备份原安装后排查系统密钥，勿直接覆盖数据。") } }
        }
    }
    fun notice(value: String) { state.update { it.copy(notice = value) } }
    fun tab(value: Int) { state.update { it.copy(tab = value) }; if (value == 1) onResume() }
    private fun persist() { viewModelScope.launch(Dispatchers.IO) { disk.withLock { if (!storageBlocked) try { vault.save(state.value.data) } catch (_: Exception) { notice("本机记录保存失败，请检查存储空间。") } } } }
    private fun data(change: (Saved) -> Saved) { state.update { it.copy(data = change(it.data)) }; persist() }
    private suspend fun checkpoint(change: (Saved) -> Saved) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        disk.withLock {
            check(!storageBlocked) { "本机记录不可写，已保留待恢复状态。" }
            saveBeforePublish({ state.value.data },
                save = { saved -> withContext(Dispatchers.IO) { vault.save(saved) } },
                publish = { update -> state.update { it.copy(data = update(it.data)) } }, change = change)
        }
        persist()
    }
    fun saveProvider(p: Provider): Boolean {
        try {
            validateProviderDestination(p); aiEndpoint(p.baseUrl); require(p.name.isNotBlank() && p.model.isNotBlank()) { "请填写名称和模型名称。" }
            data { it.copy(providers = it.providers.filterNot { v -> v.id == p.id } + if (p.chatgptAccountId.isBlank()) p else p.copy(key = ""), selectedProvider = p.id) }
            notice("模型配置已保存。")
            return true
        } catch (e: Exception) { notice(e.message ?: "配置保存失败。"); return false }
    }
    fun selectProvider(id: String) { data { it.copy(selectedProvider = id) } }
    fun removeProvider(id: String) {
        if (state.value.data.providers.size == 1) { notice("请至少保留一个模型配置。") ; return }
        data { val list = it.providers.filterNot { p -> p.id == id }; it.copy(providers = list, selectedProvider = if (it.selectedProvider == id) list.first().id else it.selectedProvider) }
    }
    fun bindWeb(page: WebPagePort?) { webPage = page }
    fun learningMode(mode: String) {
        if (state.value.locked || mode !in setOf("web", "native")) return
        if (state.value.data.learningMode == mode) return
        if (!state.value.data.canUseLearningMode(mode)) { notice("请先结束本节上课模式，再切换学习方式。"); return }
        data { it.copy(learningMode = mode) }
    }
    fun consentWebClass(on: Boolean = true) { if (!state.value.locked) data { it.copy(webClassConsent = on) } }
    suspend fun webLocation(url: String) {
        val pending = state.value.data.webPending ?: return
        val page = webPage ?: return
        if (page.pageUrl != url) return
        val safe = runCatching { pendingConversationUrl(pending, page) }.getOrNull() ?: return
        data { d -> val current = d.webPending; if (current != null && current.marker == pending.marker && webReturnUrl(current.url) == CHATGPT_WEB) d.copy(webPending = current.copy(url = safe)) else d }
    }
    private fun webDriver() = WebStudyDriver(webPage ?: error("请先打开学习页中的 ChatGPT 网页。"))
    private suspend fun webGenerate(a: Attachment, prompt: String, pageKey: String = "", noteId: String = "", noteSource: String = ""): String {
        check(state.value.data.usesWeb) { "本节记录使用网页模式，请先切回网页学习。" }
        check(state.value.data.webPending == null) { "上一份网页回答尚未保存，请先在学习页恢复回答；不会重复发送。" }
        state.update { it.copy(tab = 0) }
        val pending = WebPending("RS-${newId()}", a.label, webReturnUrl(webPage?.pageUrl ?: CHATGPT_WEB), pageKey, noteId, noteSource)
        return webDriver().generate(withLearnerProfile(prompt, state.value.data.learnerProfile), a, pending.marker, onMatched = { webLocation(webPage?.pageUrl ?: CHATGPT_WEB) }) {
            checkpoint { it.copy(webPending = pending) }
        }
    }
    fun fillWebAttachment() = operation("带入当前课件") {
        val a = state.value.pending ?: error("先在课程中选择一页课件。")
        require(state.value.data.usesWeb)
        webDriver().fill("${withLearnerProfile(state.value.data.tutorPrompt, state.value.data.learnerProfile)}\n\n请讲解这一页课件。资料来源：${a.label}\n${a.text}", a)
        state.update { it.copy(pending = null) }
        notice("课件已带入，请在网页核对附件并发送。")
    }
    fun saveWebAnswer() = operation("保存网页回答") {
        val pending = state.value.data.webPending
        val content = webDriver().capture(pending?.marker ?: "")
        val id = newId(); val receivedAt = System.currentTimeMillis()
        checkpoint { recoverWebAnswer(it, content, id, receivedAt) }
        notice("网页回答已存入笔记。")
    }
    fun discardWebPending() {
        if (state.value.locked) return
        data { it.copy(webPending = null) }
        notice("已清除待处理标记；网页中的消息不会删除。再次处理该页可能产生重复请求。")
    }
    fun autoNotes(on: Boolean) { data { it.copy(autoNotes = on) } }
    fun saveLearnerProfile(profile: String) = operation("保存学习情况") {
        require(profile.length <= LEARNER_PROFILE_LIMIT) { "学习情况最多 $LEARNER_PROFILE_LIMIT 字。" }
        checkpoint { it.copy(learnerProfile = profile.trim()) }
        notice(if (profile.isBlank()) "学习情况已清除，后续应用请求不再带入。已发送到网页的内容需在网页中更新或新建对话。" else "学习情况已加密保存。应用讲解与笔记会参考；网页自由聊天请带入并发送。")
    }
    fun fillWebLearnerProfile() = operation("带入学习情况") {
        val saved = state.value.data
        check(saved.usesWeb) { "请先切换到 ChatGPT 网页。" }
        check(saved.learnerProfile.isNotBlank()) { "请先填写并保存学习情况。" }
        state.update { it.copy(tab = 0) }
        webDriver().fill(learnerWebPrompt(saved.tutorPrompt, saved.learnerProfile), null)
        notice("学习情况已填入网页输入框，请核对后发送；新网页对话需重新带入。")
    }
    fun savePrompts(tutor: String, notes: String) { data { it.copy(tutorPrompt = tutor.ifBlank { TUTOR_PROMPT }, notesPrompt = notes.ifBlank { NOTES_PROMPT }) }; notice("角色提示词已保存，每次请求都会发送给所选模型。") }
    fun editNote(id: String, text: String): Boolean { if (state.value.locked || !state.value.data.noteWritable(id) || classWriter?.isActive == true && state.value.data.classRun?.noteId == id) { notice("请先结束整理并恢复待保存回答，再编辑笔记。"); return false }; if (state.value.data.notes.none { it.id == id }) return false; data { it.copy(notes = it.notes.map { n -> if (n.id == id) n.copy(content = text) else n }) }; return true }
    fun deleteNote(id: String): Boolean { if (state.value.locked || !state.value.data.noteWritable(id) || classWriter?.isActive == true && state.value.data.classRun?.noteId == id) { notice("请先结束整理并恢复待保存回答，再删除笔记。"); return false }; if (state.value.data.notes.none { it.id == id }) return false; data { it.copy(notes = it.notes.filterNot { n -> n.id == id }) }; return true }
    fun newChat() { if (state.value.busy) return; val c = Conversation(); data { it.copy(conversations = listOf(c) + it.conversations, currentChat = c.id) }; state.update { it.copy(pending = null) } }
    fun selectChat(id: String) { if (!state.value.busy) { data { it.copy(currentChat = id) }; state.update { it.copy(pending = null) } } }
    fun deleteChat(id: String) { if (state.value.busy) return; data { val list = it.conversations.filterNot { c -> c.id == id }.ifEmpty { listOf(Conversation()) }; it.copy(conversations = list, currentChat = if (it.currentChat == id) list.first().id else it.currentChat) } }
    private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.locked || !state.value.ready || storageBlocked) return
        state.update { it.copy(busy = true, operation = label, notice = "") }
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { notice("已停止。") ; throw e }
            catch (e: Exception) { notice(e.message?.take(240) ?: "操作失败，请重试。") }
            finally { state.update { it.copy(busy = false, operation = "", noteDraft = "") }; persist() }
        }
    }
    fun stop() { job?.cancel(); if (state.value.classUi.running) pauseClass() }
    fun testProvider(p: Provider) = operation("读取模型列表") {
        state.update { it.copy(modelListFor = "", modelList = emptyList(), modelNames = emptyMap(), modelListRequest = "") }
        val requestProvider = authorizedProvider(p)
        val choices = withContext(Dispatchers.IO) { ai.modelChoices(requestProvider) }
        val list = choices.map { it.id }
        state.update { it.copy(modelListFor = p.id, modelListRequest = modelCatalogKey(p), modelList = list, modelNames = choices.associate { m -> m.id to m.name }) }
        notice(if (list.isEmpty()) "连接成功，但未返回可选模型；可以手动填写。" else "已读取 ${list.size} 个模型，可以从列表选择。实际生成仍需核对所选模型权限。")
    }
    fun diagnoseLogin() = operation("检查雨课堂登录通道") { notice(rain.probe(state.value.data.region)) }
    private fun loginStep(value: String) {
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        state.update { it.copy(loginStage = value, loginSteps = (it.loginSteps + "$clock  $value").takeLast(8), qr = if (value.startsWith("微信已确认")) null else it.qr) }
    }
    fun onResume() {
        val s = state.value
        val now = System.currentTimeMillis()
        if (s.ready && s.tab == 1 && !s.busy && !s.loginActive && s.data.session.isNotBlank() && now - lastResumeCheck > 15000) { lastResumeCheck = now; verifyLogin() }
    }
    private suspend fun verifySession(s: Saved) {
        val user = withContext(Dispatchers.IO) { rain.api(s.region, s.session, "/api/v3/user/basic-info") }
        require(user.optional("id") != null) { "账号资料结构无法验证；登录记录已保留，可稍后重试。" }
        if (state.value.data.region == s.region && state.value.data.session == s.session) {
            data { verifyRememberedLogin(it, s.session, user.optString("name", s.username)) }
            loginStep("账号验证成功")
        }
    }
    fun verifyLogin() = operation("验证已保存的登录记录") {
        val s = state.value.data
        require(s.session.isNotBlank()) { "尚未收到雨课堂登录记录，请完成微信确认。" }
        try { verifySession(s); notice("账号已验证：${state.value.data.username}") }
        catch (e: Exception) { if (e is CancellationException) throw e; if (state.value.data.session == s.session) data { it.copy(sessionVerified = false) }; loginStep("账号验证暂未通过，登录记录已保留"); throw IllegalStateException("${e.message?.take(140) ?: "账号验证失败。"} 可点击“重新验证登录”，无需直接丢弃本次记录。") }
    }
    fun clearAttachment() { state.update { it.copy(pending = null) } }
    private fun updateChat(id: String, change: (Conversation) -> Conversation) { state.update { u -> u.copy(data = u.data.copy(conversations = u.data.conversations.map { if (it.id == id) change(it) else it })) } }
    fun send(text: String): Boolean {
        if (text.isBlank() || state.value.locked || !state.value.ready || storageBlocked) return false
        val s = state.value; val p = s.data.provider(); val c = s.data.chat(); val a = s.pending
        if (!s.data.providerReady(p) || p.model.isBlank()) { notice("请先在设置使用 ChatGPT 登录并选择模型，或填写其他服务的 API 配置。") ; tab(3); return false }
        if (!p.images && (a?.image != null || c.messages.any { it.image != null })) { notice("此对话包含课件图片，请选择并开启支持图片的模型，或新建纯文字对话。") ; return false }
        val user = Message("user", text + (a?.text?.takeIf { it.isNotBlank() }?.let { "\n\n课件原文：\n$it" } ?: ""), a?.image, a?.label)
        val history = c.messages.takeLast(24) + user
        updateChat(c.id) { it.copy(title = if (it.messages.isEmpty()) text.take(24) else it.title, messages = it.messages + user + Message("assistant", "")) }
        state.update { it.copy(pending = null) }
        persist()
        operation("正在回答") {
            try {
                ai.stream(authorizedProvider(p), tutorMessages(s.data.tutorPrompt, history, "本次上下文包含 ${history.count { it.image != null || it.source != null }} 条带课件图片或来源的消息。上课模式${if (s.classUi.running && !s.classUi.paused) "已开启" else "未运行或已暂停"}。读取动作由用户在应用界面触发，模型当前没有操作工具。", s.data.learnerProfile)) { chunk ->
                    updateChat(c.id) { chat -> chat.copy(messages = chat.messages.dropLast(1) + chat.messages.last().copy(text = chat.messages.last().text + chunk)) }
                }
            } catch (e: Exception) {
                updateChat(c.id) { chat -> val last = chat.messages.lastOrNull(); if (last?.role == "assistant" && last.text.isNotBlank()) chat.copy(messages = chat.messages.dropLast(1) + last.copy(text = last.text + "\n\n> 本次生成已中断，以上内容可能不完整。")) else chat }
                throw e
            } finally { updateChat(c.id) { it.copy(messages = it.messages.filterNot { m -> m.role == "assistant" && m.text.isBlank() }) } }
        }
        return true
    }
    fun region(region: String) {
        if (state.value.locked || region == state.value.data.region) return
        cancelLogin(); data { it.copy(region = region, session = "", username = "", sessionVerified = false) }
        state.update { it.copy(qr = null, loginActive = false, courses = emptyList(), course = null, presentations = emptyList(), slides = emptyList()) }
    }
    fun startLogin() {
        if (storageBlocked || !state.value.ready || state.value.busy) return
        cancelLogin(); val region = state.value.data.region; val attempt = newId(); loginAttempt = attempt
        state.update { it.copy(qr = null, loginActive = true, loginSteps = emptyList(), notice = "正在获取微信二维码…") }
        loginStep("正在连接微信登录通道")
        login = rain.login(region,
            onQr = { bytes -> if (loginAttempt == attempt) state.update { it.copy(qr = bytes, notice = "用微信扫一扫识别二维码并确认，完成后切回本 App。") } },
            onSuccess = { session, name -> viewModelScope.launch {
                if (loginAttempt != attempt || state.value.data.region != region) return@launch
                try {
                    state.update { it.copy(data = rememberLogin(it.data, region, session, name)) }
                    withContext(NonCancellable + Dispatchers.IO) { disk.withLock { vault.save(state.value.data) } }
                    if (loginAttempt != attempt) return@launch
                    loginStep("登录记录已保存，正在验证账号")
                    state.update { it.copy(loginActive = false, qr = null) }
                    verifyLogin()
                } catch (_: Exception) { if (loginAttempt == attempt) { loginStep("登录记录保存失败，请检查手机存储空间"); state.update { it.copy(loginActive = false, qr = null, notice = "已收到登录记录，但本机保存失败，请勿关闭 App。") } } }
            } },
            onError = { message -> if (loginAttempt == attempt) { loginStep(message); state.update { it.copy(loginActive = false, qr = null, notice = message) } } },
            onStep = { message -> if (loginAttempt == attempt) loginStep(message) }
        )
    }
    fun cancelLogin() { loginAttempt = newId(); login?.close(); state.update { it.copy(loginActive = false, qr = null) } }
    fun logout() { pauseClass(); cancelLogin(); data { it.copy(session = "", username = "", sessionVerified = false) }; state.update { it.copy(courses = emptyList(), course = null, presentations = emptyList(), slides = emptyList(), pending = null, loginSteps = emptyList(), loginStage = "") } }
    fun loadCourses() = operation("读取课程") { if (!state.value.data.sessionVerified) verifySession(state.value.data); val list = withContext(Dispatchers.IO) { rain.courses(state.value.data) }; state.update { it.copy(courses = list, course = null, slides = emptyList(), presentations = emptyList(), notice = if (list.isEmpty()) "接口未返回课程。" else "已读取 ${list.size} 门课程；不保证包含全部历史学期。") } }
    fun loadActivities(course: Course, page: Int = 0) = operation("读取课程活动") {
        val list = withContext(Dispatchers.IO) { rain.presentations(state.value.data, course, page) }
        state.update { it.copy(course = course, activityPage = page, presentations = list, slides = emptyList(), notice = if (list.isEmpty()) "这批活动没有可读取的课堂课件，可查看下一批。" else "找到 ${list.size} 条课堂 / 课件记录。") }
    }
    fun allCourses() { state.update { it.copy(course = null, presentations = emptyList(), slides = emptyList()) } }
    fun openPresentation(p: Presentation) = if (p.isLesson) operation("读取本节课件目录") {
        val list = withContext(Dispatchers.IO) { rain.lessonPresentations(state.value.data, p) }
        state.update { it.copy(presentations = list, slides = emptyList(), notice = if (list.isEmpty()) "本节课未开放可读取的课件。" else "本节共 ${list.size} 份课件，点击查看页面。") }
    } else loadSlides(p.id, p.lessonId, p.title)
    fun loadSlides(id: String, lessonId: String = "", fallbackTitle: String = "") = operation("读取课件目录") {
        val (title, slides) = withContext(Dispatchers.IO) { rain.slides(state.value.data, id, lessonId) }
        state.update { it.copy(slideTitle = if (title == "课件 $id" && fallbackTitle.isNotBlank()) fallbackTitle else title, presentationId = id, slides = slides, notice = if (slides.isEmpty()) "课件尚未开放可读取的页面。" else "已读取 ${slides.size} 页，可以提问或连续整理讲解笔记。") }
    }
    fun attach(slide: Slide) = operation("读取课件页面") {
        val a = withContext(Dispatchers.IO) { rain.attachment(state.value.slideTitle, slide) }
        state.update { it.copy(pending = a, tab = 0, notice = "已添加 ${a.label}。可在学习页带入或提问。") }
        if (state.value.data.autoNotes && (!state.value.data.usesWeb || state.value.data.webClassConsent)) generateNote(a)
    }
    fun notes() { if (state.value.data.usesWeb) saveWebAnswer() else operation("整理学习笔记") { generateNote(state.value.pending) } }
    fun batchNotes(from: String, to: String) = operation("准备连续笔记") {
        val s = state.value
        require(!s.data.usesWeb || s.data.webClassConsent) { "请先在设置启用网页跟课适配，再使用网页逐页整理。" }
        val pages = selectNotePages(from, to, s.slides)
        val p = s.data.provider()
        require(s.data.usesWeb || s.data.providerReady(p) && p.model.isNotBlank()) { "请先使用 ChatGPT 登录并选择模型，或配置其他模型服务。" }
        require(s.data.usesWeb || p.images || pages.none { it.cover.isNotBlank() }) { "所选页面包含图片，请先选择并开启支持图片输入的模型。" }
        val noteId = newId(); val createdAt = System.currentTimeMillis()
        val title = "${s.slideTitle} · 第 ${pages.first().page}–${pages.last().page} 页"
        var pageNumber = pages.first().page; var pageIndex = 1
        fun source(completed: Int) = "$title（已完成 $completed/${pages.size} 页${if (completed < pages.size) "，其余页面待处理" else "，全部完成"}）"
        if (s.data.usesWeb) {
            require(s.data.webPending == null) { "请先恢复上一份网页回答，再开始连续整理。" }
            val initial = Note(noteId, title, "# ${s.slideTitle}\n\n", source(0), "ChatGPT 网页", createdAt)
            checkpoint { it.copy(notes = listOf(initial) + it.notes) }
        }
        state.update { it.copy(tab = 2) }
        buildNotebook(s.slideTitle, pages,
            read = { page -> withContext(Dispatchers.IO) { rain.attachment(s.slideTitle, page) } },
            generate = { a ->
                val text = StringBuilder()
                val input = listOf(Message("user", a.text.ifBlank { "请阅读课件图片并整理笔记。" }, a.image, a.label))
                if (s.data.usesWeb) webGenerate(a, s.data.notesPrompt, "batch:$pageNumber", noteId, source(pageIndex)) else {
                    ai.stream(authorizedProvider(p), noteMessages(s.data.notesPrompt, input, a.label, s.data.learnerProfile)) { chunk -> text.append(chunk); state.update { it.copy(noteDraft = text.toString()) } }
                    text.toString()
                }
            },
            checkpoint = { progress ->
                val note = Note(noteId, title, progress.markdown, source(progress.completed), if (s.data.usesWeb) "ChatGPT 网页" else "${p.name} / ${p.model}", createdAt)
                checkpoint { it.copy(notes = listOf(note) + it.notes.filterNot { n -> n.id == noteId }, webPending = if (s.data.usesWeb) null else it.webPending) }
            },
            onPage = { page, index, total -> pageNumber = page; pageIndex = index; state.update { it.copy(operation = "连续笔记 $index/$total · 第 $page 页", noteDraft = "正在读取第 $page 页…") } },
            includeProgress = !s.data.usesWeb
        )
        notice("所选 ${pages.size} 页笔记已保存为一份，可编辑和导出。")
    }
    private suspend fun generateNote(a: Attachment?) {
        val s = state.value.data; val p = s.provider()
        if (s.usesWeb) {
            require(a != null) { "请在学习页保存已有的网页回答，或先选择课件。" }
            val text = webGenerate(a, s.notesPrompt)
            val n = Note(title = a.label, content = text, source = a.label, model = "ChatGPT 网页")
            checkpoint { it.copy(notes = listOf(n) + it.notes, webPending = null) }; notice("网页讲解已保存为笔记。")
            return
        }
        val input = a?.let { listOf(Message("user", it.text.ifBlank { "请根据课件图片整理笔记。" }, it.image, it.label)) } ?: s.chat().messages.takeLast(24)
        require(input.isNotEmpty()) { "先选择课件页面或进行对话，再整理笔记。" }
        val source = a?.label ?: "对话：${s.chat().title}"
        val text = StringBuilder()
        state.update { it.copy(operation = "自动整理笔记", noteDraft = "正在阅读资料…") }
        ai.stream(authorizedProvider(p), noteMessages(s.notesPrompt, input, source, s.learnerProfile)) { chunk -> text.append(chunk); state.update { it.copy(noteDraft = text.toString()) } }
        require(text.isNotBlank()) { "模型未生成笔记。" }
        val note = Note(title = a?.label ?: s.chat().title, content = text.toString(), source = source, model = "${p.name} / ${p.model}")
        data { it.copy(notes = listOf(note) + it.notes) }
        notice("笔记已保存，可在笔记页编辑和导出。")
    }
    fun discoverClasses() = operation("查找正在进行的课堂") {
        val lessons = withContext(Dispatchers.IO) { rain.onLessons(state.value.data) }
        state.update { it.copy(liveLessons = lessons) }
        notice(if (lessons.isEmpty()) "当前账号没有正在进行的课堂。老师开课后可刷新。" else "请选择正在上的课程，进入后开始跟随。")
    }
    fun startClass(lesson: LiveLesson) {
        val saved = state.value.data
        val p = saved.provider()
        if (saved.usesWeb && !saved.webClassConsent) { notice("请先在设置启用网页跟课适配，并在学习页登录网页。"); tab(3); return }
        if (saved.usesWeb && saved.webPending != null && saved.webPending.noteId != saved.classRun?.takeUnless { it.ended }?.takeIf { it.lesson.id == lesson.id }?.noteId) { notice("请先在学习页恢复待完成的网页回答。"); tab(0); return }
        if (!saved.usesWeb && (!saved.providerReady(p) || p.model.isBlank() || !p.images)) { notice("上课模式需要先配置支持图片的模型。"); tab(3); return }
        if (state.value.locked) return
        val existing = state.value.data.classRun
        if (existing != null && !existing.ended && existing.lesson.id != lesson.id) { notice("请先结束上一节的上课模式，再开始另一节课。"); return }
        val run = if (existing != null && !existing.ended && existing.lesson.id == lesson.id) existing else ClassRun(lesson, providerId = if (saved.usesWeb) WEB_PROVIDER_ID else p.id)
        if (run !== existing) {
            val note = Note(run.noteId, "${lesson.title} · 课堂笔记", "# ${lesson.title}\n\n> 根据课堂展示的课件逐页整理；补充内容标为 AI 讲解，不包含老师口述。\n\n", "课堂：${lesson.title}", if (saved.usesWeb) "ChatGPT 网页" else "${p.name} / ${p.model}", run.startedAt)
            data { it.copy(classRun = run, notes = listOf(note) + it.notes) }
        }
        resumeClass()
    }
    fun resumeClass() {
        if (state.value.busy || state.value.chatgptLoginActive || storageBlocked || state.value.classUi.let { it.running && !it.paused }) return
        val run = state.value.data.classRun?.takeUnless { it.ended && it.pending.isEmpty() } ?: return
        val p = state.value.data.providers.find { it.id == run.providerId }
        val web = run.providerId == WEB_PROVIDER_ID
        if (web && (!state.value.data.usesWeb || !state.value.data.webClassConsent)) { notice("请先启用网页跟课适配。"); tab(3); return }
        if (web && state.value.data.webPending?.let { it.noteId != run.noteId } == true) { notice("另一份网页回答尚未保存，请先恢复；不会重复发送。"); tab(0); return }
        if (!web && (state.value.data.usesWeb || p == null || !state.value.data.providerReady(p) || !p.images)) { notice("本节课记录使用原模型配置，请切回接口模式，或结束本节后新建网页课堂。"); return }
        val previousWriter = classWriter
        val previousFeed = classFeed
        val saved = state.value.data
        val prompt = saved.notesPrompt
        state.update { it.copy(classUi = ClassUi(running = true, paused = false, status = "正在连接课堂…")) }
        classWriter = viewModelScope.launch {
            previousWriter?.join()
            try {
                val pending = state.value.data.webPending
                if (web && pending != null) {
                    state.update { it.copy(tab = 0, classUi = it.classUi.copy(status = "正在恢复上一页网页讲解，不会重复发送…")) }
                    val answer = webDriver().awaitAnswer(pending.marker, onMatched = { webLocation(webPage?.pageUrl ?: CHATGPT_WEB) }, requireCurrent = true)
                    val receivedAt = System.currentTimeMillis()
                    checkpoint { recoverWebAnswer(it, answer, receivedAt = receivedAt) }
                    state.update { u ->
                        if (u.classUi.paused) u else u.copy(noteDraft = "", classUi = u.classUi.copy(status =
                            if (classAccess == null && u.data.classRun?.ended != true) "上一页已保存，正在连接课堂…"
                            else if (u.data.classRun?.pending?.isNotEmpty() == true) "上一页已保存，继续整理后续课件"
                            else "上一页已保存，等待老师翻页"))
                    }
                }
                while (isActive) {
                    val current = state.value.data.classRun ?: break
                    if (current.ended && current.pending.isEmpty()) { state.update { it.copy(classUi = ClassUi(status = "本节课堂笔记已完成")) }; break }
                    if (!current.ended && classAccess == null) { classWake.receive(); continue }
                    val page = current.pending.firstOrNull()
                    if (page == null) { classWake.receive(); continue }
                    state.update { it.copy(classUi = it.classUi.copy(current = page, status = "正在讲解第 ${page.page} 页")) }
                    // An old canceled HTTP request may finish after resume starts a new feed.
                    // Its rotated authorization belongs only to the access object that sent it.
                    val pageAccess = classAccess
                    val (title, slides) = withContext(Dispatchers.IO) { rain.slides(saved, page.presentationId, lessonId = if (current.ended) current.lesson.id else "", authorization = pageAccess?.authorization ?: "", onAuthorization = { pageAccess?.authorization = it }) }
                    val slide = slides.find { it.page == page.page } ?: error("第 ${page.page} 页尚未开放，已暂停；可稍后继续。")
                    val a = withContext(Dispatchers.IO) { rain.attachment(title, slide) }
                    val text = StringBuilder()
                    if (web) text.append(webGenerate(a, prompt, page.key, run.noteId))
                    else ai.stream(authorizedProvider(p!!), noteMessages(prompt, listOf(Message("user", a.text.ifBlank { "讲解这一页课件。" }, a.image, a.label)), a.label, saved.learnerProfile)) { chunk -> text.append(chunk); state.update { it.copy(noteDraft = text.toString()) } }
                    require(text.isNotBlank()) { "这一页没有生成有效讲解。" }
                    checkpoint { d ->
                        val r = d.classRun?.takeIf { it.noteId == run.noteId }
                        val updated = r?.copy(completed = (r.completed + page.key).distinct(), pending = r.pending.filterNot { it.key == page.key })
                        val notes = d.notes.map { if (it.id == run.noteId) it.copy(content = it.content + "## $title · 第 ${page.page} 页\n\n$text\n\n---\n\n") else it }
                        check(notes.any { it.id == run.noteId }) { "课堂笔记已被移除。" }
                        d.copy(classRun = updated ?: d.classRun, notes = notes, webPending = if (web) null else d.webPending)
                    }
                    state.update { u -> val current = u.data.classRun; if (current != null && current.noteId == run.noteId) u.copy(noteDraft = "", classUi = u.classUi.copy(current = null, status = "已保存 ${current.completed.size} 页${if (u.classUi.connected) "，等待老师翻页" else ""}")) else u }
                    if (state.value.data.classRun?.ended == true && state.value.data.classRun?.pending?.isEmpty() == true) { state.update { it.copy(classUi = ClassUi(status = "本节课堂笔记已完成")) }; break }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pauseClass(e.message?.take(180) ?: "笔记整理中断，已完成页面已保留。") }
        }
        classFeed = viewModelScope.launch {
            previousFeed?.join()
            try {
                if (run.ended) { classWake.trySend(Unit); return@launch }
                classAccess = withContext(Dispatchers.IO) { rain.enterLesson(saved, run.lesson) }
                classWake.trySend(Unit)
                var attempt = 0
                while (isActive) {
                    try {
                        rain.followLesson(saved, run.lesson, classAccess!!).collect { signal -> when (signal) {
                            LiveSignal.Connected -> { state.update { it.copy(classUi = it.classUi.copy(connected = true, status = if (it.classUi.current != null || it.data.webPending != null) it.classUi.status else "课堂已连接，等待课件页")) } }
                            is LiveSignal.Pages -> {
                                data { d -> d.copy(classRun = d.classRun?.let { enqueueClassPages(it, signal.pages) }) }
                                state.update { it.copy(classUi = it.classUi.copy(lastPage = signal.pages.lastOrNull() ?: it.classUi.lastPage)) }
                                classWake.trySend(Unit)
                            }
                            LiveSignal.Ended -> { data { it.copy(classRun = it.classRun?.copy(ended = true)) }; state.update { it.copy(classUi = it.classUi.copy(connected = false, status = "老师已下课，正在保存剩余讲解")) }; if (state.value.data.classRun?.pending?.isEmpty() == true) { classWriter?.cancel(); state.update { it.copy(classUi = ClassUi(status = "本节课堂笔记已完成")) } } }
                        } }
                        if (state.value.data.classRun?.ended == true) break
                        error("课堂连接已断开。")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        attempt++
                        if (attempt > 4) throw e
                        state.update { it.copy(classUi = it.classUi.copy(connected = false, status = "连接中断，正在重连 $attempt/4…")) }
                        delay(attempt * 2000L)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pauseClass(e.message?.take(180) ?: "课堂连接失败，已保留本节笔记。") }
        }
        classWake.trySend(Unit)
    }
    private var classAccess: LessonAccess? = null
    fun pauseClass(reason: String = "已暂停，完成的页面已保存") {
        classFeed?.cancel(); classWriter?.cancel(); classAccess = null
        state.update { it.copy(classUi = it.classUi.copy(paused = true, connected = false, current = null, status = reason), noteDraft = "") }
        persist()
    }
    fun background() { if (state.value.classUi.running && !state.value.classUi.paused) pauseClass("已切到后台，上课模式已暂停；回到 App 后点击继续。") }
    fun endClass() { pauseClass(); data { it.copy(classRun = it.classRun?.copy(ended = true, pending = emptyList())) }; state.update { it.copy(classUi = ClassUi(status = "本节已结束，已完成的笔记已保留")) } }
    private suspend fun saveNow() = withContext(NonCancellable + Dispatchers.IO) { disk.withLock { check(!storageBlocked); vault.save(state.value.data) } }
    private fun setChatGptAccount(account: ChatGptAccount) { state.update { u -> u.copy(data = u.data.copy(chatgptAccounts = u.data.chatgptAccounts.filterNot { it.id == account.id } + account)) } }
    private suspend fun authorizedProvider(p: Provider): Provider {
        check(!state.value.data.usesWeb) { "当前使用网页学习；接口调用已关闭。需要时可在设置显式切换。" }
        if (p.chatgptAccountId.isBlank()) return p
        validateProviderDestination(p)
        return accountLock.withLock {
            var account = state.value.data.chatgptAccounts.find { it.id == p.chatgptAccountId } ?: error("此 ChatGPT 账号记录已移除，请重新登录。")
            require(account.connected && account.planGranted) { "请使用 ChatGPT 登录并允许本应用使用套餐。" }
            val now = System.currentTimeMillis()
            if (account.expiresAt <= now + 60000 && !(account.earliestRefreshAt > now && account.expiresAt > now + 5000)) {
                require(account.refreshToken.isNotBlank()) { "ChatGPT 登录已到期，请重新授权。" }
                // A rotating refresh result must reach encrypted storage even if generation is canceled.
                withContext(NonCancellable) {
                    try {
                        account = withContext(Dispatchers.IO) { chatgpt.refresh(chatgpt.endpoints(), account) }
                        setChatGptAccount(account); saveNow()
                    } catch (e: OAuthFailure) {
                        if (e.reason in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) { setChatGptAccount(account.signedOut()); saveNow() }
                        throw e
                    }
                }
            }
            require(account.planGranted && account.accessToken.isNotBlank()) { "账号尚未授权使用 ChatGPT 套餐，请在设置开启。" }
            p.copy(key = account.accessToken, baseUrl = CHATGPT_API, protocol = "responses")
        }
    }
    private fun chatGptProvider(account: ChatGptAccount): Provider {
        val old = state.value.data.providers.find { it.chatgptAccountId == account.id }
        return old ?: Provider(name = "ChatGPT", baseUrl = CHATGPT_API, model = "", images = true, protocol = "responses", chatgptAccountId = account.id)
    }
    fun useChatGptAccount(id: String) {
        if (state.value.locked) return
        val account = state.value.data.chatgptAccounts.find { it.id == id } ?: return
        val p = chatGptProvider(account)
        data { it.copy(providers = it.providers.filterNot { existing -> existing.id == p.id } + p, selectedProvider = p.id) }
        state.update { it.copy(modelListFor = "", modelList = emptyList(), modelNames = emptyMap(), modelListRequest = "") }
        if (account.connected && account.planGranted) testProvider(p)
    }
    fun startChatGptLogin(accountId: String = "", draftClientId: String = "", forceConsent: Boolean = false) {
        if (state.value.locked || !state.value.ready || storageBlocked) return
        val selected = state.value.data.chatgptAccounts.find { it.id == accountId }
        if (accountId.isNotBlank() && selected == null) return
        val draft = draftClientId.takeIf { it in state.value.data.chatgptDraftClients } ?: ""
        val attemptId = newId(); authAttemptId = attemptId
        state.update { it.copy(tab = 3, chatgptLoginActive = true, chatgptStage = "正在准备 ChatGPT 账号授权…", notice = "") }
        authJob = viewModelScope.launch {
            var identityReceived = false
            var credentialsSaved = false
            try {
                if (state.value.data.chatgptHostId.isBlank()) { data { it.copy(chatgptHostId = "urn:uuid:${newId()}") }; saveNow() }
                getApplication<Application>().startForegroundService(Intent(getApplication(), AuthorizationService::class.java))
                val endpoints = withContext(Dispatchers.IO) { chatgpt.endpoints() }
                val receiver = withContext(Dispatchers.IO) { LoopbackReceiver() }; authReceiver = receiver
                val attempt = OAuthAttempt(receiver.redirect, selected?.clientId ?: draft.ifBlank { "dynamic_agent_client" }, state.value.data.chatgptHostId, idTokenHint = selected?.idToken ?: "", emailHint = selected?.email ?: "", expectedSubject = selected?.subject ?: "", forceConsent = forceConsent)
                val url = authorizationUrl(endpoints, attempt)
                state.update { it.copy(chatgptStage = "请在系统浏览器登录 ChatGPT，并允许雨课伴学使用套餐。") }
                openLink(url)
                val callback = receiver.receive(attempt)
                authReceiver = null
                if (callback.error.isNotBlank()) throw OAuthFailure(callback.error, if (callback.error == "access_denied") "你取消了 ChatGPT 授权；原有模型和登录保持不变。" else "ChatGPT 授权回调未通过校验，请重新开始。")
                if (selected == null) { data { it.copy(chatgptDraftClients = (it.chatgptDraftClients + callback.clientId).distinct()) }; saveNow() }
                state.update { it.copy(chatgptStage = "已收到授权，正在验证账号身份与套餐权限…") }
                val account = withContext(Dispatchers.IO) { chatgpt.exchange(endpoints, attempt, callback, selected?.id ?: newId()) }
                if (authAttemptId != attemptId) return@launch
                identityReceived = true
                accountLock.withLock { setChatGptAccount(account); data { it.copy(chatgptDraftClients = it.chatgptDraftClients.filterNot { id -> id == account.clientId }) }; saveNow() }
                credentialsSaved = true
                val p = chatGptProvider(account)
                data { it.copy(providers = it.providers.filterNot { existing -> existing.id == p.id } + p, selectedProvider = p.id) }
                saveNow()
                state.update { it.copy(modelListFor = "", modelList = emptyList(), modelNames = emptyMap(), modelListRequest = "", chatgptPlanWelcome = account.planGranted && !it.data.chatgptWelcomeShown) }
                if (account.planGranted && account.connected) {
                    state.update { it.copy(chatgptStage = "账号已验证，正在读取可用模型…") }
                    try {
                        val choices = withContext(Dispatchers.IO) { ai.modelChoices(p.copy(key = account.accessToken)) }
                        state.update { it.copy(modelListFor = p.id, modelListRequest = modelCatalogKey(p), modelList = choices.map { m -> m.id }, modelNames = choices.associate { m -> m.id to m.name }) }
                        notice("ChatGPT 登录已保存。请选择账号提供的模型并保存，再开始聊天或上课模式。")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("ChatGPT 登录已保存，但模型列表读取失败。可点击“读取模型”重试，无需 API Key。") }
                    state.update { it.copy(chatgptStage = "ChatGPT 账号已连接 · 已授权套餐使用") }
                } else {
                    state.update { it.copy(chatgptStage = "账号身份已验证，但尚未授予套餐使用权限。") }
                    notice("账号登录成功，但 AI 请求尚不能使用套餐；可点击“授权使用套餐”继续。")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (authAttemptId == attemptId) {
                    val message = (e as? OAuthFailure)?.message ?: if (identityReceived && !credentialsSaved) "ChatGPT 授权已通过，但本机保存未完成。请检查存储空间，暂时不要关闭 App。" else if (credentialsSaved) "ChatGPT 登录已保存，后续配置未完成；可在设置继续。" else "ChatGPT 登录未完成，请检查网络后重试；原有登录未被替换。"
                    state.update { it.copy(chatgptStage = message ?: "ChatGPT 登录未完成。", notice = message ?: "ChatGPT 登录未完成。") }
                }
            } finally {
                if (authAttemptId == attemptId) { authReceiver?.close(); authReceiver = null; state.update { it.copy(chatgptLoginActive = false) }; getApplication<Application>().stopService(Intent(getApplication(), AuthorizationService::class.java)) }
            }
        }
    }
    fun cancelChatGptLogin() {
        authAttemptId = newId(); authReceiver?.close(); authReceiver = null; authJob?.cancel()
        state.update { it.copy(chatgptLoginActive = false, chatgptStage = "已取消本次授权，原有账号保持不变。") }
        getApplication<Application>().stopService(Intent(getApplication(), AuthorizationService::class.java))
    }
    fun acknowledgeChatGptPlan() { data { it.copy(chatgptWelcomeShown = true) }; state.update { it.copy(chatgptPlanWelcome = false) } }
    fun signOutChatGpt(id: String) = operation("退出 ChatGPT 账号") {
        accountLock.withLock {
            val account = state.value.data.chatgptAccounts.find { it.id == id } ?: return@withLock
            var revoked = account.refreshToken.isBlank()
            try { if (!revoked) {
                for (attempt in 0..1) {
                    revoked = withContext(Dispatchers.IO) { runCatching { chatgpt.revoke(chatgpt.endpoints(), account) }.getOrDefault(false) }
                    if (revoked) break
                    if (attempt == 0) delay(1000)
                }
            } } finally { withContext(NonCancellable) { setChatGptAccount(account.signedOut()); saveNow() } }
            notice(if (revoked) "已退出此 ChatGPT 账号。其他账号和笔记已保留。" else "已清除本机登录，远端撤销未确认；可在 ChatGPT 的设置中断开雨课伴学。")
        }
    }
    fun openLink(url: String) {
        val u = Uri.parse(url)
        require(u.scheme == "https" && u.host in setOf("auth.openai.com", "chatgpt.com", "developers.openai.com"))
        getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun onCleared() { login?.close(); authReceiver?.close(); authJob?.cancel(); getApplication<Application>().stopService(Intent(getApplication(), AuthorizationService::class.java)); classFeed?.cancel(); classWriter?.cancel(); super.onCleared() }
}
