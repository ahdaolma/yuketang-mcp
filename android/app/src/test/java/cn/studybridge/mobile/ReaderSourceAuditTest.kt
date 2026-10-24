package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ReaderSourceAuditTest {
    @Test fun windowsNewlinesKeepOriginalOffsetsAndTwoSourcePages() {
        for (newline in listOf("\r\n", "\r", "\n")) {
            val text = listOf("# 标题", "", "## 第 1 页", "第一页。", "", "## 第 2 页", "第二页。").joinToString(newline)
            val headings = readingHeadings(text)
            assertEquals(text.indexOf("## 第 2 页"), headings.last().first)
            val parts = noteReadingSegments(text)
            assertEquals("Two source pages must remain two with newline=${newline.toByteArray().toList()}", 2, parts.size)
            assertTrue(parts.last().display(text).startsWith("## 第 2 页"))
            assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
            assertEquals(listOf("第 1 / 2 页", "第 2 / 2 页"), readerPageLabels(text, parts))
        }
    }

    @Test fun searchDoesNotMakeOccurrencesBeyondOneHundredUnreachable() {
        val text = (1..130).joinToString("\n\n") { "第${it}条。定位词。" }
        val hits = searchNoteContent(text, "定位词")
        assertEquals(130, hits.size)
        assertEquals(text.lastIndexOf("定位词"), hits.last().offset)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.offset < b.offset })
    }

    @Test fun longTablesKeepTheirHeaderWhenSplitForReading() {
        val header = "|知识点|说明|\n|---|---|\n"
        val text = "## 表格\n\n" + header + (1..120).joinToString("\n") { "|条目$it|这是需要完整阅读的解释。|" } + "\n\n表格后的正文。"
        val parts = noteReadingSegments(text, 512)
        assertTrue(parts.size > 2)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        parts.filter { text.substring(it.start, it.end).trimStart().startsWith("|条目") }.forEach { part ->
            assertTrue("Continuation rows require the original table header for Markdown rendering", part.display(text).startsWith(header))
        }
    }

    @Test fun aSourceImageReferenceCannotBeCutInHalfByAnArtificialSegmentLimit() {
        val id = "a".repeat(64)
        val text = "前".repeat(490) + sourceImageMarkdown(id, "原图") + "后".repeat(700)
        val parts = noteReadingSegments(text, 512)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(1, parts.sumOf { sourceImagePattern.findAll(it.display(text)).count() })
    }
}
