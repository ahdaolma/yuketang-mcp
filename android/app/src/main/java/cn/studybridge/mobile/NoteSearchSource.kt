package cn.studybridge.mobile

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.parser.Parser

// Search destinations are visible notebook text, not hidden link addresses,
// reference definitions or our private image IDs. Code retains literal text.
private data class NoteSearchSyntax(val hidden: BooleanArray, val definitions: String)
private fun referenceLabel(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

private fun parsedReferenceDefinitions(source: String): Map<String, String> {
    val definitions = linkedMapOf<String, String>()
    if (!source.contains("]:")) return definitions
    Parser.builder().build().parse(source).accept(object : AbstractVisitor() {
        override fun visit(definition: LinkReferenceDefinition) {
            val label = definition.label.replace("\\", "\\\\").replace("]", "\\]")
            val destination = definition.destination.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>")
            val title = definition.title?.let { " \"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }.orEmpty()
            definitions.putIfAbsent(referenceLabel(definition.label), "[$label]: <$destination>$title")
        }
    })
    return definitions
}

private fun noteSearchSyntax(source: String): NoteSearchSyntax {
    val hidden = BooleanArray(source.length)
    val code = BooleanArray(source.length)
    fun mark(mask: BooleanArray, start: Int, end: Int) {
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(source.length)) mask[i] = true
    }
    val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    var fence = ""
    var indented = false
    var previousBlank = true
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
                previousBlank = true
            } else mark(code, line.start, line.end)
        } else if (fence.isNotEmpty()) mark(code, line.start, line.end)
        else if ((indented || previousBlank) && (text.startsWith("    ") || text.startsWith('\t'))) {
            mark(code, line.start, line.end); indented = true
        } else if (text.isNotBlank()) indented = false
        if (marker == null) previousBlank = text.isBlank() || Regex("^ {0,3}#{1,6}(?:\\s.*)?$").matches(text) || Regex("^ {0,3}(?:[-*_]\\s*){3,}$").matches(text) || Regex("^ {0,3}(?:=+|-+)\\s*$").matches(text)
    }
    Regex("(`+)(.*?)\\1", RegexOption.DOT_MATCHES_ALL).findAll(source).forEach {
        if (!code[it.range.first] && !hidden[it.range.first] && (it.range.first == 0 || source[it.range.first - 1] != '\\')) mark(code, it.range.first, it.range.last + 1)
    }
    val definitions = parsedReferenceDefinitions(source)
    val definitionPattern = Regex("^ {0,3}\\[([^]]+)]:.*$")
    val lines = noteSourceLines(source)
    val parser = Parser.builder().build()
    for ((lineIndex, line) in lines.withIndex()) {
        if (code[line.start] || hidden[line.start]) continue
        val text = source.substring(line.start, line.contentEnd)
        val definition = definitionPattern.matchEntire(text) ?: continue
        if (referenceLabel(definition.groupValues[1]) !in definitions) continue
        var acceptedEnd = -1
        for (endIndex in lineIndex until lines.size) {
            val last = lines[endIndex]
            if (endIndex > lineIndex && source.substring(last.start, last.contentEnd).isBlank()) break
            val node = parser.parse(source.substring(line.start, last.contentEnd)).firstChild
            if (node is LinkReferenceDefinition && node.next == null) acceptedEnd = last.end
            // Once a complete definition has been read, an ordinary following
            // paragraph belongs to visible content, not the definition.
            else if (acceptedEnd >= 0) break
        }
        if (acceptedEnd >= 0) mark(hidden, line.start, acceptedEnd)
    }
    val brackets = ArrayDeque<Int>()
    var i = 0
    while (i < source.length) {
        if (code[i] || hidden[i]) { i++; continue }
        if (source[i] == '\\') { i += 2; continue }
        if (source[i] == '[') brackets.addLast(i)
        if (source[i] == ']' && brackets.isNotEmpty()) {
            val labelStart = brackets.removeLast()
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
                    val id = source.substring(open + 1, end).ifBlank { source.substring(labelStart + 1, i) }
                    if (referenceLabel(id) in definitions) { mark(hidden, open, end + 1); i = end + 1; continue }
                }
            }
        }
        i++
    }
    sourceImagePattern.findAll(source).filter { !code[it.range.first] }.forEach { mark(hidden, it.range.first, it.range.last + 1) }
    return NoteSearchSyntax(hidden, definitions.values.joinToString("\n"))
}

internal fun noteHiddenSearchCharacters(source: String) = noteSearchSyntax(source).hidden

internal fun noteSearchPrefixSource(source: String, end: Int): String {
    val definitions = noteSearchSyntax(source).definitions
    val prefix = source.take(end.coerceIn(0, source.length))
    // Put valid definition nodes before the prefix: appending would turn them
    // into visible code when the hit is inside an unfinished fenced block.
    return if (definitions.isBlank()) prefix else "$definitions\n\n$prefix"
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
