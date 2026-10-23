package cn.studybridge.mobile

import org.json.JSONObject

data class NoteFolder(val id: String = newId(), val name: String, val parentId: String = "", val classroomKey: String = "") {
    fun json(includeAssociation: Boolean = true) = JSONObject().put("id", id).put("name", name).put("parentId", parentId).apply {
        if (includeAssociation) put("classroomKey", classroomKey)
    }
}
fun noteFolderFromJson(value: JSONObject) = NoteFolder(value.getString("id"), value.getString("name"), value.optString("parentId"), value.optString("classroomKey"))
fun normalizedNoteFolders(folders: List<NoteFolder>): List<NoteFolder> {
    val unique = folders.distinctBy { it.id }
    val roots = unique.filter { it.parentId.isEmpty() }.map { it.id }.toSet()
    return unique.map { if (it.parentId.isNotEmpty() && (it.parentId !in roots || it.parentId == it.id)) it.copy(parentId = "") else it }
}
fun Saved.folderScope(id: String): Set<String> = if (id.isEmpty()) setOf("") else setOf(id) + noteFolders.filter { it.parentId == id }.map { it.id }
fun Saved.effectiveFolder(note: Note) = note.folderId.takeIf { id -> noteFolders.any { it.id == id } } ?: ""
fun Saved.folderName(note: Note) = noteFolders.find { it.id == note.folderId }?.name ?: "未分类"
fun Saved.folderPath(id: String): String {
    val folder = noteFolders.find { it.id == id } ?: return "未整理"
    val parent = noteFolders.find { it.id == folder.parentId }
    return if (parent == null) folder.name else "${parent.name} / ${folder.name}"
}
fun Saved.folderNoteCount(id: String): Int {
    val scope = folderScope(id)
    return notes.count { effectiveFolder(it) in scope }
}
fun Saved.withNoteFolder(id: String?, name: String, parentId: String? = null): Saved {
    val value = name.trim()
    val old = id?.let { target -> noteFolders.find { it.id == target } }
    require(id == null || old != null) { "分类已不存在。" }
    val parent = parentId ?: old?.parentId.orEmpty()
    require(parent.isEmpty() || noteFolders.any { it.id == parent && it.parentId.isEmpty() }) { "章节只能建在笔记本下。" }
    require(old == null || parent == old.parentId) { "重命名不能改变分类层级。" }
    require(value.isNotEmpty() && value.length <= 30) { "名称请填写 1–30 个字。" }
    require(value !in setOf("全部", "全部笔记", "未分类", "未整理")) { "请换一个名称。" }
    require(noteFolders.none { it.id != id && it.parentId == parent && it.name.equals(value, true) }) { "同一位置已有同名分类。" }
    return copy(noteFolders = if (id == null) noteFolders + NoteFolder(name = value, parentId = parent) else noteFolders.map { if (it.id == id) it.copy(name = value) else it })
}
fun Saved.withoutNoteFolder(id: String): Saved {
    val folder = noteFolders.find { it.id == id } ?: return this
    val removed = folderScope(id)
    return copy(noteFolders = noteFolders.filterNot { it.id in removed }, notes = notes.map { if (it.folderId in removed) it.copy(folderId = folder.parentId) else it })
}
fun Saved.withMovedNotes(ids: Set<String>, destination: String): Saved {
    require(ids.isNotEmpty() && notes.map { it.id }.toSet().containsAll(ids)) { "部分笔记已不存在，请重新选择。" }
    require(destination.isEmpty() || noteFolders.any { it.id == destination }) { "目标分类已不存在。" }
    return copy(notes = notes.map { if (it.id in ids) it.copy(folderId = destination) else it })
}
fun Saved.withClassNotebookFolder(binding: ClassNotebookBinding, title: String): Pair<Saved, String> {
    val key = imageDigest("${binding.region}\u0000${binding.account}\u0000${binding.classroomId}".toByteArray(Charsets.UTF_8))
    noteFolders.firstOrNull { it.parentId.isEmpty() && it.classroomKey == key }?.let { return this to it.id }
    val base = title.trim().take(24).ifBlank { "课堂笔记" }
    var name = base; var suffix = 2
    while (noteFolders.any { it.parentId.isEmpty() && it.name.equals(name, true) } || name in setOf("全部", "全部笔记", "未分类", "未整理")) name = "$base（${suffix++}）"
    val folder = NoteFolder(name = name, classroomKey = key)
    return copy(noteFolders = noteFolders + folder) to folder.id
}

val NOTE_QUICK_VIEWS = linkedMapOf("all" to "全部", "pinned" to "已置顶", "review" to "待复习", "class" to "课堂笔记")
fun Note.matchesQuickView(view: String) = when (view) { "pinned" -> pinned; "review" -> reviewState != "mastered"; "class" -> classNotebook != null; else -> true }
