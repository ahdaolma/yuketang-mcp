package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NotePresentationTest {
    @Test fun displayRemovesOnlyExactRepeatedTitleAndKeepsOriginalAndCode() {
        val original = "# 第一章\n\n## 第 1 页\n\n```md\n# 第一章\n```\n\n原文保留。"
        val displayed = noteTextForDisplay(original, "第一章 · 第 1–10 页")
        assertTrue(displayed.startsWith("## 第 1 页"))
        assertTrue(displayed.contains("```md\n# 第一章\n```"))
        assertEquals(original, noteTextForDisplay(original, "另一章"))
        assertEquals("# 第一章\n\n" + displayed, original)
    }
    @Test fun customNotePromptIsPreservedAndDefaultsHaveMobileReadingRules() {
        val custom = "按我自定义的五个部分整理"
        assertEquals(custom, upgradedNotesPrompt(custom))
        assertTrue(NOTE_FORMAT.contains("封面")); assertTrue(NOTE_FORMAT.contains("不重复"))
        assertTrue(NOTES_PROMPT.contains("AI 讲解/补充"))
    }
    @Test fun shipped105DefaultUpgradesButAnEditedCopyIsPreserved() {
        val previous = requireNotNull(javaClass.getResource("/notes-prompt-1.0.5.txt")).readText()
        assertNotEquals(previous, NOTES_PROMPT)
        assertEquals(NOTES_PROMPT, upgradedNotesPrompt(previous))
        val custom = previous + "\n请按我自己的习惯整理。"
        assertEquals(custom, upgradedNotesPrompt(custom))
    }
}
