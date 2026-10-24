package cn.studybridge.mobile

// Search destinations are visible notebook text, not hidden link addresses,
// reference definitions or our private image IDs. Code retains literal text.
internal fun noteHiddenSearchCharacters(source: String): BooleanArray {
    val hidden = BooleanArray(source.length)
    val code = BooleanArray(source.length)
    fun mark(mask: BooleanArray, start: Int, end: Int) {
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(source.length)) mask[i] = true
    }
    val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    var fence = ""
    for (line in noteSourceLines(source)) {
        val text = source.substring(line.start, line.contentEnd)
        val marker = fencePattern.matchEntire(text)
        if (marker != null) {
            val token = marker.groupValues[1]
            if (fence.isEmpty()) {
                fence = token
                mark(hidden, line.start, line.end)
            } else if (token.first() == fence.first() && token.length >= fence.length && marker.groupValues[2].isBlank()) {
                fence = ""
                mark(hidden, line.start, line.end)
            } else mark(code, line.start, line.end)
        } else if (fence.isNotEmpty()) mark(code, line.start, line.end)
        else {
            Regex("(`+)(.*?)\\1").findAll(text).forEach { mark(code, line.start + it.range.first, line.start + it.range.last + 1) }
            if (Regex("^ {0,3}\\[[^]]+]:\\s*\\S.*$").matches(text)) mark(hidden, line.start, line.end)
        }
    }
    val brackets = ArrayDeque<Int>()
    var i = 0
    while (i < source.length) {
        if (code[i] || hidden[i]) { i++; continue }
        if (source[i] == '\\') { i += 2; continue }
        if (source[i] == '[') brackets.addLast(i)
        if (source[i] == ']' && brackets.isNotEmpty()) {
            brackets.removeLast()
            val open = i + 1
            if (source.getOrNull(open) == '(') {
                var cursor = open + 1
                var depth = 1
                var quote: Char? = null
                var angle = false
                while (cursor < source.length && depth > 0) {
                    val c = source[cursor]
                    if (c == '\\') { cursor += 2; continue }
                    if (quote != null) { if (c == quote) quote = null }
                    else if (angle) { if (c == '>') angle = false }
                    else when {
                        c == '<' && cursor == open + 1 -> angle = true
                        (c == '"' || c == '\'') && source.getOrNull(cursor - 1)?.isWhitespace() == true -> quote = c
                        c == '(' -> depth++
                        c == ')' -> depth--
                        (c == '\r' || c == '\n') && source.getOrNull(cursor + 1) == c -> break
                    }
                    cursor++
                }
                if (depth == 0) { mark(hidden, open, cursor); i = cursor; continue }
            } else if (source.getOrNull(open) == '[') {
                val end = source.indexOf(']', open + 1)
                if (end >= 0 && source.substring(open, end).none { it == '\r' || it == '\n' }) {
                    mark(hidden, open, end + 1); i = end + 1; continue
                }
            }
        }
        i++
    }
    sourceImagePattern.findAll(source).filter { !code[it.range.first] }.forEach { mark(hidden, it.range.first, it.range.last + 1) }
    return hidden
}

internal fun renderedNoteSearchOffset(rendered: String, renderedPrefix: String, query: String): Int {
    if (query.isEmpty()) return -1
    var count = 0
    var offset = 0
    while (offset < renderedPrefix.length) {
        val found = renderedPrefix.indexOf(query, offset, true)
        if (found < 0) break
        count++; offset = found + query.length
    }
    var found = -query.length
    repeat(count + 1) {
        found = rendered.indexOf(query, (found + query.length).coerceAtLeast(0), true)
        if (found < 0) return -1
    }
    return found
}
