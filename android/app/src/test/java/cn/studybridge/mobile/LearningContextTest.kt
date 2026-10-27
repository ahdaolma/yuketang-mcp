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

    @Test fun sourceAndNestedKnowledgeTopicsSurviveLongNotebooksWithoutInventingPages() {
        val notes = (1..80).joinToString("\n\n") { page ->
            "## 课件 · 第 $page 页\n\n### 概念$page\n\n#### 条件$page\n\n本页原文条件$page。"
        }
        val context = priorNoteContext("课件", notes)
        val index = context.substringAfter("先前主题：").substringBefore("近期已保存笔记节选：")
        assertTrue("Index must contain source AND concept, not just page numbers", index.contains("第 80 页 → 概念80"))
        assertTrue(index.contains("概念80 / 条件80"))
        assertTrue(index.contains("第 1 页 → 概念1"))
        assertTrue(context.contains("主题索引有省略"))
        assertTrue(context.contains("本页原文条件80"))
        assertTrue(context.length <= 12000)
    }

    @Test fun fencedHeadingsCannotBeMistakenForSourceOrKnowledgeTopics() {
        val notes = "## 真实资料 · 第 7 页\n\n### 动量\n\n~~~markdown\n## 伪造来源 · 第 999 页\n### 伪造主题\n~~~\n\n#### 守恒条件\n\n仅在合外力为零时成立。"
        val index = priorNoteContext("力学", notes).substringAfter("先前主题：").substringBefore("近期已保存笔记节选：")
        assertFalse(index.contains("999")); assertFalse(index.contains("伪造主题"))
        assertTrue(index.contains("第 7 页 → 动量 / 守恒条件"))
    }

    @Test fun sourceLessPersonalNotesUseOnlyTheirRealHeadings() {
        val context = priorNoteContext("手写整理", "### 我的疑问\n\n为什么需要前提条件？")
        val index = context.substringAfter("先前主题：").substringBefore("近期已保存笔记节选：")
        assertTrue(index.contains("我的疑问"))
        assertFalse(index.contains("第 1 页"))
    }
    @Test fun headingHashesAndALongLastParagraphRetainTheirActualMeaning() {
        val context = priorNoteContext("程序设计", "## 第 3 页\n\n### C# 与 F# ###\n\n    ### 代码里的标题\n\n" + "单段很长但仍要保留近期内容。".repeat(900) + "真实末尾")
        val index = context.substringAfter("先前主题：").substringBefore("近期已保存笔记节选：")
        assertTrue(index.contains("第 3 页 → C# 与 F#"))
        assertFalse(index.contains("代码里的标题"))
        assertTrue(context.contains("真实末尾"))
        assertTrue(context.contains("段落前部省略"))
        assertTrue(context.length <= 12000)
    }
}
