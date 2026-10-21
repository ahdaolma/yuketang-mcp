package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject

data class TrashedNote(val note: Note, val deletedAt: Long = System.currentTimeMillis())
data class NoteRevision(val id: String = newId(), val note: Note, val savedAt: Long = System.currentTimeMillis())
val NOTE_SORT_OPTIONS = linkedMapOf("updated" to "最近修改", "created" to "最近创建", "title" to "标题")
val NOTE_REVIEW_STATES = linkedMapOf("unreviewed" to "未复习", "reviewing" to "复习中", "mastered" to "已掌握")
fun normalizedNoteTags(tags: List<String>) = tags.map { it.trim().removePrefix("#").take(32) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(12)
fun Note.notebookJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("content", content).put("source", source).put("model", model).put("createdAt", createdAt).put("materialId", materialId).put("pinned", pinned).put("folderId", folderId).put("classNotebook", classNotebook?.json()).put("annotations", JSONArray(annotations.map { it.json() })).put("tags", JSONArray(tags)).put("reviewState", reviewState).put("relatedNoteIds", JSONArray(relatedNoteIds))
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
fun notebookNoteFromJson(o: JSONObject) = Note(o.getString("id"), o.getString("title"), o.getString("content"), o.optString("source"), o.optString("model"), o.optLong("createdAt", 0), o.optString("materialId"), o.optBoolean("pinned"), o.optString("folderId"), o.optJSONObject("classNotebook")?.let(::classNotebookBindingFromJson), (o.optJSONArray("annotations") ?: JSONArray()).objects().map(::noteAnnotationFromJson), normalizedNoteTags((o.optJSONArray("tags") ?: JSONArray()).strings()), o.optString("reviewState", "unreviewed").takeIf { it in NOTE_REVIEW_STATES } ?: "unreviewed", (o.optJSONArray("relatedNoteIds") ?: JSONArray()).strings().distinct().take(100))
fun putNotebookLibrary(json: JSONObject, data: Saved) {
    json.put("noteTrash", JSONArray(data.noteTrash.map { JSONObject().put("note", it.note.notebookJson()).put("deletedAt", it.deletedAt) }))
    json.put("noteHistory", JSONArray(data.noteHistory.map { JSONObject().put("id", it.id).put("note", it.note.notebookJson()).put("savedAt", it.savedAt) }))
    json.put("noteModifiedTimes", JSONObject(data.noteModifiedTimes)); json.put("noteSort", data.noteSort)
}
fun notebookTrashFromJson(o: JSONObject) = (o.optJSONArray("noteTrash") ?: JSONArray()).objects().map { TrashedNote(notebookNoteFromJson(it.getJSONObject("note")), it.optLong("deletedAt")) }.distinctBy { it.note.id }
fun notebookHistoryFromJson(o: JSONObject) = (o.optJSONArray("noteHistory") ?: JSONArray()).objects().map { NoteRevision(it.getString("id"), notebookNoteFromJson(it.getJSONObject("note")), it.optLong("savedAt")) }.distinctBy { it.id }
fun notebookModifiedTimesFromJson(o: JSONObject): Map<String, Long> = o.optJSONObject("noteModifiedTimes")?.let { times -> times.keys().asSequence().associateWith { times.optLong(it) } } ?: emptyMap()
fun Saved.imageBearingNotes(): List<Note> = notes + noteTrash.map { it.note } + noteHistory.map { it.note }

// Called by manual mutation paths. Class page appends are checkpointed separately.
// Never expire deleted notes automatically; permanent removal is an explicit action.
fun recordNotebookMutation(before: Saved, after: Saved, now: Long = System.currentTimeMillis()): Saved {
    val previous = before.notes.associateBy { it.id }
    val currentIds = after.notes.map { it.id }.toSet()
    val removed = before.notes.filter { it.id !in currentIds }.map { TrashedNote(it, now) }
    val changed = after.notes.mapNotNull { note -> previous[note.id]?.takeIf { it != note } }
    val revisions = changed.filter { old -> after.notes.first { it.id == old.id }.let { it.content != old.content || it.annotations != old.annotations } }
        .map { NoteRevision(note = it, savedAt = now) }
    val seen = mutableMapOf<String, Int>(); var chars = 0
    val history = (revisions + after.noteHistory).sortedByDescending { it.savedAt }.filter { revision ->
        val count = seen.getOrDefault(revision.note.id, 0)
        if (count >= 20 || chars + revision.note.content.length > 6_000_000) false
        else { seen[revision.note.id] = count + 1; chars += revision.note.content.length; true }
    }.take(200)
    val modified = after.noteModifiedTimes + changed.associate { it.id to now } + after.notes.filter { it.id !in previous }.associate { it.id to now }
    return after.copy(noteTrash = (removed + after.noteTrash).distinctBy { it.note.id }, noteHistory = history, noteModifiedTimes = modified)
}

fun Saved.restoreTrashedNote(id: String, now: Long = System.currentTimeMillis()): Saved {
    val trashed = noteTrash.firstOrNull { it.note.id == id } ?: error("回收站中没有这份笔记。")
    val collides = notes.any { it.id == id }
    val restored = trashed.note.copy(id = if (collides) newId() else id, title = trashed.note.title + if (collides) "（恢复副本）" else "", folderId = trashed.note.folderId.takeIf { folder -> noteFolders.any { it.id == folder } } ?: "", classNotebook = if (collides) null else trashed.note.classNotebook)
    return copy(notes = listOf(restored) + notes, noteTrash = noteTrash.filterNot { it.note.id == id }, noteModifiedTimes = noteModifiedTimes + (restored.id to now))
}
fun Saved.restoreNoteRevision(id: String): Saved {
    val revision = noteHistory.find { it.id == id } ?: error("这份历史版本已不可用。")
    val restored = revision.note.copy(id = newId(), title = revision.note.title + "（历史恢复）", classNotebook = null, pinned = false, folderId = revision.note.folderId.takeIf { folder -> noteFolders.any { it.id == folder } } ?: "")
    // Restore as a separate notebook, so a live class/draft is never overwritten.
    return copy(notes = listOf(restored) + notes)
}
fun Saved.purgeTrashedNotes(ids: Set<String>): Saved {
    val removed = noteTrash.map { it.note.id }.filter { it in ids }.toSet() - notes.map { it.id }.toSet()
    fun Note.cleaned() = copy(relatedNoteIds = relatedNoteIds.filterNot { it in removed })
    return copy(notes = notes.map { it.cleaned() }, noteTrash = noteTrash.filterNot { it.note.id in removed }.map { it.copy(note = it.note.cleaned()) }, noteHistory = noteHistory.filterNot { it.note.id in removed }.map { it.copy(note = it.note.cleaned()) }, noteModifiedTimes = noteModifiedTimes - removed, noteDrafts = noteDrafts.filterNot { it.noteId in removed }, noteReadingPositions = noteReadingPositions.filterNot { it.noteId in removed })
}
fun Saved.withNoteDetails(id: String, tags: List<String>, review: String, links: List<String>): Saved {
    require(review in NOTE_REVIEW_STATES) { "请选择有效复习状态。" }
    val valid = notes.map { it.id }.toSet()
    return copy(notes = notes.map { if (it.id == id) it.copy(tags = normalizedNoteTags(tags), reviewState = review, relatedNoteIds = links.filter { link -> link != id && link in valid }.distinct().take(100)) else it })
}
fun libraryNotes(data: Saved, query: String, folder: String?, tag: String?, review: String?): List<Note> {
    val filtered = findNotes(data.notes, query).filter { (folder == null || it.folderId == folder) && (tag == null || it.tags.any { t -> t.equals(tag, true) }) && (review == null || it.reviewState == review) }
    val sorted = when (data.noteSort) {
        "title" -> filtered.sortedWith(compareBy<Note> { it.title.lowercase() }.thenBy { it.id })
        "created" -> filtered.sortedByDescending { it.createdAt }
        else -> filtered.sortedByDescending { data.noteModifiedTimes[it.id] ?: it.createdAt }
    }
    return sorted.sortedByDescending { it.pinned }
}
fun linkedNotes(data: Saved, note: Note): List<Note> = data.notes.filter { it.id != note.id && (it.id in note.relatedNoteIds || note.id in it.relatedNoteIds) }

data class NoteTemplate(val name: String, val title: String, val content: String)
val NOTE_TEMPLATES = listOf(
    NoteTemplate("空白笔记", "新笔记", ""),
    NoteTemplate("课堂重点", "课堂重点", "# 课堂重点\n\n## 核心结论\n\n## 条件与公式\n\n## 易错点\n\n## 我的疑问\n\n## 课后复习\n\n- [ ] 回顾课件\n- [ ] 完成自测\n"),
    NoteTemplate("错题整理", "错题整理", "# 错题整理\n\n## 题目\n\n## 错误原因\n\n## 正确思路\n\n## 相关知识\n\n## 再做一遍\n\n- [ ] 独立完成\n"),
    NoteTemplate("复习提纲", "复习提纲", "# 复习提纲\n\n## 本章主线\n\n## 必须掌握\n\n## 概念对比\n\n## 自测问题\n\n- [ ] 能用自己的话解释\n- [ ] 能解决对应例题\n")
)
