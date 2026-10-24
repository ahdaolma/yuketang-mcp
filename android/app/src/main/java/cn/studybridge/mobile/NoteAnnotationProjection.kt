package cn.studybridge.mobile

/** A display range only: the saved anchor, identifier and remark stay intact. */
data class ProjectedNoteAnnotation(val annotation: NoteAnnotation, val start: Int, val end: Int)

private data class AnnotationTextRun(val from: Int, val to: Int, val length: Int)

private fun commonAnnotationPrefix(first: String, second: String): Int {
    var length = 0
    while (length < minOf(first.length, second.length) && first[length] == second[length]) length++
    return length
}

private fun commonAnnotationSuffix(first: String, second: String): Int {
    var length = 0
    while (length < minOf(first.length, second.length) && first[first.lastIndex - length] == second[second.lastIndex - length]) length++
    return length
}

// Map the render of an exact raw subrange into its parent's render. A shared
// SOURCE endpoint fixes occurrence/rank, even for thousands of repeated words.
// When both endpoints differ, bridge through the parent's prefix ending at the
// subrange end. Never search for a quote or choose the first duplicate match.
private fun annotationIntersectionRun(parent: String, parentText: String, first: Int, last: Int, render: (String) -> String): AnnotationTextRun {
    val shared = render(parent.substring(first, last))
    if (first == 0) return AnnotationTextRun(0, 0, commonAnnotationPrefix(shared, parentText))
    if (last == parent.length) {
        val length = commonAnnotationSuffix(shared, parentText)
        return AnnotationTextRun(shared.length - length, parentText.length - length, length)
    }
    val prefix = render(parent.substring(0, last))
    val suffixLength = commonAnnotationSuffix(shared, prefix)
    val prefixLength = commonAnnotationPrefix(prefix, parentText)
    val prefixStart = prefix.length - suffixLength
    val length = (minOf(prefix.length, prefixLength) - prefixStart).coerceAtLeast(0)
    return AnnotationTextRun(shared.length - suffixLength, prefixStart, length)
}

/**
 * Project attached pre-segmentation anchors through their verified raw overlap.
 * Parsing is supplied by the caller so UI and PDF use their own actual Markwon
 * configuration. Only identical endpoint-anchored render runs are transferable;
 * changed Markdown context and stale source fail closed, preserving saved data.
 */
fun projectNoteAnnotations(
    parsed: String, source: String, sourceStart: Int?, annotations: List<NoteAnnotation>,
    content: String? = null, annotationStart: Int = 0, renderPrefix: String = "", render: ((String) -> String)? = null
): List<ProjectedNoteAnnotation> {
    if (sourceStart == null || annotations.isEmpty()) return emptyList()
    val hash = imageDigest(source.toByteArray())
    val cache = mutableMapOf<String, String>()
    val attachmentCache = mutableMapOf<Triple<Int, Int, String>, Boolean>()
    fun rendered(raw: String): String = cache.getOrPut(raw) { requireNotNull(render)(raw) }
    val result = mutableListOf<ProjectedNoteAnnotation>()
    for (mark in annotations) {
        if (mark.start < 0 || mark.end <= mark.start || mark.quote.length != mark.end - mark.start) continue
        if (mark.renderPrefix == renderPrefix && mark.sourceStart == sourceStart && mark.sourceLength == source.length && mark.sourceHash == hash) {
            if (mark.start >= annotationStart && mark.end <= parsed.length && parsed.substring(mark.start, mark.end) == mark.quote) {
                result.add(ProjectedNoteAnnotation(mark, mark.start, mark.end))
                continue
            }
        }
        if (content == null || render == null || sourceStart < 0 ||
            sourceStart.toLong() + source.length > content.length || content.substring(sourceStart, sourceStart + source.length) != source) continue
        val overlapStart = maxOf(sourceStart, mark.sourceStart)
        val overlapEnd = minOf(sourceStart + source.length, mark.sourceStart + mark.sourceLength)
        if (overlapStart >= overlapEnd) continue
        if (!attachmentCache.getOrPut(Triple(mark.sourceStart, mark.sourceLength, mark.sourceHash)) { mark.isAttached(content) }) continue
        val oldSource = content.substring(mark.sourceStart, mark.sourceStart + mark.sourceLength)
        val oldParent = mark.renderPrefix + oldSource
        val oldText = rendered(oldParent)
        if (mark.end > oldText.length || oldText.substring(mark.start, mark.end) != mark.quote) continue
        val currentParent = renderPrefix + source
        val currentText = rendered(currentParent)
        val oldRun = annotationIntersectionRun(oldParent, oldText, mark.renderPrefix.length + overlapStart - mark.sourceStart, mark.renderPrefix.length + overlapEnd - mark.sourceStart, ::rendered)
        val newRun = annotationIntersectionRun(currentParent, currentText, renderPrefix.length + overlapStart - sourceStart, renderPrefix.length + overlapEnd - sourceStart, ::rendered)
        val sharedFirst = maxOf(oldRun.from, newRun.from, oldRun.from + mark.start - oldRun.to)
        val sharedLast = minOf(oldRun.from + oldRun.length, newRun.from + newRun.length, oldRun.from + mark.end - oldRun.to)
        if (sharedFirst >= sharedLast) continue
        var first = newRun.to + sharedFirst - newRun.from
        var last = newRun.to + sharedLast - newRun.from
        // Current displays may include a repeated table header/reopened fence.
        // The prefix is not stored source and must never receive a legacy mark.
        val shownRun = AnnotationTextRun(0, 0, commonAnnotationPrefix(currentText, parsed))
        first = maxOf(first, shownRun.from)
        last = minOf(last, shownRun.from + shownRun.length)
        if (first >= last) continue
        val shownFirst = shownRun.to + first - shownRun.from
        val shownLast = shownRun.to + last - shownRun.from
        if (shownFirst < annotationStart || parsed.substring(shownFirst, shownLast) != currentText.substring(first, last)) continue
        // Object-replacement characters are table/plugin layout tokens rather
        // than identifiable source text. Do not guess between duplicate rows.
        if ('\uFFFC' in parsed.substring(shownFirst, shownLast)) continue
        result.add(ProjectedNoteAnnotation(mark, shownFirst, shownLast))
    }
    return result
}
