package cn.studybridge.mobile

data class NoteReadingSegment(val start: Int, val end: Int, val prefix: String = "", val suffix: String = "") {
    fun display(markdown: String) = prefix + markdown.substring(start, end) + suffix
    fun sourceForDisplay(markdown: String, removedPrefix: Int = 0): NoteReadingSource {
        val removedSource = (removedPrefix - prefix.length).coerceIn(0, end - start)
        return NoteReadingSource(markdown.substring(start + removedSource, end), start + removedSource, (prefix.length - removedPrefix).coerceAtLeast(0))
    }
}

// Original bytes corresponding to the real part of a displayed segment. The
// prefix can be a repeated table header or a reopened fence, never stored text.
data class NoteReadingSource(val source: String, val sourceStart: Int, val prefixLength: Int = 0) {
    fun block(start: Int, end: Int): NoteReadingSource? {
        val first = maxOf(start, prefixLength)
        val last = minOf(end, prefixLength + source.length)
        if (last <= first) return null
        return NoteReadingSource(source.substring(first - prefixLength, last - prefixLength), sourceStart + first - prefixLength, (prefixLength - start).coerceAtLeast(0))
    }
}

// Suppress only the exact redundant document title in the rendered first segment.
// Original Markdown, code fences, editing and exports keep their original bytes.
fun noteTextForDisplay(markdown: String, title: String): String {
    val match = Regex("\\A[\\r\\n]*# +([^\\r\\n]+)\\r?\\n(?:\\r?\\n)*").find(markdown) ?: return markdown
    val heading = match.groupValues[1].trim()
    val withoutRange = title.replace(Regex(" · 第 \\d+[–-]\\d+ 页$"), "")
    return if (heading == title.trim() || heading == withoutRange.trim()) markdown.substring(match.range.last + 1) else markdown
}

private data class ReadingAtomicRange(val start: Int, val end: Int)
private data class ReadingTable(val bodyStart: Int, val end: Int, val header: String)
private data class ReadingFence(val offset: Int, val marker: String, val opener: String)

// First index whose value is greater than the requested offset. Boundary lookups
// stay logarithmic even for a very long document with no paragraph separators.
private fun List<Int>.afterOffset(offset: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] <= offset) low = middle + 1 else high = middle
    }
    return low
}

private fun tableDelimiter(line: String): Boolean {
    val cells = line.trim().removePrefix("|").removeSuffix("|").split('|')
    return cells.isNotEmpty() && cells.all { Regex("^:?-{3,}:?$").matches(it.trim()) }
}

