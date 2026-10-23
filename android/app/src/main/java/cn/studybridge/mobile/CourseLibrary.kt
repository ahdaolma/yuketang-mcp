package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

enum class SourceTimeKind(val label: String) {
    PRESENTATION_CREATED("创建时间"), ACTIVITY_PUBLISHED("发布时间"), LESSON_STARTED("上课时间"), ACTIVITY_RECORDED("记录时间")
}
data class SourceTime(val kind: SourceTimeKind, val raw: String, val epochMillis: Long?, val sourceField: String)
enum class CourseOrder(val label: String) { NEWEST("时间从新到旧"), OLDEST("时间从旧到新"), NAME("名称顺序"), TEACHER("按老师分组") }
private val sourceZone = ZoneId.of("Asia/Shanghai")
private val sourceDisplay = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
private val maxSourceEpoch = Instant.parse("2101-01-01T00:00:00Z").toEpochMilli() - 1

fun parseSourceEpoch(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty() || text.length > 100) return null
    if (text.matches(Regex("[+-]?\\d+"))) {
        val n = text.toLongOrNull() ?: return null
        if (n <= 0) return null
        val epoch = if (n < 100_000_000_000L) runCatching { Math.multiplyExact(n, 1000L) }.getOrNull() ?: return null else n
        return epoch.takeIf { it in 1..maxSourceEpoch }
    }
    val instant = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
    if (instant != null) return runCatching { instant.toEpochMilli().takeIf { it in 1..maxSourceEpoch } }.getOrNull()
    runCatching { LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(sourceZone).toInstant().toEpochMilli() }.getOrNull()?.let { return it.takeIf { epoch -> epoch in 1..maxSourceEpoch } }
    val patterns = listOf("uuuu-MM-dd HH:mm:ss.SSS", "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu-MM-dd'T'HH:mm:ss.SSS", "uuuu-MM-dd'T'HH:mm:ss", "uuuu/MM/dd HH:mm:ss", "uuuu/MM/dd HH:mm", "uuuu年M月d日 HH:mm:ss", "uuuu年M月d日 HH:mm", "uuuu年M月d日HH:mm:ss", "uuuu年M月d日HH:mm", "uuuu年M月d日 H时m分s秒", "uuuu年M月d日H时m分s秒", "uuuu-MM-dd", "uuuu/MM/dd", "uuuu年M月d日")
    for (pattern in patterns) {
        val format = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
        val value = runCatching { if ('H' in pattern) LocalDateTime.parse(text, format).atZone(sourceZone).toInstant() else LocalDate.parse(text, format).atStartOfDay(sourceZone).toInstant() }.getOrNull() ?: continue
        return value.toEpochMilli().takeIf { it in 1..maxSourceEpoch }
    }
    return null
}
fun sourceTime(kind: SourceTimeKind, raw: String, field: String) = SourceTime(kind, raw.trim(), parseSourceEpoch(raw), field)
private val createdKeys = listOf("created_at", "create_time", "created_time", "createdAt", "createTime")
private fun JSONObject.nonempty(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
private fun collectTimes(o: JSONObject, keys: List<String>, kind: SourceTimeKind) = keys.mapNotNull { key -> o.nonempty(key)?.let { sourceTime(kind, it, key) } }
fun activityTimes(o: JSONObject, lesson: Boolean): List<SourceTime> = (
    collectTimes(o, createdKeys + listOf("time", "timestamp"), SourceTimeKind.ACTIVITY_RECORDED) +
    collectTimes(o, listOf("publish_time", "published_at", "publishTime", "publishedAt"), SourceTimeKind.ACTIVITY_PUBLISHED) +
    if (lesson) collectTimes(o, listOf("start_time", "startTime", "lesson_start_time"), SourceTimeKind.LESSON_STARTED) else emptyList()
).distinctBy { it.kind to it.raw }

// Only a record holding its own explicit presentation reference owns generic creation fields.
// An activity's root date and unrelated siblings are never copied into PPT creation metadata.
fun presentationOwnedTimes(root: JSONObject): Map<String, List<SourceTime>> {
    val result = linkedMapOf<String, List<SourceTime>>()
    fun walk(value: Any?, depth: Int) {
        if (depth > 12) return
        when (value) {
            is JSONObject -> {
                val ids = listOf("presentation_id", "presentationId").mapNotNull { value.nonempty(it)?.takeIf { id -> id.matches(Regex("[0-9]{1,24}")) } }.distinct()
                val times = collectTimes(value, listOf("presentation_create_time", "presentation_created_at", "presentationCreatedAt", "presentationCreateTime"), SourceTimeKind.PRESENTATION_CREATED) +
                    if (depth > 0) collectTimes(value, createdKeys, SourceTimeKind.PRESENTATION_CREATED) + collectTimes(value, listOf("publish_time", "published_at", "publishTime", "publishedAt"), SourceTimeKind.ACTIVITY_PUBLISHED) else emptyList()
                ids.forEach { id -> result[id] = (result[id].orEmpty() + times).distinctBy { it.kind to it.raw } }
                value.keys().forEach { walk(value.opt(it), depth + 1) }
            }
            is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it), depth + 1) }
            is String -> if (value.length <= 100000 && value.trimStart().startsWith("{")) runCatching { walk(JSONObject(value), depth + 1) }
        }
    }
    walk(root, 0)
    return result
}
fun lessonPresentation(id: String, title: String, lesson: Presentation, ownTimes: List<SourceTime> = emptyList()) = Presentation(id, title, lesson.lessonId, recordedAt = lesson.recordedAt, teacher = lesson.teacher, times = (lesson.times.filter { it.kind != SourceTimeKind.PRESENTATION_CREATED } + ownTimes).distinctBy { it.kind to it.raw })
fun mergePresentations(items: List<Presentation>): List<Presentation> {
    val merged = linkedMapOf<String, Presentation>()
    items.forEach { p -> val key = "${p.lessonId}:${p.id}"; val old = merged[key]; merged[key] = if (old == null) p else old.copy(teacher = old.teacher.ifBlank { p.teacher }, recordedAt = old.recordedAt.ifBlank { p.recordedAt }, times = (old.times + p.times).distinctBy { Triple(it.kind, it.raw, it.sourceField) }) }
    return merged.values.toList()
}
private fun presentationEpoch(p: Presentation): Long? = SourceTimeKind.entries.firstNotNullOfOrNull { kind -> p.times.firstNotNullOfOrNull { if (it.kind == kind) it.epochMillis else null } } ?: parseSourceEpoch(p.recordedAt)
private fun compareEpoch(a: Long?, b: Long?, newest: Boolean): Int = when { a == null && b == null -> 0; a == null -> 1; b == null -> -1; newest -> b.compareTo(a); else -> a.compareTo(b) }
private fun compareTeacher(a: String, b: String): Int = when { a.isBlank() && b.isBlank() -> 0; a.isBlank() -> 1; b.isBlank() -> -1; else -> a.compareTo(b, ignoreCase = true) }
fun orderedPresentations(items: List<Presentation>, order: CourseOrder): List<Presentation> = items.sortedWith { a, b -> when (order) {
    CourseOrder.NAME -> a.title.compareTo(b.title, ignoreCase = true)
    CourseOrder.TEACHER -> compareTeacher(a.teacher, b.teacher).takeIf { it != 0 } ?: compareEpoch(presentationEpoch(a), presentationEpoch(b), true)
    else -> compareEpoch(presentationEpoch(a), presentationEpoch(b), order == CourseOrder.NEWEST)
} }
fun groupedCourses(items: List<Course>, order: CourseOrder): List<Pair<String, List<Course>>> {
    val ordered = if (order == CourseOrder.NAME) items.sortedBy { it.title.lowercase(Locale.ROOT) } else items
    if (order != CourseOrder.TEACHER) return listOf("" to ordered)
    return ordered.groupBy { it.teacher.trim() }.entries.sortedWith { a, b -> compareTeacher(a.key, b.key) }.map { (teacher, courses) -> teacher.ifBlank { "老师未提供" } to courses }
}
fun presentationMetadata(p: Presentation): String = buildList {
    add(if (p.isLesson) "课堂记录" else "课件")
    add("老师：${p.teacher.ifBlank { "未提供" }}")
    if (p.times.none { it.kind == SourceTimeKind.PRESENTATION_CREATED }) add("创建时间：未提供")
    p.times.distinctBy { it.kind to it.raw }.forEach { t -> add("${t.kind.label}：" + (t.epochMillis?.let { sourceDisplay.format(Instant.ofEpochMilli(it).atZone(sourceZone)) } ?: "${t.raw}（无法解析）")) }
    if (p.recordedAt.isNotBlank() && p.times.isEmpty()) add("记录时间：" + (parseSourceEpoch(p.recordedAt)?.let { sourceDisplay.format(Instant.ofEpochMilli(it).atZone(sourceZone)) } ?: "${p.recordedAt}（无法解析）"))
}.joinToString("\n")
