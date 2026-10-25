package cn.studybridge.mobile

import androidx.compose.runtime.staticCompositionLocalOf
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser

// Cache syntax only. Markwon spans contain mutable native layout / callbacks
// and must be freshly rendered for each view, font, theme and actual width.
internal class ReaderMarkdownParser(private val characterBudget: Int = 96_000) {
    private val entries = LinkedHashMap<String, Node>(16, .75f, true)
    private var characters = 0
    fun parse(source: String): Node {
        synchronized(entries) { entries[source]?.let { return it } }
        // Parse outside the cache lock: speculative neighbor work cannot hold
        // the UI waiting on a different block. Each parse has its own parser.
        val node = Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(source)
        synchronized(entries) {
            entries[source]?.let { return it }
            if (source.length <= characterBudget) {
                entries[source] = node; characters += source.length
                val iterator = entries.iterator()
                while (entries.size > 12 || characters > characterBudget) {
                    val entry = iterator.next(); characters -= entry.key.length; iterator.remove()
                }
            }
        }
        return node
    }
    fun warm(markdown: String) {
        var start = 0
        sourceImagePattern.findAll(markdown).forEach { image ->
            if (image.range.first > start) parse(markdownForStudyMarks(markdown.substring(start, image.range.first)))
            start = image.range.last + 1
        }
        if (start < markdown.length || markdown.isEmpty()) parse(markdownForStudyMarks(markdown.substring(start)))
    }
}

internal val LocalReaderMarkdownParser = staticCompositionLocalOf<ReaderMarkdownParser?> { null }