// Store offsets, not copies of an entire long notebook. Synthetic fences and
// repeated table headers affect display only; source ranges still join exactly.
fun noteReadingSegments(markdown: String, limit: Int = 12000): List<NoteReadingSegment> {
    require(limit >= 256)
    if (markdown.isEmpty()) return listOf(NoteReadingSegment(0, 0))
    val result = mutableListOf<NoteReadingSegment>()
    val lines = noteSourceLines(markdown)
    val lineEnds = lines.map { it.end }
    val paragraphs = mutableListOf<Int>()
    val atomic = mutableListOf<ReadingAtomicRange>()
    val tables = mutableListOf<ReadingTable>()
    val fences = mutableListOf(ReadingFence(0, "", ""))
    val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    val tableInterrupt = Regex("^ {0,3}(?:#{1,6}(?:\\s|$)|>|[-+*]\\s|1[.)]\\s|(?:[-*_]\\s*){3,}$)")
    var opened = ""
    var opener = ""
    var tableHeader = ""
    var tableBodyStart = -1
    var tableEnd = 0
    for ((index, range) in lines.withIndex()) {
        val line = markdown.substring(range.start, range.contentEnd)
        if (line.isBlank()) paragraphs.add(range.end)
        val marker = fence.matchEntire(line)
        if (marker != null) {
            val token = marker.groupValues[1]
            if (opened.isEmpty()) {
                opened = token
                opener = line.trimStart()
            } else if (token.first() == opened.first() && token.length >= opened.length && marker.groupValues[2].isBlank()) {
                opened = ""
                opener = ""
            }
            // A fence marker itself must never become a partial Markdown line.
            atomic.add(ReadingAtomicRange(range.start, range.end))
            fences.add(ReadingFence(range.end, opened, opener))
        }
        if (tableBodyStart >= 0) {
            // The source delimiter belongs to the atomic header range, not to
            // the body (it may itself have no outer pipe characters).
            if (range.start < tableBodyStart) continue
            // GFM permits omitted trailing cells, including a body row with
            // only its first cell and no pipe. Blank lines and block starters
            // end a table; ordinary nonblank text remains a complete row.
            val interruptsTable = tableInterrupt.containsMatchIn(line)
            if (opened.isEmpty() && marker == null && line.isNotBlank() && ('|' in line || !interruptsTable)) {
                atomic.add(ReadingAtomicRange(range.start, range.end))
                tableEnd = range.end
                continue
            }
            tables.add(ReadingTable(tableBodyStart, tableEnd, tableHeader))
            tableBodyStart = -1
        }
        if (opened.isEmpty() && marker == null && '|' in line && index + 1 < lines.size) {
            val delimiter = lines[index + 1]
            if (tableDelimiter(markdown.substring(delimiter.start, delimiter.contentEnd))) {
                // Keep the source header and delimiter together. The delimiter
                // is handled on the next iteration as part of this same range.
                tableHeader = markdown.substring(range.start, delimiter.end)
                tableBodyStart = delimiter.end
                tableEnd = delimiter.end
                atomic.add(ReadingAtomicRange(range.start, delimiter.end))
            }
        }
    }
    if (tableBodyStart >= 0) tables.add(ReadingTable(tableBodyStart, tableEnd, tableHeader))
    sourceImagePattern.findAll(markdown).forEach { atomic.add(ReadingAtomicRange(it.range.first, it.range.last + 1)) }
    // Image references may sit inside table rows. Merge overlapping ranges,
    // while adjacent rows remain independent so long tables can still split.
    val atoms = mutableListOf<ReadingAtomicRange>()
    for (range in atomic.sortedBy { it.start }) {
        val previous = atoms.lastOrNull()
        if (previous != null && range.start < previous.end) atoms[atoms.lastIndex] = previous.copy(end = maxOf(previous.end, range.end))
        else atoms.add(range)
    }
    val atomStarts = atoms.map { it.start }
    val fenceOffsets = fences.map { it.offset }
    val allHeadings = readingHeadings(markdown)
    val pageHeadings = allHeadings.filter { markdown.startsWith("## ", it.first) && Regex("第\\s*\\d+\\s*页").containsMatchIn(it.second) }
    // Keep each page's source image with its explanation, including ### subtopics.
    val headings = (if (pageHeadings.isNotEmpty()) pageHeadings.drop(1) else allHeadings.filter { markdown.startsWith("# ", it.first) || markdown.startsWith("## ", it.first) }).map { it.first }
    var headingIndex = 0
    var tableIndex = 0
    var start = 0
    while (start < markdown.length) {
        while (headingIndex < headings.size && headings[headingIndex] <= start) headingIndex++
        val nextHeading = headings.getOrNull(headingIndex) ?: markdown.length
        var end = minOf(markdown.length, start + limit, nextHeading)
        if (end < markdown.length && end != nextHeading) {
            val paragraph = paragraphs.getOrNull(paragraphs.afterOffset(end) - 1) ?: -1
            val line = lineEnds.getOrNull(lineEnds.afterOffset(end) - 1) ?: -1
            end = when {
                paragraph >= start + limit / 2 -> paragraph
                line >= start + limit / 2 -> line
                else -> end
            }
        }
        val atom = atoms.getOrNull(atomStarts.afterOffset(end - 1) - 1)
        if (atom != null && end > atom.start && end < atom.end) {
            // Normally stop before an atomic row/reference. Only a single
            // oversized atom at the segment start may exceed the size limit.
            end = if (atom.start > start) atom.start else atom.end
        }
        if (end < markdown.length && end > start &&
            ((markdown[end - 1].isHighSurrogate() && markdown[end].isLowSurrogate()) ||
                (markdown[end - 1] == '\r' && markdown[end] == '\n'))) end--
        val startFence = fences[fenceOffsets.afterOffset(start) - 1]
        val endFence = fences[fenceOffsets.afterOffset(end) - 1]
        while (tableIndex < tables.size && tables[tableIndex].end <= start) tableIndex++
        val table = tables.getOrNull(tableIndex)?.takeIf { start >= it.bodyStart && start < it.end }
        val prefix = if (startFence.marker.isNotEmpty()) "${startFence.opener}\n" else table?.header.orEmpty()
        val suffix = if (endFence.marker.isEmpty()) "" else "\n${endFence.marker}\n"
        result.add(NoteReadingSegment(start, end, prefix, suffix))
        start = end
    }
    return result
}
