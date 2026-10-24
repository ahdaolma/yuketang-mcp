package cn.studybridge.mobile

import org.json.JSONObject

// Exact source anchors deliberately fail closed after edits. A remark is kept
// for review even when its old location no longer matches the document.
data class NoteAnnotation(val id: String = newId(), val sourceStart: Int, val sourceLength: Int, val sourceHash: String, val start: Int, val end: Int, val quote: String, val kind: String, val comment: String = "", val renderPrefix: String = "") {
    fun isAttached(content: String): Boolean = sourceStart >= 0 && sourceLength >= 0 && sourceStart.toLong() + sourceLength <= content.length &&
        imageDigest(content.substring(sourceStart, sourceStart + sourceLength).toByteArray()) == sourceHash
    fun json() = JSONObject().put("id", id).put("sourceStart", sourceStart).put("sourceLength", sourceLength).put("sourceHash", sourceHash).put("start", start).put("end", end).put("quote", quote).put("kind", kind).put("comment", comment).put("renderPrefix", renderPrefix)
}
fun noteAnnotationFromJson(o: JSONObject) = NoteAnnotation(o.getString("id"), o.getInt("sourceStart"), o.getInt("sourceLength"), o.getString("sourceHash"), o.getInt("start"), o.getInt("end"), o.getString("quote"), o.getString("kind"), o.optString("comment"), o.optString("renderPrefix"))
fun noteAnnotationExport(note: Note): String {
    if (note.annotations.isEmpty()) return ""
    return "\n\n## 我的标记与备注\n\n" + note.annotations.joinToString("\n\n") { a ->
        val label = when (a.kind) { "highlight" -> "重点"; "underline" -> "下划线"; else -> "备注" }
        "- $label${if (a.isAttached(note.content)) "" else "（原文位置待核对）"}：${a.quote.replace('\n', ' ')}" + if (a.comment.isBlank()) "" else "\n  ${a.comment.replace("\n", "\n  ")}"
    }
}
