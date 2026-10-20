package cn.studybridge.mobile

import org.json.JSONObject

// Import only the local original attached to an obsolete send intent. No web
// browser, account cookie or unfinished answer is opened or sent during upgrade.
fun migrateLegacyWebNotes(record: JSONObject, saved: Saved): Saved {
    val pending = record.optJSONObject("webPending") ?: return saved
    val image = pending.optString("imageRef").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return saved
    val id = pending.optString("noteId")
    if (saved.notes.any { it.content.contains("study-image:$image") }) return saved
    val label = pending.optString("label", "旧版待完成资料").take(200)
    val original = sourceImageMarkdown(image, label) + "> 旧版尚未完成的网页回答未导入；这里保留的是本机原始资料。\n\n"
    val note = saved.notes.find { it.id == id }
    return saved.copy(notes = if (note == null) listOf(Note(title = label, content = original, source = "旧版本机资料", model = "原始资料")) + saved.notes else saved.notes.map { if (it.id == id) it.copy(content = it.content + original) else it })
}
