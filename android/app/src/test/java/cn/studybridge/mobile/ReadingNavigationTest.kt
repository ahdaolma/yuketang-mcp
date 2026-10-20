package cn.studybridge.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadingNavigationTest {
    private val id = "a".repeat(64)
    @Test fun headingsIgnoreCodeAndKeepPageImageWithSubtopics() {
        val text = "# 课件\n\n## 第 1 页\n\n" + sourceImageMarkdown(id, "第一页") + "### 原理\n\n解释\n\n```text\n## 第 999 页\n```\n\n## 第 2 页\n\n第二页"
        val segments = noteReadingSegments(text)
        assertEquals(2, segments.size)
        assertTrue(segments.first().display(text).contains("study-image:$id"))
        assertTrue(segments.first().display(text).contains("### 原理"))
        assertTrue(segments.last().display(text).startsWith("## 第 2 页"))
        assertEquals(text, segments.joinToString("") { text.substring(it.start, it.end) })
        assertFalse(readingHeadings(text).any { it.second.contains("999") })
    }
    @Test fun notebookCheckpointAndClassPageAppendKeepMatchingImages() = runBlocking {
        var saved = Saved()
        val original = Note("batch", "课件", "", "课件", "测试")
        buildNotebook("课件", listOf(Slide(1, "", "文字")),
            read = { Attachment(null, "课件 · 第 1 页", it.text, id) },
            generate = { "讲解正文" },
            checkpoint = { saved = saved.copy(notes = listOf(original.copy(content = it.markdown))) })
        assertEquals(1, sourceImagePattern.findAll(saved.notes.single().content).count())
        val page = LivePage("12", 2)
        val run = ClassRun(LiveLesson("42", "课件", "7"), noteId = original.id, providerId = "fixture", pending = listOf(page), ended = true)
        saved = savedFromJson(saved.copy(classRun = run).toJson())
        val recovered = appendClassNotebookPage(saved, original.id, page, "第二页讲解", "课件 · 第 2 页", "b".repeat(64))
        val images = sourceImagePattern.findAll(recovered.notes.single().content).map { it.groupValues[2] }.toList()
        assertEquals(listOf(id, "b".repeat(64)), images)
        assertTrue(recovered.notes.single().content.contains("第二页讲解"))
        assertEquals(listOf(page.key), recovered.classRun!!.completed)

    }
    @Test fun sourceLabelsCannotBreakImageMarkupOrReferenceArbitraryPaths() {
        assertEquals("", sourceImageMarkdown("../../secret", "label"))
        val text = sourceImageMarkdown(id, "含有 [括号]\n换行")
        assertEquals(1, sourceImagePattern.findAll(text).count())
        assertEquals(id, sourceImagePattern.find(text)!!.groupValues[2])
        assertTrue(priorNoteContext("课件", "笔记\n$text").contains("[原页图片]"))
        assertFalse(priorNoteContext("课件", "笔记\n$text").contains("study-image:"))
    }
}
