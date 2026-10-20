package cn.studybridge.mobile

data class NoteReadingSegment(val start: Int, val end: Int, val prefix: String = "", val suffix: String = "") {
    fun display(markdown: String) = prefix + markdown.substring(start, end) + suffix
}

// Store offsets, not copies of an entire long notebook. Synthetic fences affect
// reading only; the original bytes remain unchanged for saving/editing/export.
fun noteReadingSegments(markdown: String, limit: Int = 12000): List<NoteReadingSegment> {
    require(limit >= 256)
    if (markdown.isEmpty()) return listOf(NoteReadingSegment(0, 0))
    val result = mutableListOf<NoteReadingSegment>()
    val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    var opened = ""; var opener = ""; var start = 0
    while (start < markdown.length) {
        var end = minOf(markdown.length, start + limit)
        if (end < markdown.length) {
            val paragraph = markdown.lastIndexOf("\n\n", end - 2)
            val line = markdown.lastIndexOf('\n', end - 1)
            end = when {
                paragraph >= start + limit / 2 -> paragraph + 2
                line >= start + limit / 2 -> line + 1
                else -> end
            }
            if (end > start && markdown[end - 1].isHighSurrogate() && markdown[end].isLowSurrogate()) end--
        }
        val prefix = if (opened.isBlank()) "" else "$opener\n"
        for (line in markdown.substring(start, end).lineSequence()) {
            val match = fence.matchEntire(line) ?: continue
            val marker = match.groupValues[1]
            if (opened.isBlank()) { opened = marker; opener = line.trimStart() }
            else if (marker.first() == opened.first() && marker.length >= opened.length && match.groupValues[2].isBlank()) { opened = ""; opener = "" }
        }
        val suffix = if (opened.isBlank()) "" else "\n$opened\n"
        result.add(NoteReadingSegment(start, end, prefix, suffix))
        start = end
    }
    return result
}
