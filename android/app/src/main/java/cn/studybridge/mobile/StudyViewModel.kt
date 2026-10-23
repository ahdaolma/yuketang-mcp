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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiState(val data: Saved = Saved(), val tab: Int = 0, val settingsPage: String = "", val notice: String = "", val busy: Boolean = false, val operation: String = "", val ready: Boolean = false, val qr: ByteArray? = null, val loginActive: Boolean = false, val courses: List<Course> = emptyList(), val course: Course? = null, val lesson: Presentation? = null, val courseActivities: List<Presentation> = emptyList(), val activityPage: Int = 0, val courseOrder: CourseOrder = CourseOrder.NEWEST, val presentations: List<Presentation> = emptyList(), val slides: List<Slide> = emptyList(), val slideTitle: String = "", val presentationId: String = "", val pending: Attachment? = null, val chatDrafts: Map<String, String> = emptyMap(), val noteChatOrigin: String? = null, val noteDraft: String = "", val modelListFor: String = "", val modelList: List<String> = emptyList(), val loginStage: String = "", val loginSteps: List<String> = emptyList(), val liveLessons: List<LiveLesson> = emptyList(), val classUi: ClassUi = ClassUi(), val chatgptLoginActive: Boolean = false, val chatgptStage: String = "", val chatgptPlanWelcome: Boolean = false, val modelNames: Map<String, String> = emptyMap(), val modelListRequest: String = "", val materialsOpen: Boolean = false, val materialPane: String = "home", val materialReturnPane: String = "files", val localDocument: MaterialDocument? = null, val slideRecordedAt: String = "", val storageError: String = "", val noteDraftSaving: Boolean = false, val noteDraftSavedAt: Long = 0L, val noteDraftSaveError: String = "") { val locked get() = !ready || storageError.isNotBlank() || busy || chatgptLoginActive || (classUi.running && !classUi.paused) }

