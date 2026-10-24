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

    @Test fun mixedNewlineOffsetsIgnoreBothKindsOfFencedHeadings() {
        val text = "# 标题\r\n~~~text\r## 伪标题一\n~~~\r\n## 第 1 页\r```kotlin\n### 伪标题二\r\n```\r### 专题\n## 第 2 页"
        assertEquals(
            listOf("# 标题", "## 第 1 页", "### 专题", "## 第 2 页").map { text.indexOf(it) },
            readingHeadings(text).map { it.first }
        )
        val parts = noteReadingSegments(text, 256)
        assertEquals(2, parts.size)
        assertTrue(parts.first().display(text).contains("### 专题"))
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun oversizedImageIsOneAtomicSegmentAndNeighboringTextStaysBounded() {
        val token = sourceImageMarkdown("b".repeat(64), "图".repeat(200)).trim()
        assertTrue(token.length > 256)
        val text = "前".repeat(600) + token + "后".repeat(600)
        val parts = noteReadingSegments(text, 256)
        val oversized = parts.filter { it.end - it.start > 256 }
        assertEquals(1, oversized.size)
        assertEquals(token, oversized.single().display(text))
        assertEquals(1, parts.sumOf { sourceImagePattern.findAll(it.display(text)).count() })
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun continuedCrLfTablesKeepAlignmentHeaderAndCompleteOversizedRows() {
        val header = "主题 | 说明\r\n:--- | ---:\r\n"
        val longRow = "长行 | " + "解释".repeat(200) + "\r\n"
        val text = "表格前\r\n\r\n" + header + "首行 | 简短\r\n" + longRow +
            (1..60).joinToString("") { "条目$it | 需要阅读的说明。\r\n" } + "\r\n表格后"
        val parts = noteReadingSegments(text, 256)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(parts.any { text.substring(it.start, it.end) == longRow })
        for (part in parts.filter { it.prefix.isNotEmpty() }) {
            assertEquals(header, part.prefix)
            assertTrue(text.substring(part.start, part.end).startsWith("条目") || text.substring(part.start, part.end).startsWith("长行"))
        }
        assertTrue(parts.last().display(text).endsWith("表格后"))
    }

    @Test fun tableLikeTextInsideCodeDoesNotAcquireTableHeaders() {
        val text = "~~~text\r\n|主题|解释|\r\n|---|---|\r\n" + "|条目|代码内容|\r\n".repeat(90) + "~~~\r\n结束"
        val parts = noteReadingSegments(text, 256)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(parts.drop(1).any { it.prefix == "~~~text\n" })
        assertTrue(parts.all { it.prefix.isEmpty() || it.prefix == "~~~text\n" })
        assertTrue(parts.all { it.end - it.start <= 256 })
    }

    @Test fun privateImagesWithinLongTableRowsAreKeptAtomic() {
        val image = sourceImageMarkdown("c".repeat(64), "原图").trim()
        val text = "|主题|说明|\n|---|---|\n" +
            (1..12).joinToString("\n") { "|条目$it|${"解释".repeat(80)}$image|" } + "\n"
        val parts = noteReadingSegments(text, 256)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(12, parts.sumOf { sourceImagePattern.findAll(it.display(text)).count() })
        assertTrue(parts.drop(1).all { it.prefix == "|主题|说明|\n|---|---|\n" })
    }

    @Test fun longLinesRemainBoundedAndNeverSplitSurrogatesOrCrLf() {
        val text = ("正文🧠".repeat(1000) + "\r\n").repeat(3)
        val parts = noteReadingSegments(text, 257)
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(parts.all { it.end - it.start <= 257 })
        assertTrue(parts.drop(1).none { text[it.start].isLowSurrogate() || (text[it.start] == '\n' && text[it.start - 1] == '\r') })
    }

    @Test fun uncappedSearchPreservesCaseInsensitiveSourceOffsetsAcrossSegments() {
        val text = (1..230).joinToString("\r\n") { if (it % 2 == 0) "Hit$it" else "hIT$it" }
        val parts = noteReadingSegments(text, 256)
        val hits = searchNoteContent(text, " hit ", parts)
        assertEquals(230, hits.size)
        for (hit in hits) {
            assertTrue(text.substring(hit.offset, hit.offset + 3).equals("hit", true))
            assertTrue(hit.offset >= parts[hit.segment].start && hit.offset < parts[hit.segment].end)
        }
    }
}
