package cn.studybridge.mobile

// All offsets refer to the original UTF-16 source. In particular, CRLF occupies
// two characters, while a lone CR is also a complete Markdown line ending.
internal data class NoteSourceLine(val start: Int, val contentEnd: Int, val end: Int)

internal fun noteSourceLines(source: String): List<NoteSourceLine> {
    val lines = mutableListOf<NoteSourceLine>()
    var start = 0
    var cursor = 0
    while (cursor < source.length) {
        if (source[cursor] != '\r' && source[cursor] != '\n') {
            cursor++
            continue
        }
        val contentEnd = cursor++
        if (source[contentEnd] == '\r' && cursor < source.length && source[cursor] == '\n') cursor++
        lines.add(NoteSourceLine(start, contentEnd, cursor))
        start = cursor
    }
    if (start < source.length) lines.add(NoteSourceLine(start, source.length, source.length))
    return lines
}