class StudyViewModel @JvmOverloads constructor(app: Application, private val rain: RainApi = RainApi(), private val ai: AiApi = AiApi()) : AndroidViewModel(app) {
    val noteReader = NoteReader(
        storedDraft = { id -> savedDrafts.find { it.noteId == id && it.content != state.value.data.notes.find { n -> n.id == id }?.content } },
        onDraft = ::stageNoteDraft
    )
    val readingMemory = NoteReadingMemory(app, viewModelScope, ::notice)
    private val draftVault = Vault(app, "note-drafts-v1.enc")
    private val draftDisk = Mutex()
    private val durableNoteIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var savedDrafts = emptyList<NoteDraft>()
    private var draftRevision = 0L
    private var draftStorageBlocked = false
    private val draftWrites = Channel<Unit>(Channel.CONFLATED)
    private val vault = Vault(app)
    private val disk = Mutex()
    private val materials = MaterialStore(app)
    val sourceImages = SourceImages(app)
    val previews = MaterialPreviews(app)
    private val downloads = DownloadStore(app)
    private var exportDeck: UiState? = null
    private var materialRevision = 0L
    private val chatgpt = ChatGptAuth()
    private val accountLock = Mutex()
    private var authJob: Job? = null
    private var authReceiver: LoopbackReceiver? = null
    private var authAttemptId = ""
    private val state = MutableStateFlow(UiState())
    val ui = state.asStateFlow()
    private var job: Job? = null
    private var classFeed: Job? = null
    private var classWriter: Job? = null
    private var classStartJob: Job? = null
    private var classResumeStart: Job? = null
    private val classWake = Channel<Unit>(Channel.CONFLATED)
    private var login: AutoCloseable? = null
    private var storageBlocked = false
    private var loginAttempt = newId()
    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val original = vault.load(); val saved = original.withAutomaticChatTitles()
                    val drafts = runCatching { draftVault.load().noteDrafts }.getOrElse { draftStorageBlocked = true; emptyList() }
                        .filter { draft -> saved.notes.any { it.id == draft.noteId && it.content != draft.content } }
                    runCatching { sourceImages.prune(saved.copy(noteDrafts = drafts)) }
                    Triple(saved, drafts, saved != original)
                }
                durableNoteIds.addAll(loaded.first.notes.map { it.id })
                savedDrafts = loaded.second
                readingMemory.load(loaded.first.notes.map { it.id }.toSet())
                val interrupted = loaded.first.classRun?.let { !it.ended || it.pending.isNotEmpty() } == true
                state.update { it.copy(data = loaded.first, ready = true, classUi = if (interrupted) ClassUi(running = true, paused = true, status = "已恢复上次课堂，点击继续后才会重新连接；已保存页面会跳过。") else ClassUi()) }
                if (loaded.third) persist()
                savedDrafts.maxByOrNull { it.updatedAt }?.let { draft -> loaded.first.notes.find { it.id == draft.noteId }?.let { note ->
                    noteReader.open(note); state.update { it.copy(tab = 2, notice = "已恢复上次自动保存的笔记草稿。", noteDraftSavedAt = draft.updatedAt) }
                } }
                if (draftStorageBlocked) { state.update { it.copy(noteDraftSaveError = "自动保存不可用：草稿文件暂时无法读取") }; notice("草稿暂时无法读取，已保留原文件；已保存笔记仍可查看。") }
            }
            catch (_: Exception) { storageBlocked = true; state.update { it.copy(ready = true, storageError = "本机记录无法解密，已停止写入以保留原记录。请备份原安装后排查系统密钥，勿直接覆盖数据。") } }
        }
    }
    init { viewModelScope.launch { for (signal in draftWrites) writeDraftSnapshot() } }
    private fun stageNoteDraft(id: String, text: String) {
        if (!state.value.ready || storageBlocked || draftStorageBlocked) return
        val note = state.value.data.notes.find { it.id == id } ?: return
        savedDrafts = savedDrafts.filterNot { it.noteId == id } + if (text == note.content) emptyList() else listOf(NoteDraft(id, text, baseContent = noteReader.baseContent))
        draftRevision++
        state.update { it.copy(noteDraftSaving = true, noteDraftSaveError = "") }
        draftWrites.trySend(Unit)
    }
    private suspend fun writeDraftSnapshot() = withContext(NonCancellable) {
        draftDisk.withLock {
            val revision = draftRevision
            val snapshot = savedDrafts
            try {
                withContext(Dispatchers.IO) {
                    if (!draftStorageBlocked) {
                        // A new note's original record must be durable before
                        // its draft can be acknowledged as recoverable.
                        if (snapshot.any { it.noteId !in durableNoteIds }) disk.withLock {
                            val originals = state.value.data
                            vault.save(originals); durableNoteIds.addAll(originals.notes.map { it.id })
                        }
                        draftVault.save(Saved(noteDrafts = snapshot))
                    }
                }
                if (revision == draftRevision) state.update { it.copy(noteDraftSaving = false, noteDraftSavedAt = System.currentTimeMillis()) }
            } catch (_: Exception) { state.update { it.copy(noteDraftSaveError = "草稿保存失败，请手动保存并检查存储空间") }; notice("草稿自动保存失败，请检查存储空间；当前草稿仍在编辑器中。") }
        }
    }
    fun discardNoteDraft(id: String) {
        if (draftStorageBlocked) return
        savedDrafts = savedDrafts.filterNot { it.noteId == id }; draftRevision++
        state.update { it.copy(noteDraftSaving = true) }; draftWrites.trySend(Unit)
    }
    fun notice(value: String) { state.update { it.copy(notice = value) } }
    fun settingsPage(value: String) { if (value in setOf("", "models", "notes", "learning", "files", "about")) state.update { it.copy(settingsPage = value) } }
    fun tab(value: Int) { if (value != state.value.tab) { materialRevision++; state.update { it.copy(materialsOpen = false) } }; state.update { if (value == 1 && it.localDocument != null) it.copy(tab = value, localDocument = null, slides = emptyList()) else it.copy(tab = value) } }
    private fun persist() { viewModelScope.launch(Dispatchers.IO) { disk.withLock { if (!storageBlocked) try {
        val snapshot = state.value.data
        vault.save(snapshot)
        durableNoteIds.addAll(snapshot.notes.map { it.id })
        withContext(Dispatchers.Main.immediate) {
            val remaining = savedDrafts.filter { draft ->
                val current = state.value.data.notes.find { it.id == draft.noteId }
                val written = snapshot.notes.find { it.id == draft.noteId }
                current != null && (written?.content != draft.content || current.content != draft.content)
            }
            if (remaining != savedDrafts) { savedDrafts = remaining; draftRevision++; draftWrites.trySend(Unit) }
        }
    } catch (_: Exception) { notice("本机记录保存失败，请检查存储空间。") } } } }
    private fun data(change: (Saved) -> Saved) { if (storageBlocked || !state.value.ready) { notice("本机记录尚不可写，设置未保存。"); return }; state.update { it.copy(data = recordNotebookMutation(it.data, change(it.data))) }; persist() }
    fun updateNotebookState(change: (Saved) -> Saved): Boolean {
        if (storageBlocked || !state.value.ready) { notice("本机记录尚不可写，笔记未修改。"); return false }
        data(change)
        return true
    }
    fun notebookSnapshot(): Saved {
        val saved = state.value.data
        val ids = saved.notes.map { it.id }.toSet()
        return saved.copy(noteDrafts = savedDrafts.filter { it.noteId in ids }, noteReadingPositions = saved.notes.mapNotNull { readingMemory.position(it.id) })
    }
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
            check(!storageBlocked && state.value.ready) { "本机记录尚不可写，模型配置未保存。" }; validateProviderDestination(p); aiEndpoint(p.baseUrl); require(p.name.isNotBlank() && p.model.isNotBlank()) { "请填写名称和模型名称。" }
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
    fun autoNotes(on: Boolean) { data { it.copy(autoNotes = on) } }
    fun saveLearnerProfile(profile: String) = operation("保存学习情况") {
        require(profile.length <= LEARNER_PROFILE_LIMIT) { "学习情况最多 $LEARNER_PROFILE_LIMIT 字。" }
        checkpoint { it.copy(learnerProfile = profile.trim()) }
        notice(if (profile.isBlank()) "学习情况已清除，后续请求不再带入。" else "学习情况已加密保存，后续讲解与笔记会参考。")
    }
    fun savePrompts(tutor: String, notes: String) { data { it.copy(tutorPrompt = tutor.ifBlank { TUTOR_PROMPT }, notesPrompt = notes.ifBlank { NOTES_PROMPT }) }; notice("角色提示词已保存，每次请求都会发送给所选模型。") }
    fun editNote(id: String, text: String): Boolean {
        if (!state.value.noteEditable(id)) { notice("当前笔记暂不能编辑，请稍后再试；草稿会保留。"); return false }
        val note = state.value.data.notes.find { it.id == id } ?: return false
        val base = if (noteReader.id == id && noteReader.editing) noteReader.baseContent else note.content
        val merged = try { mergeNoteEdit(base, text, note.content) } catch (e: IllegalArgumentException) { notice(e.message.orEmpty()); return false }
        data { it.copy(notes = it.notes.map { n -> if (n.id == id) n.copy(content = merged) else n }) }
        if (savedDrafts.any { it.noteId == id }) {
            savedDrafts = savedDrafts.map { if (it.noteId == id) it.copy(content = merged, baseContent = merged) else it }
            draftRevision++; draftWrites.trySend(Unit)
        }
        if (noteReader.id == id) noteReader.acceptSaved(merged)
        return true
    }
    fun saveNoteAnnotation(id: String, annotation: NoteAnnotation): Boolean {
        if (!state.value.noteEditable(id)) return false
        val note = state.value.data.notes.find { it.id == id } ?: return false
        if (!annotation.isAttached(note.content) && note.annotations.none { it.id == annotation.id }) { notice("原文已变化，请重新选中文字。"); return false }
        data { d -> d.copy(notes = d.notes.map { if (it.id == id) it.copy(annotations = it.annotations.filterNot { a -> a.id == annotation.id } + annotation) else it }) }
        return true
    }
    fun removeNoteAnnotations(id: String, annotationIds: Set<String>) {
        if (!state.value.noteEditable(id)) return
        data { d -> d.copy(notes = d.notes.map { if (it.id == id) it.copy(annotations = it.annotations.filterNot { a -> a.id in annotationIds }) else it }) }
    }
    fun superFocusNotes(on: Boolean) {
        if (!state.value.ready || storageBlocked) return
        data { it.copy(superFocusNotes = on) }
        notice(if (on) "超重点模式已开启，下一页生成起只保留核心要点。" else "已恢复普通笔记风格。")
    }
    fun createManualNote(folderId: String = "") {
        if (state.value.locked) return
        val note = Note(title = "新笔记", content = "", source = "手写笔记", model = "手写", folderId = folderId.takeIf { id -> state.value.data.noteFolders.any { it.id == id } } ?: "")
        data { it.copy(notes = listOf(note) + it.notes) }
        if (state.value.data.notes.any { it.id == note.id }) { noteReader.open(note); noteReader.editing = true; tab(2) }
    }
    fun renameNote(id: String, title: String): Boolean {
        val value = title.trim()
        if (!state.value.noteEditable(id)) return false
        if (value.isBlank() || value.length > 120) { notice("笔记标题请填写 1–120 个字。"); return false }
        if (state.value.data.notes.none { it.id == id }) return false
        data { it.copy(notes = it.notes.map { n -> if (n.id == id) n.copy(title = value) else n }) }; return true
    }
    fun toggleNotePin(id: String) {
        if (state.value.locked) return
        data { it.copy(notes = it.notes.map { n -> if (n.id == id) n.copy(pinned = !n.pinned) else n }) }
    }
    fun deleteNotePages(id: String, snapshot: String, selected: Set<Int>): Boolean {
        val note = state.value.data.notes.find { it.id == id } ?: return false
        if (note.content != snapshot) { notice("笔记已发生变化，请重新选择要删除的页。"); return false }
        return try {
            val next = removeNotePages(snapshot, selected)
            if (!editNote(id, next)) false else { if (noteReader.id == id && !noteReader.editing) noteReader.draft = next; true }
        } catch (e: IllegalArgumentException) { notice(e.message ?: "页码无效。"); false }
    }
    fun deleteNote(id: String): Boolean { if (state.value.locked || !state.value.data.noteWritable(id) || classWriter?.isActive == true && state.value.data.classRun?.noteId == id) { notice("请先结束整理并恢复待保存回答，再删除笔记。"); return false }; if (state.value.data.notes.none { it.id == id }) return false; data { it.copy(notes = it.notes.filterNot { n -> n.id == id }) }; return true }
    fun saveNoteFolder(id: String?, name: String, parentId: String? = null): Boolean {
        if (state.value.locked) return false
        return try {
            data { it.withNoteFolder(id, name, parentId) }
            true
        } catch (e: IllegalArgumentException) { notice(e.message ?: "分类保存失败。"); false }
    }
    fun moveNote(id: String, folderId: String): Boolean {
        return moveNotes(setOf(id), folderId)
    }
    fun moveNotes(ids: Set<String>, folderId: String): Boolean {
        if (state.value.locked) return false
        return try { data { it.withMovedNotes(ids, folderId) }; true }
        catch (error: IllegalArgumentException) { notice(error.message ?: "移动失败，请重新选择。"); false }
    }
    fun deleteNoteFolder(id: String): Boolean {
        if (state.value.locked || state.value.data.noteFolders.none { it.id == id }) return false
        data { it.withoutNoteFolder(id) }
        return true
    }
    fun newChat() { if (state.value.locked) return; val c = Conversation(); data { it.copy(conversations = listOf(c) + it.conversations, currentChat = c.id) }; state.update { it.copy(pending = null) } }
    fun chatDraft(id: String, text: String) {
        if (state.value.data.conversations.none { it.id == id }) return
        state.update { it.copy(chatDrafts = it.chatDrafts + (id to text)) }
    }
    fun chatDraft(text: String) = chatDraft(state.value.data.chat().id, text)
    fun selectChat(id: String) {
        if (state.value.locked || state.value.data.conversations.none { it.id == id }) return
        data { it.copy(currentChat = id) }; state.update { it.copy(pending = null) }
    }
    fun deleteChat(id: String) {
        if (state.value.locked || state.value.data.conversations.none { it.id == id }) return
        val active = state.value.data.chat().id == id
        data { val list = it.conversations.filterNot { c -> c.id == id }.ifEmpty { listOf(Conversation()) }; it.copy(conversations = list, currentChat = if (active) list.first().id else it.currentChat) }
        state.update { it.copy(chatDrafts = it.chatDrafts - id, pending = if (active) null else it.pending) }
    }
    private fun materialOperation(label: String, block: suspend (UiState, Long) -> Unit) {
        if (state.value.locked || storageBlocked) return
        val snapshot = state.value
        val revision = ++materialRevision
        operation(label) { block(snapshot, revision) }
    }
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
        val history = chatTranscriptMessages(c.messages).takeLast(24) + user
        updateChat(c.id) { it.copy(messages = it.messages + user + Message("assistant", "")).withAutomaticTitle() }
        state.update { it.copy(pending = null, chatDrafts = it.chatDrafts - c.id) }
        persist()
        operation("正在回答") {
            try {
                val notebook = withContext(Dispatchers.Default) { chatNotebookContext(s.data, text, a?.noteId.orEmpty(), c.messages) }
                updateChat(c.id) { chat -> chat.copy(messages = chat.messages.dropLast(1) + chat.messages.last().copy(noteRefs = notebook.references)) }
                val input = if (notebook.text.isBlank()) history else history.dropLast(1) + Message("user", notebook.text) + history.last()
                ai.stream(authorizedProvider(p), tutorMessages(s.data.tutorPrompt, input, "本次上下文包含 ${history.count { it.image != null || it.source != null }} 条带课件图片或来源的消息，实际检索了 ${notebook.references.size} 份本机已保存笔记。上课模式${if (s.classUi.running && !s.classUi.paused) "已开启" else "未运行或已暂停"}。", s.data.learnerProfile)) { chunk ->
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
        materialRevision++; cancelLogin(); data { it.copy(region = region, session = "", username = "", sessionVerified = false) }
        state.update { it.copy(qr = null, loginActive = false, courses = emptyList(), course = null, lesson = null, courseActivities = emptyList(), presentationId = "", presentations = emptyList(), slides = emptyList()) }
    }
    fun startLogin() {
        if (storageBlocked || !state.value.ready || state.value.busy) return
        cancelLogin(); val region = state.value.data.region; val attempt = newId(); loginAttempt = attempt
        state.update { it.copy(qr = null, loginActive = true, loginSteps = emptyList(), notice = "正在获取微信二维码…") }
        loginStep("正在连接微信登录通道")
        try { getApplication<Application>().startForegroundService(Intent(getApplication(), RainLoginService::class.java).putExtra(RainLoginService.ATTEMPT, attempt)) }
        catch (_: Exception) { state.update { it.copy(loginActive = false, notice = "系统未允许保持扫码连接，请回到 App 后重新开始。") }; return }
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
                finally { if (loginAttempt == attempt) stopRainLoginService() }
            } },
            onError = { message -> if (loginAttempt == attempt) { loginStep(message); state.update { it.copy(loginActive = false, qr = null, notice = message) }; stopRainLoginService() } },
            onStep = { message -> if (loginAttempt == attempt) loginStep(message) }
        )
    }
    private fun stopRainLoginService() { getApplication<Application>().stopService(Intent(getApplication(), RainLoginService::class.java)) }
    internal fun rainLoginServiceStopped(attempt: String) {
        if (loginAttempt != attempt || !state.value.loginActive) return
        cancelLogin()
        val message = "后台扫码连接已停止，请回到 App 后重新获取二维码。"
        loginStep(message)
        notice(message)
    }
    fun cancelLogin() { loginAttempt = newId(); login?.close(); login = null; stopRainLoginService(); state.update { it.copy(loginActive = false, qr = null) } }
    fun logout() { materialRevision++; pauseClass(); cancelLogin(); data { it.copy(session = "", username = "", sessionVerified = false) }; state.update { it.copy(courses = emptyList(), course = null, lesson = null, courseActivities = emptyList(), presentationId = "", presentations = emptyList(), slides = emptyList(), pending = null, loginSteps = emptyList(), loginStage = "") } }
    fun courseOrder(order: CourseOrder) { state.update { it.copy(courseOrder = order) } }
    fun loadCourses() = materialOperation("读取课程") { s, revision ->
        if (!s.data.sessionVerified) verifySession(s.data)
        val list = withContext(Dispatchers.IO) { rain.courses(s.data) }
        if (revision == materialRevision) state.update { it.copy(courses = list, course = null, lesson = null, courseActivities = emptyList(), presentationId = "", slides = emptyList(), presentations = emptyList(), notice = if (list.isEmpty()) "接口未返回课程。" else "已读取 ${list.size} 门课程；不保证包含全部历史学期。") }
    }
    fun loadActivities(course: Course, page: Int = 0) = materialOperation("读取课程活动") { s, revision ->
        val list = withContext(Dispatchers.IO) { rain.presentations(s.data, course, page) }
        if (revision == materialRevision) state.update { it.copy(course = course, lesson = null, courseActivities = list, activityPage = page, presentations = list, presentationId = "", slides = emptyList(), notice = if (list.isEmpty()) "这批活动没有可读取的课堂课件，可查看下一批。" else "找到 ${list.size} 条课堂 / 课件记录。") }
    }
    fun allCourses() { materialRevision++; state.update { it.copy(course = null, lesson = null, courseActivities = emptyList(), presentations = emptyList(), presentationId = "", slides = emptyList()) } }
    fun backRainMaterials() {
        materialRevision++
        state.update { when {
            it.presentationId.isNotBlank() -> it.copy(presentationId = "", slides = emptyList(), slideTitle = "", slideRecordedAt = "", localDocument = null)
            it.lesson != null -> it.copy(lesson = null, presentations = it.courseActivities, slides = emptyList())
            it.course != null -> it.copy(course = null, lesson = null, courseActivities = emptyList(), presentations = emptyList(), slides = emptyList())
            else -> it.copy(materialPane = "home")
        } }
    }
    fun backMaterials() {
        when (state.value.materialPane) {
            "rain" -> backRainMaterials()
            "pages" -> { materialRevision++; state.update { it.copy(materialPane = it.materialReturnPane, localDocument = null, slides = emptyList(), presentationId = "") } }
            "home" -> closeMaterials()
            else -> materialPane("home")
        }
    }
    fun openPresentation(p: Presentation) {
        if (p.isLesson) materialOperation("读取本节课件目录") { s, revision ->
            val list = withContext(Dispatchers.IO) { rain.lessonPresentations(s.data, p) }
            if (revision == materialRevision) state.update { it.copy(lesson = p, courseActivities = if (s.lesson == null) s.presentations else s.courseActivities, presentations = list, presentationId = "", slides = emptyList(), notice = if (list.isEmpty()) "本节课未开放可读取的课件。" else "本节共 ${list.size} 份课件，点击查看页面。") }
        } else loadSlides(p.id, p.lessonId, p.title, presentationMetadata(p))
    }
    fun loadSlides(id: String, lessonId: String = "", fallbackTitle: String = "", recordedAt: String = "") = materialOperation("读取课件目录") { s, revision ->
        val (title, slides) = withContext(Dispatchers.IO) { rain.slides(s.data, id, lessonId) }
        if (revision == materialRevision) state.update { it.copy(localDocument = null, slideRecordedAt = recordedAt, slideTitle = if (title == "课件 $id" && fallbackTitle.isNotBlank()) fallbackTitle else title, presentationId = id, slides = slides, notice = if (slides.isEmpty()) "课件尚未开放可读取的页面。" else "已读取 ${slides.size} 页，可以提问或连续整理讲解笔记。") }
    }
    fun attach(slide: Slide) = materialOperation("读取课件页面") { s, revision ->
        val a = withContext(Dispatchers.IO) { readAttachment(s, slide) }
        if (revision == materialRevision) {
            closeMaterials()
            state.update { it.copy(pending = a, tab = 0, notice = "已添加 ${a.label}。可在学习页带入或提问。") }
            if (s.data.autoNotes) generateNote(a, s.data)
        }
    }
    fun attachNote(note: Note) {
        if (state.value.locked || state.value.data.notes.none { it.id == note.id }) return
        val current = state.value.data.notes.first { it.id == note.id }
        closeMaterials()
        state.update { it.copy(pending = Attachment(null, current.title, "", noteId = current.id), tab = 0, notice = "已选择原笔记；提问时将读取最新已保存内容。") }
    }
    fun openChatNote(id: String, edit: Boolean = false): Boolean {
        if (state.value.locked) return false
        val note = state.value.data.notes.find { it.id == id } ?: run { notice("这份笔记已删除或暂不可用。"); return false }
        val open = state.value.data.notes.find { it.id == noteReader.id }
        if (noteReader.id != id && noteReader.editing && noteReader.draft != open?.content) {
            tab(2); notice("请先保存或取消当前笔记的修改，再打开另一份笔记。"); return false
        }
        if (edit && !state.value.data.noteWritable(id)) { notice("课堂正在写入此笔记，请暂停并结束整理后再编辑。"); return false }
        if (noteReader.id != id) noteReader.open(note)
        if (edit) noteReader.beginEditing(note)
        state.update { it.copy(noteChatOrigin = it.data.chat().id) }
        closeMaterials(); tab(2)
        return true
    }
    fun closeNoteReader() {
        val origin = state.value.noteChatOrigin
        noteReader.close()
        state.update { it.copy(noteChatOrigin = null) }
        if (origin != null) {
            if (state.value.data.chat().id != origin) selectChat(origin)
            tab(0)
        }
    }
    private suspend fun appendSavedNoteResult(chatId: String, note: Note, label: String) {
        val ref = withContext(Dispatchers.Default) { chatNoteReference(note) }
        checkpoint { saved -> saved.copy(conversations = saved.conversations.map { chat ->
            if (chat.id == chatId) {
                val named = chat.withAutomaticTitle()
                val title = if (named.title == "新对话") note.title.replace(Regex("\\s+"), " ").trim().take(24).ifBlank { named.title } else named.title
                named.copy(title = title, messages = chat.messages + Message("assistant", label, noteRefs = listOf(ref), kind = "note_result"))
            } else chat
        }) }
    }
    fun saveChatAsNote() {
        val s = state.value
        val chat = s.data.chat()
        val input = chatTranscriptMessages(chat.messages)
        if (s.locked || storageBlocked) return
        if (input.isEmpty()) { notice("先进行对话，再保存原文。"); return }
        operation("保存对话原文") {
            val content = withContext(Dispatchers.IO) {
                input.joinToString("\n\n") { message ->
                    "## ${if (message.role == "user") "我" else "AI"}\n\n" + message.text +
                        (message.source?.takeIf { it.isNotBlank() }?.let { "\n\n来源：$it" } ?: "") +
                        (message.image?.let { sourceImageMarkdown(sourceImages.save(it), message.source ?: "对话中的资料") } ?: "")
                }
            }
            val note = Note(title = chat.title, content = content, source = "对话原文：${chat.title}", model = "对话原文（未调用 AI）")
            checkpoint { it.copy(notes = listOf(note) + it.notes) }
            appendSavedNoteResult(chat.id, note, "对话原文已保存")
            notice("对话原文已保存，可查看或编辑原笔记。")
        }
    }
    fun notes() {
        val snapshot = state.value
        operation("整理学习笔记") {
            val note = generateNote(snapshot.pending, snapshot.data)
            appendSavedNoteResult(snapshot.data.chat().id, note, "AI 整理的笔记已保存")
        }
    }
    fun batchValidation(pages: List<Slide>): String? = batchNoteValidation(state.value.data, pages)
    fun batchAllNotes() {
        val s = state.value
        startBatchNotes(s, allNotePages(s.slides))
    }
    fun batchNotes(from: String, to: String) {
        val s = state.value
        val pages = try { allNotePages(selectNotePages(from, to, s.slides)) }
            catch (error: Exception) { notice(error.message ?: "页码无效。"); return }
        startBatchNotes(s, pages)
    }
    private fun startBatchNotes(s: UiState, pages: List<Slide>) {
        if (s.locked || storageBlocked) return
        batchNoteValidation(s.data, pages)?.let { notice(it); return }
        operation("准备连续笔记") { buildBatchNotes(s, pages) }
    }
    private suspend fun buildBatchNotes(s: UiState, pages: List<Slide>) {
        val p = s.data.provider()
        val noteId = newId(); val createdAt = System.currentTimeMillis()
        val title = s.slideTitle + " · 第 "+pages.first().page+"–"+pages.last().page+" 页"
        var previousNotes = ""
        fun source(completed: Int) = title + "（已完成 "+completed+"/"+pages.size+" 页）"
        closeMaterials(); state.update { it.copy(tab = 2) }
        buildNotebook(s.slideTitle, pages,
            read = { page -> withContext(Dispatchers.IO) { readAttachment(s, page) } },
            generate = { attachment ->
                val text = StringBuilder()
                val prior = priorNoteContext(s.slideTitle, previousNotes)
                val input = (if (prior.isBlank()) emptyList() else listOf(Message("user", prior))) + Message("user", attachment.text.ifBlank { "请阅读课件图片并整理笔记。" }, attachment.image, attachment.label)
                ai.stream(authorizedProvider(p), noteMessages(s.data.notesPrompt, input, attachment.label, s.data.learnerProfile, s.data.superFocusNotes)) { text.append(it); state.update { u -> u.copy(noteDraft = text.toString()) } }
                text.toString()
            },
            checkpoint = { progress ->
                val note = Note(noteId, title, progress.markdown, source(progress.completed), p.name+" / "+p.model, createdAt)
                checkpoint { it.copy(notes = listOf(note) + it.notes.filterNot { n -> n.id == noteId }) }
                previousNotes = progress.markdown
            },
            onPage = { page, index, total -> state.update { it.copy(operation = "连续笔记 "+index+"/"+total+" · 第 "+page+" 页", noteDraft = "正在读取课件…") } }
        )
        notice("所选页面已保存为一份笔记，可编辑和导出。")
    }
    private suspend fun generateNote(a: Attachment?, request: Saved = state.value.data): Note {
        val s = request; val p = s.provider()
        val input = a?.let {
            val attached = if (it.noteId.isBlank()) null else s.notes.find { note -> note.id == it.noteId }
            require(it.noteId.isBlank() || attached != null) { "选择的笔记已删除或不可用，请重新选择。" }
            val body = attached?.let { note -> withContext(Dispatchers.Default) { chatNoteReference(note).snapshot } } ?: it.text.ifBlank { "请根据课件图片整理笔记。" }
            listOf(Message("user", body, it.image, it.label))
        } ?: chatTranscriptMessages(s.chat().messages).takeLast(24)
        require(input.isNotEmpty()) { "先选择课件页面或进行对话，再整理笔记。" }
        val selectedTitle = a?.takeIf { it.noteId.isNotBlank() }?.let { attachment -> s.notes.find { it.id == attachment.noteId }?.title } ?: a?.label
        val source = selectedTitle ?: "对话：${s.chat().title}"
        val text = StringBuilder()
        state.update { it.copy(operation = "自动整理笔记", noteDraft = "正在阅读资料…") }
        val notebook = if (a == null || a.noteId.isNotBlank()) withContext(Dispatchers.Default) {
            chatNotebookContext(s, input.lastOrNull { it.role == "user" }?.text.orEmpty(), a?.noteId.orEmpty())
        } else ChatNotebookContext()
        val contextualInput = (if (notebook.text.isBlank()) emptyList() else listOf(Message("user", notebook.text))) + input
        ai.stream(authorizedProvider(p), noteMessages(s.notesPrompt, contextualInput, source, s.learnerProfile, s.superFocusNotes)) { chunk -> text.append(chunk); state.update { it.copy(noteDraft = text.toString()) } }
        require(text.isNotBlank()) { "模型未生成笔记。" }
        val illustrations = if (a != null) sourceImageMarkdown(a.imageRef, a.label) else withContext(Dispatchers.IO) { input.filter { it.image != null }.distinctBy { it.image }.joinToString("") { sourceImageMarkdown(sourceImages.save(it.image!!), it.source ?: "对话中的资料") } }
        val note = Note(title = selectedTitle ?: s.chat().title, content = illustrations + text.toString(), source = source, model = "${p.name} / ${p.model}")
        checkpoint { it.copy(notes = listOf(note) + it.notes) }
        notice("笔记已保存，可在笔记页编辑和导出。")
        return note
    }
    fun openMaterials() { if (!state.value.locked) { materialRevision++; state.update { it.copy(materialsOpen = true, materialPane = "home") } } }
    fun closeMaterials() { materialRevision++; state.update { it.copy(materialsOpen = false) } }
    fun materialPane(value: String) {
        if (value !in setOf("home", "files", "notes", "rain", "pages")) return
        materialRevision++; state.update { it.copy(materialPane = value) }
    }
    fun browseRainMaterials() {
        if (state.value.locked) return
        materialRevision++
        state.update { it.copy(materialPane = "rain", localDocument = null, presentationId = "", slides = emptyList(), course = null, lesson = null, courseActivities = emptyList(), presentations = emptyList()) }
        if (state.value.data.session.isBlank()) { closeMaterials(); tab(1); notice("请先连接雨课堂，再选择学习资料。") } else loadCourses()
    }
    private fun showMaterial(doc: MaterialDocument, returnPane: String) {
        state.update { it.copy(localDocument = doc, materialReturnPane = returnPane, slideTitle = doc.material.title, slideRecordedAt = "创建时间：未提供\n导入时间：${readableTime(doc.material.importedAt)}" + if (doc.material.modifiedAt > 0) "\n文件修改时间：${readableTime(doc.material.modifiedAt)}" else "", presentationId = "", slides = doc.pages.map { p -> Slide(p.page, if (doc.material.format in listOf("pdf", "image") || p.images.isNotEmpty()) "local" else "", p.text) }, materialsOpen = true, materialPane = "pages") }
    }
    fun openMaterial(m: StudyMaterial) = materialOperation("读取本机资料") { s, revision ->
        val doc = withContext(Dispatchers.IO) { materials.read(m) }
        if (revision == materialRevision) showMaterial(doc, s.materialPane.takeIf { it == "files" || it == "notes" } ?: if (m.kind == "notes") "notes" else "files")
    }
    fun importMaterial(uri: Uri, kind: String) = materialOperation("导入学习资料") { _, revision ->
        var imported: MaterialDocument? = null
        try {
            val doc = withContext(NonCancellable + Dispatchers.IO) { materials.importDocument(uri, kind).also { imported = it } }
            val note = if (kind == "notes") Note(title = doc.material.title, content = doc.noteText.ifBlank { "# ${doc.material.title}\n\n原始笔记共 ${doc.pages.size} 页。点击「AI 讲解」选择原文页面；此处未伪造图片中的文字。" }, source = "外部导入 · ${doc.material.title} · ${readableTime(doc.material.importedAt)}", model = "外部导入", materialId = doc.material.id) else null
            val savedDoc = doc.copy(material = doc.material.copy(noteId = note?.id.orEmpty()))
            checkpoint { it.copy(materials = listOf(savedDoc.material) + it.materials, notes = if (note != null) listOf(note) + it.notes else it.notes) }
            if (revision == materialRevision) showMaterial(savedDoc, if (kind == "notes") "notes" else "files")
            notice("已导入 ${doc.material.title}，请选择页面或分段讲解。")
        } finally {
            imported?.let { doc -> if (state.value.data.materials.none { it.id == doc.material.id }) withContext(NonCancellable + Dispatchers.IO) { materials.remove(doc.material.id) } }
        }
    }
    private fun keepImage(a: Attachment): Attachment = if (a.image == null || a.imageRef.isNotBlank()) a else a.copy(imageRef = sourceImages.save(a.image))
    private fun readAttachment(s: UiState, slide: Slide): Attachment = keepImage(s.localDocument?.let { materials.attachment(it, slide.page) } ?: rain.attachment(s.slideTitle, slide))
    fun pagePreviewKey(s: UiState, slide: Slide) = "page:${s.data.region}:${imageDigest(s.data.session.toByteArray()).take(12)}:${s.localDocument?.material?.id ?: s.presentationId}:${slide.page}:${slide.cover}"
    fun deckPreviewKey(s: UiState, p: Presentation) = "deck:${s.data.region}:${imageDigest(s.data.session.toByteArray()).take(12)}:${p.lessonId}:${p.id}"
    suspend fun previewPage(s: UiState, slide: Slide): Attachment = withContext(Dispatchers.IO) { s.localDocument?.let { materials.attachment(it, slide.page) } ?: rain.attachment(s.slideTitle, slide) }
    suspend fun previewDeck(s: UiState, p: Presentation): Attachment = withContext(Dispatchers.IO) {
        val deck = if (p.isLesson) rain.lessonPresentations(s.data, p).firstOrNull() ?: error("本节课未开放课件预览。") else p
        val (title, pages) = rain.slides(s.data, deck.id, deck.lessonId)
        rain.attachment(title, pages.firstOrNull() ?: error("课件尚未开放页面。"))
    }
    suspend fun previewLocal(m: StudyMaterial, page: Int = 1): Attachment = withContext(Dispatchers.IO) { materials.attachment(materials.read(m), page) }
    fun explainPage(slide: Slide) = materialOperation("讲解并保存") { s, revision ->
        batchNoteValidation(s.data, listOf(slide))?.let { error(it) }
        val a = withContext(Dispatchers.IO) { readAttachment(s, slide) }
        if (revision == materialRevision) explainAttachment(a, s)
    }
    private suspend fun explainAttachment(a: Attachment, s: UiState) {
        closeMaterials()
        state.update { it.copy(tab = 0, pending = null) }
        val note = generateNote(a, s.data)
        val ref = withContext(Dispatchers.Default) { chatNoteReference(note) }
        checkpoint { d -> d.copy(conversations = d.conversations.map { c -> if (c.id == s.data.chat().id) c.copy(messages = c.messages + Message("user", a.text, a.image, a.label) + Message("assistant", note.content, noteRefs = listOf(ref))).withAutomaticTitle() else c }) }
    }
    fun explainNote(note: Note) {
        val s = state.value
        val material = s.data.materials.find { it.id == note.materialId }
        if (material != null) { openMaterial(material); return }
        if (note.content.length > 100_000) { notice("这份笔记较长，请导出后通过「导入笔记」分段讲解。"); return }
        batchNoteValidation(s.data, listOf(Slide(1, "", note.content)))?.let { notice(it); return }
        operation("讲解笔记并保存") {
            explainAttachment(Attachment(null, note.title, "用户选择的笔记原文：\n${note.content}"), s)
        }
    }
    fun setDownloadDirectory(uri: Uri?) = operation("设置下载文件夹") {
        val old = state.value.data.downloadTreeUri
        try {
            val name = if (uri == null) "" else withContext(Dispatchers.IO) { downloads.retain(uri) }
            checkpoint { it.copy(downloadTreeUri = uri?.toString().orEmpty(), downloadDirectoryName = name) }
            if (old != uri?.toString()) withContext(Dispatchers.IO) { downloads.release(old) }
            notice(if (uri == null) "下载时将询问保存位置。" else "下载文件夹已保存：$name")
        } finally { if (uri != null && state.value.data.downloadTreeUri != uri.toString()) withContext(NonCancellable + Dispatchers.IO) { downloads.release(uri.toString()) } }
    }
    fun prepareDeckDownload(): String? {
        if (state.value.locked || state.value.slides.isEmpty() || state.value.localDocument != null) return null
        exportDeck = state.value
        return downloadName(state.value.slideTitle, "pdf")
    }
    fun cancelDeckDownload() { exportDeck = null }
    fun downloadDeck(destination: Uri? = null) {
        val snapshot = exportDeck ?: return
        exportDeck = null
        operation("下载课件 PDF") {
            val temp = withContext(Dispatchers.IO) { java.io.File.createTempFile("slides-", ".pdf", getApplication<Application>().cacheDir) }
            try {
                withContext(Dispatchers.IO) {
                    temp.outputStream().buffered().use { out ->
                        val writer = ImagePdfWriter(out, snapshot.slideTitle)
                        snapshot.slides.forEachIndexed { index, slide ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            state.update { it.copy(operation = "下载课件 ${index + 1}/${snapshot.slides.size}") }
                            val a = readAttachment(snapshot, slide)
                            if (a.image != null) {
                                val source = boundedBitmap(android.util.Base64.decode(a.image.substringAfter(','), android.util.Base64.DEFAULT))
                                try { val page = captionedBitmap(source, "第 ${slide.page} 页"); try { writer.add(page) } finally { page.recycle() } } finally { source.recycle() }
                            } else textPageBitmaps(a.text, "第 ${slide.page} 页").forEach { page -> try { writer.add(page) } finally { page.recycle() } }
                        }
                        writer.finish()
                    }
                    downloads.write(snapshot.data.downloadTreeUri, destination, downloadName(snapshot.slideTitle, "pdf"), "application/pdf") { temp.inputStream() }
                }
                notice("课件 PDF 已保存，共 ${snapshot.slides.size} 个开放页面；由页面重建，并非原始 PPT。")
            } finally { temp.delete() }
        }
    }
    fun exportNote(note: Note, text: String, destination: Uri? = null) = operation("导出 Markdown") {
        val tree = state.value.data.downloadTreeUri
        withContext(Dispatchers.IO) {
            val temp = java.io.File.createTempFile("note-export-", ".md", getApplication<Application>().cacheDir)
            try {
                temp.outputStream().buffered().use { sourceImages.writeExport(text + noteAnnotationExport(note.copy(content = text)) + "\n\n---\n来源：${note.source}\n模型：${note.model}\n", it) }
                downloads.write(tree, destination, downloadName(note.title, "md"), "text/markdown") { temp.inputStream() }
            } finally { temp.delete() }
        }
        notice("Markdown 笔记已保存。")
    }
    fun discoverClasses() = operation("查找正在进行的课堂") {
        val lessons = withContext(Dispatchers.IO) { rain.onLessons(state.value.data) }
        state.update { it.copy(liveLessons = lessons) }
        notice(if (lessons.isEmpty()) "当前账号没有正在进行的课堂。老师开课后可刷新。" else "请选择正在上的课程，进入后开始跟随。")
    }
    fun startClass(lesson: LiveLesson) {
        val saved = state.value.data
        val p = saved.provider()
        if (!saved.providerReady(p) || p.model.isBlank() || !p.images) { notice("上课模式需要先配置支持图片的模型。"); tab(3); settingsPage("models"); return }
        if (state.value.locked) return
        val existing = saved.classRun
        if (existing != null && !existing.ended && (existing.lesson.id != lesson.id || existing.lesson.classroomId != lesson.classroomId)) { notice("请先结束上一节的上课模式，再开始另一节课。"); return }
        state.update { it.copy(busy = true, operation = "连接课堂并查找原笔记", classUi = ClassUi(running = true, paused = true, status = "正在准备课堂…")) }
        classStartJob = viewModelScope.launch {
            var access: LessonAccess? = null
            try {
                classWriter?.join(); classFeed?.join()
                if (!ensureClassService()) return@launch
                val authorized = withContext(Dispatchers.IO) { rain.enterLesson(saved, lesson) }
                require(state.value.data.session == saved.session && state.value.data.region == saved.region) { "账号已变化，请重新进入课堂。" }
                val newNoteId = newId(); val startedAt = System.currentTimeMillis()
                val providerId = p.id
                checkpoint { prepareClassNotebook(it, lesson, authorized.userId, providerId, newNoteId, startedAt) }
                access = authorized
                notice("已接回课堂笔记，跳过已保存的 "+state.value.data.classRun!!.completed.size+" 页。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = e.message?.take(200) ?: "连接课堂失败，原笔记已保留。"
                state.update { it.copy(classUi = ClassUi(running = it.data.classRun?.let { run -> !run.ended || run.pending.isNotEmpty() } == true, paused = true, status = reason), notice = reason) }
            }
            finally { state.update { it.copy(busy = false, operation = "") } }
            access?.let { resumeClassWithAccess(it) }
        }
        job = classStartJob
    }
    fun resumeClass() = resumeClassWithAccess(null)
    private fun resumeClassWithAccess(preparedAccess: LessonAccess?) {
        if (state.value.busy || state.value.chatgptLoginActive || storageBlocked || state.value.classUi.let { it.running && !it.paused }) return
        if (classResumeStart?.isActive == true) return
        val run = state.value.data.classRun?.takeUnless { it.ended && it.pending.isEmpty() } ?: return
        val p = state.value.data.providers.find { it.id == run.providerId }
        if (p == null || !state.value.data.providerReady(p) || !p.images) { notice("请在模型与账号中配置支持图片的模型，再进入课堂。原笔记会保留。"); return }
        state.update { it.copy(classUi = it.classUi.copy(running = true, paused = true, status = "正在启动后台上课服务…")) }
        classResumeStart = viewModelScope.launch {
            if (ensureClassService()) launchClassRuntime(preparedAccess)
        }
    }
    private suspend fun ensureClassService(): Boolean {
        val app = getApplication<Application>() as? StudyApplication ?: return true
        return try { app.awaitClassFollowService(); true }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            val reason = "后台上课服务未能启动，上课已暂停；待整理页面已保留，请回到应用重试。"
            state.update { it.copy(classUi = it.classUi.copy(running = it.data.classRun?.let { run -> !run.ended || run.pending.isNotEmpty() } == true, paused = true, connected = false, status = reason), notice = reason) }
            app.updateClassFollowStatus { it.copy(serviceRunning = false, error = reason) }
            false
        }
    }
    private fun launchClassRuntime(preparedAccess: LessonAccess?) {
        val run = state.value.data.classRun?.takeUnless { it.ended && it.pending.isEmpty() } ?: return
        val p = state.value.data.providers.find { it.id == run.providerId } ?: return
        val previousWriter = classWriter
        val previousFeed = classFeed
        val saved = state.value.data
        val prompt = saved.notesPrompt
        state.update { it.copy(classUi = ClassUi(running = true, paused = false, status = "正在连接课堂…")) }
        classWriter = viewModelScope.launch {
            previousWriter?.join()
            try {
                while (isActive) {
                    val current = state.value.data.classRun ?: break
                    if (current.ended && current.pending.isEmpty()) { state.update { it.copy(classUi = ClassUi(status = "本节课堂笔记已完成")) }; break }
                    if (!current.ended && classAccess == null) { classWake.receive(); continue }
                    val page = current.pending.firstOrNull()
                    if (page == null) { classWake.receive(); continue }
                    if (page.key in current.completed) {
                        checkpoint { d -> d.copy(classRun = d.classRun?.let { r -> r.copy(pending = r.pending.filterNot { it.key == page.key }) }) }
                        continue
                    }
                    state.update { it.copy(classUi = it.classUi.copy(current = page, status = "正在讲解第 ${page.page} 页")) }
                    // An old canceled HTTP request may finish after resume starts a new feed.
                    // Its rotated authorization belongs only to the access object that sent it.
                    val pageAccess = classAccess
                    val (title, slides) = withContext(Dispatchers.IO) { rain.slides(saved, page.presentationId, lessonId = if (current.ended) current.lesson.id else "", authorization = pageAccess?.authorization ?: "", onAuthorization = { pageAccess?.authorization = it }) }
                    val slide = slides.find { it.page == page.page } ?: error("第 ${page.page} 页尚未开放，已暂停；可稍后继续。")
                    val a = withContext(Dispatchers.IO) { keepImage(rain.attachment(title, slide)) }
                    val prior = if (current.completed.isEmpty()) "" else priorNoteContext(current.lesson.title, state.value.data.notes.find { it.id == run.noteId }?.content.orEmpty())
                    val text = StringBuilder()
                    kotlin.run {
                        val input = (if (prior.isBlank()) emptyList() else listOf(Message("user", prior))) + Message("user", a.text.ifBlank { "讲解这一页课件。" }, a.image, a.label)
                        ai.stream(authorizedProvider(p!!), noteMessages(prompt, input, a.label, saved.learnerProfile, state.value.data.superFocusNotes)) { chunk -> text.append(chunk); state.update { it.copy(noteDraft = text.toString()) } }
                    }
                    require(text.isNotBlank()) { "这一页没有生成有效讲解。" }
                    checkpoint { d ->
                        appendClassNotebookPage(d, run.noteId, page, text.toString(), "$title · 第 "+page.page+" 页", a.imageRef)
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
                classAccess = preparedAccess ?: withContext(Dispatchers.IO) { rain.enterLesson(saved, run.lesson) }
                classWake.trySend(Unit)
                var attempt = 0
                while (isActive) {
                    try {
                        rain.followLesson(saved, run.lesson, classAccess!!).collect { signal -> when (signal) {
                            LiveSignal.Connected -> { state.update { it.copy(classUi = it.classUi.copy(connected = true, status = if (it.classUi.current != null) it.classUi.status else "课堂已连接，等待课件页")) } }
                            is LiveSignal.Pages -> {
                                checkpoint { d -> d.copy(classRun = d.classRun?.let { enqueueClassPages(it, signal.pages) }) }
                                state.update { it.copy(classUi = it.classUi.copy(lastPage = signal.pages.lastOrNull() ?: it.classUi.lastPage)) }
                                classWake.trySend(Unit)
                            }
                            LiveSignal.Ended -> { checkpoint { it.copy(classRun = it.classRun?.copy(ended = true)) }; state.update { it.copy(classUi = it.classUi.copy(connected = false, status = "老师已下课，正在保存剩余讲解")) }; if (state.value.data.classRun?.pending?.isEmpty() == true) { classWriter?.cancel(); state.update { it.copy(classUi = ClassUi(status = "本节课堂笔记已完成")) } } }
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
        classStartJob?.cancel(); classResumeStart?.cancel()
        classFeed?.cancel(); classWriter?.cancel(); classAccess = null
        state.update { it.copy(classUi = it.classUi.copy(running = it.data.classRun?.let { run -> !run.ended || run.pending.isNotEmpty() } == true, paused = true, connected = false, current = null, status = reason)) }
        persist()
    }
    fun background() { if (state.value.noteDraftSaving) draftWrites.trySend(Unit); persist() }
    fun endClass() { pauseClass(); data { it.copy(classRun = it.classRun?.copy(ended = true, pending = emptyList())) }; state.update { it.copy(classUi = ClassUi(status = "本节已结束，已完成的笔记已保留"), noteDraft = "") } }
    // After stopping the runtime, callers restoring fixture storage must wait
    // for any non-cancellable notebook checkpoint already being committed.
    internal suspend fun awaitClassPersistence() {
        classStartJob?.join(); classResumeStart?.join(); classFeed?.join(); classWriter?.join()
        saveNow()
    }
    private suspend fun saveNow() = withContext(NonCancellable + Dispatchers.IO) { disk.withLock { check(!storageBlocked); vault.save(state.value.data) } }
    private fun setChatGptAccount(account: ChatGptAccount) { state.update { u -> u.copy(data = u.data.copy(chatgptAccounts = u.data.chatgptAccounts.filterNot { it.id == account.id } + account)) } }
    private suspend fun authorizedProvider(p: Provider): Provider {
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
    override fun onCleared() { login?.close(); stopRainLoginService(); authReceiver?.close(); authJob?.cancel(); getApplication<Application>().stopService(Intent(getApplication(), AuthorizationService::class.java)); classFeed?.cancel(); classWriter?.cancel(); super.onCleared() }
}
