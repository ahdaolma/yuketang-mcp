package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NoteReadingTest {
    @Test fun longReadingSegmentsPreserveEveryCharacterAndRespectSize() {
        val text = "# 课堂笔记\n\n" + "## 第 1 页\n\n中文笔记 🧠 English\n\n".repeat(2000) + "末尾标记"
        val pages = noteReadingSegments(text)
        assertTrue(pages.size > 1)
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(pages.all { it.end - it.start <= 12000 })
        assertTrue(pages.all { !text.substring(it.start, it.end).first().isLowSurrogate() })
        assertTrue(pages.last().display(text).endsWith("末尾标记"))
        assertEquals(listOf(NoteReadingSegment(0, 0)), noteReadingSegments(""))
    }
    @Test fun longCodeFenceIsReopenedForDisplayWithoutChangingExportContent() {
        val text = "## 代码\n\n```kotlin\n" + "val value = 42\n".repeat(200) + "```\n\n后续原文"
        val pages = noteReadingSegments(text, 512)
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(pages.any { it.prefix == "```kotlin\n" })
        assertTrue(pages.any { it.suffix == "\n```\n" })
        assertTrue(pages.last().display(text).contains("后续原文"))
    }
}
