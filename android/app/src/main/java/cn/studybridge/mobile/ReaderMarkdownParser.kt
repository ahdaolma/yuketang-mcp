package cn.studybridge.mobile

import androidx.compose.runtime.staticCompositionLocalOf
import android.text.Spanned
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser

// Syntax is reusable, but a staged Spanned is transferred to ONE view only.
// No attached TextView / mutable table layout is ever returned to this cache.
internal class ReaderMarkdownParser(private val characterBudget: Int = 96_000) {
    private class Entry(val node: Node, var staged: Spanned? = null)
    private class Rendering(val key: Any, val factory: () -> (String, Node) -> Spanned) {
        var render: ((String, Node) -> Spanned)? = null
    }
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var characters = 0
    private var rendering: Rendering? = null
    fun configureRendering(key: Any, factory: () -> (String, Node) -> Spanned) {
        synchronized(entries) {
            if (rendering?.key == key) return
            rendering = Rendering(key, factory)
            entries.values.forEach { it.staged = null }
        }
    }
    fun takePrepared(source: String, key: Any): Spanned? = synchronized(entries) {
        if (rendering?.key != key) null else entries[source]?.let { entry -> entry.staged.also { entry.staged = null } }
    }
    fun parse(source: String): Node {
        synchronized(entries) { entries[source]?.let { return it.node } }
        // Parse outside the cache lock: speculative neighbor work cannot hold
        // the UI waiting on a different block. Each parse has its own parser.
        val node = Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(source)
        synchronized(entries) {
            entries[source]?.let { return it.node }
            if (source.length <= characterBudget) {
                entries[source] = Entry(node); characters += source.length
                val iterator = entries.iterator()
                while (entries.size > 12 || characters > characterBudget) {
                    val entry = iterator.next(); characters -= entry.key.length; iterator.remove()
                }
            }
        }
        return node
    }
    private fun warmBlock(raw: String) {
        val source = markdownForStudyMarks(raw)
        val node = parse(source)
        val setup = synchronized(entries) {
            if (entries[source]?.staged != null) return
            rendering
        } ?: return
        // Only worker rendering shares this instance. UI acquisition never
        // waits on its visitor lock, and stale themes cannot publish results.
        val staged = synchronized(setup) {
            val render = setup.render ?: setup.factory().also { setup.render = it }
            render(source, node)
        }
        synchronized(entries) {
            if (rendering === setup) entries[source]?.takeIf { it.node === node && it.staged == null }?.staged = staged
        }
    }
    fun warm(markdown: String) {
        var start = 0
        sourceImagePattern.findAll(markdown).forEach { image ->
            if (image.range.first > start) warmBlock(markdown.substring(start, image.range.first))
            start = image.range.last + 1
        }
        if (start < markdown.length || markdown.isEmpty()) warmBlock(markdown.substring(start))
    }
}

internal val LocalReaderMarkdownParser = staticCompositionLocalOf<ReaderMarkdownParser?> { null }
