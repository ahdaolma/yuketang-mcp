package cn.studybridge.mobile

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ReaderMarkdownParserTest {
    private fun nodes(root: Node): List<Node> {
        val result = mutableListOf(root)
        var child = root.firstChild
        while (child != null) { result += nodes(child); child = child.next }
        return result
    }
    @Test fun cachedSyntaxPreservesTablesAndChangedLinkDestinations() {
        val cache = ReaderMarkdownParser()
        val first = "| 标题 | 解释 |\n| --- | --- |\n| 重点 | [资料](https://example.com/first) |"
        val a = cache.parse(first)
        assertTrue(nodes(a).any { it is TableBlock })
        assertEquals("https://example.com/first", nodes(a).filterIsInstance<Link>().single().destination)
        val b = cache.parse(first.replace("/first", "/changed"))
        assertEquals("https://example.com/changed", nodes(b).filterIsInstance<Link>().single().destination)
        assertSame(a, cache.parse(first))
    }
    @Test fun longDocumentTraversalDoesNotRetainEveryParsedPage() {
        val cache = ReaderMarkdownParser(200)
        val source = "first " + "a".repeat(90)
        val first = cache.parse(source)
        repeat(100) { cache.parse("page$it " + "b".repeat(90)) }
        assertNotSame("Earlier pages must be evicted when traversal exceeds the character budget", first, cache.parse(source))
    }
    @Test fun neighborWorkersAndVisibleLookupShareConsistentSyntax() {
        val cache = ReaderMarkdownParser()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val source = "**重点** [资料](https://example.com/source)".repeat(150)
            val results = executor.invokeAll((1..20).map { Callable { cache.parse(source) } }).map { it.get() }
            assertTrue(results.all { it === results.first() })
            assertEquals(150, nodes(results.first()).filterIsInstance<Link>().size)
        } finally { executor.shutdownNow() }
    }
}
