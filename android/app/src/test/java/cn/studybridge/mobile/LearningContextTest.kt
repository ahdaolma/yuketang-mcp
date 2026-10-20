package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class LearningContextTest {
    @Test fun priorNotesAreBoundedAndClearlySeparatedFromCurrentSource() {
        assertEquals("", priorNoteContext("课件", ""))
        val notes = "## 蛋白质结构 · 第 1 页\n\n" + "旧内容\n".repeat(5000) + "## 酶的活性中心 · 第 2 页\n\nAI 讲解：结合与催化需区分。"
        val result = priorNoteContext("生物化学", notes)
        assertTrue(result.length <= 12000)
        assertTrue(result.contains("蛋白质结构 · 第 1 页"))
        assertTrue(result.contains("AI 讲解：结合与催化需区分。"))
        assertTrue(result.contains("不代表完整历史")); assertTrue(result.contains("不是当前页的原文"))
        assertTrue(result.contains("只整理当前提供的页面"))
    }
    @Test fun priorContextCanBeDeliveredByBothPathsWithoutReplacingCurrentAttachment() {
        val previous = priorNoteContext("生化", "## 第 1 页\n\n酶降低活化能。")
        val attachment = Message("user", "当前页原文", "data:image/png;base64,test", "生化 · 第 2 页")
        val input = noteMessages("", listOf(Message("user", previous), attachment), attachment.source!!, "")
        assertTrue(input.any { it.text == previous })
        assertTrue(input.contains(attachment)); assertEquals("生化 · 第 2 页", input[input.lastIndex - 1].source)
        assertTrue(input.any { it.text.contains(NOTE_FORMAT) })
    }
}
