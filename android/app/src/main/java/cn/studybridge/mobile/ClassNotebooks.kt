package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject

data class ClassNotebookBinding(val region: String, val account: String, val lessonId: String, val classroomId: String, val completed: List<String> = emptyList()) {
    fun json() = JSONObject().put("region", region).put("account", account).put("lessonId", lessonId).put("classroomId", classroomId).put("completed", JSONArray(completed))
}
fun classNotebookBindingFromJson(o: JSONObject) = ClassNotebookBinding(o.getString("region"), o.getString("account"), o.getString("lessonId"), o.getString("classroomId"), (o.optJSONArray("completed") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } })

// Identity comes from the authorized lesson response, never a mutable title or
// session cookie. A fresh login to the same server account can reuse its notebook.
fun prepareClassNotebook(saved: Saved, lesson: LiveLesson, userId: String, providerId: String, newNoteId: String = newId(), now: Long = System.currentTimeMillis()): Saved {
    require(userId.isNotBlank()) { "课堂没有返回账号标识，暂不能安全关联笔记。" }
    val identity = ClassNotebookBinding(saved.region, imageDigest(userId.toByteArray()), lesson.id, lesson.classroomId)
    val previous = saved.classRun
    require(previous == null || previous.ended || previous.lesson.id == lesson.id && previous.lesson.classroomId == lesson.classroomId) { "请先结束上一节课堂。" }
    val previousBinding = previous?.let { run -> saved.notes.find { it.id == run.noteId }?.classNotebook }
    require(previous == null || previous.ended || previousBinding == null || previousBinding.copy(completed = emptyList()) == identity) { "当前课堂属于另一账号，请先结束原课堂。" }
    val found = saved.notes.firstOrNull { it.classNotebook?.copy(completed = emptyList()) == identity }
    val current = previous?.takeIf { !it.ended && it.lesson.id == lesson.id && it.lesson.classroomId == lesson.classroomId }
    // An unfinished legacy run can be upgraded, but historical notes without
    // identity are never guessed by title, model name or source description.
    val old = found ?: current?.let { run -> saved.notes.find { it.id == run.noteId && it.classNotebook == null } }
    val completed = (old?.classNotebook?.completed.orEmpty() + current?.takeIf { it.noteId == old?.id }?.completed.orEmpty()).distinct()
    val note = old?.copy(classNotebook = identity.copy(completed = completed)) ?: Note(newNoteId, "${lesson.title} · 课堂笔记", "# ${lesson.title}\n\n> 根据课堂展示的课件逐页整理；补充内容标为 AI 讲解，不包含老师口述。\n\n", "课堂：${lesson.title}", saved.providers.find { it.id == providerId }?.let { "${it.name} / ${it.model}" }.orEmpty(), now, classNotebook = identity)
    val run = if (current != null && current.noteId == note.id) current.copy(lesson = lesson, completed = completed) else ClassRun(lesson, note.id, providerId, now, completed)
    return saved.copy(notes = if (old != null) saved.notes.map { if (it.id == note.id) note else it } else listOf(note) + saved.notes, classRun = run)
}

fun appendClassNotebookPage(saved: Saved, noteId: String, page: LivePage, content: String, label: String, imageRef: String): Saved {
    val run = saved.classRun?.takeIf { it.noteId == noteId } ?: error("课堂已切换，已保留待恢复内容。")
    val note = saved.notes.find { it.id == noteId } ?: error("课堂笔记已被移除。")
    if (page.key in run.completed || page.key in note.classNotebook?.completed.orEmpty()) return saved.copy(classRun = run.copy(completed = (run.completed + note.classNotebook?.completed.orEmpty()).distinct(), pending = run.pending.filterNot { it.key == page.key }))
    val completed = (run.completed + page.key).distinct()
    return saved.copy(classRun = run.copy(completed = completed, pending = run.pending.filterNot { it.key == page.key }), notes = saved.notes.map {
        if (it.id == noteId) it.copy(content = it.content + "## $label\n\n${sourceImageMarkdown(imageRef, label)}$content\n\n---\n\n", classNotebook = it.classNotebook?.copy(completed = completed)) else it
    })
}

fun mergeNoteEdit(base: String, edited: String, current: String): String {
    require(current.startsWith(base)) { "原文在编辑期间发生了变化，草稿已保留。请复制修改内容，重新打开原文后核对。" }
    return edited + current.substring(base.length)
}

// Text edits and personal marks can coexist with append-only class generation.
// Removing a notebook/page still uses noteWritable and stays separately guarded.
fun UiState.noteEditable(id: String): Boolean = ready && storageError.isBlank() && !busy && !chatgptLoginActive && data.notes.any { it.id == id } &&
    (data.noteWritable(id) || data.classRun?.noteId == id)
