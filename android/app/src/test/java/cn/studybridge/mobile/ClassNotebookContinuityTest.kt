package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ClassNotebookContinuityTest {
    private val lesson = LiveLesson("42", "遗传学", "7")
    @Test fun savedEditRetainsPagesAppendedAfterEditingStartedAndRefusesOtherChanges() {
        assertEquals("我的修改\n新页", mergeNoteEdit("旧文", "我的修改", "旧文\n新页"))
        assertEquals("插入", mergeNoteEdit("", "插入", ""))
        assertTrue(runCatching { mergeNoteEdit("旧文", "修改", "另一份修改") }.isFailure)
        assertEquals("旧文", mergeNoteEdit("旧文", "旧文", "旧文"))
    }
    @Test fun endedAndInterleavedLessonsReuseTheirOwnNotebookAndSkipCompletedPagesAfterReload() {
        var saved = Saved(region = "standard")
        saved = prepareClassNotebook(saved, lesson, "student-1", "provider", "note-1", 100)
        saved = appendClassNotebookPage(saved, "note-1", LivePage("12", 1), "第一页", "原课件", "")
        saved = saved.copy(classRun = saved.classRun!!.copy(ended = true, pending = emptyList()))
        saved = prepareClassNotebook(saved, LiveLesson("43", "同名课", "7"), "student-1", "provider", "note-2", 200)
        saved = saved.copy(classRun = saved.classRun!!.copy(ended = true))
        saved = prepareClassNotebook(savedFromJson(saved.toJson()), lesson, "student-1", "provider", "unused", 300)
        assertEquals(2, saved.notes.size); assertEquals("note-1", saved.classRun!!.noteId)
        assertEquals(listOf("12:1"), saved.classRun!!.completed)
        assertFalse(saved.classRun!!.ended)
        assertTrue(enqueueClassPages(saved.classRun!!, listOf(LivePage("12", 1))).pending.isEmpty())
        val same = appendClassNotebookPage(saved, "note-1", LivePage("12", 1), "不应再追加", "原课件", "")
        assertEquals(saved.notes, same.notes)
    }
    @Test fun accountRegionAndClassroomArePartOfIdentityAndTitlesAreNot() {
        val first = prepareClassNotebook(Saved(), lesson, "student-1", "provider", "one", 1)
        val ended = first.copy(classRun = first.classRun!!.copy(ended = true))
        assertEquals(2, prepareClassNotebook(ended, lesson, "student-2", "provider", "two", 2).notes.size)
        assertEquals(2, prepareClassNotebook(ended.copy(region = "hetang"), lesson, "student-1", "provider", "two", 2).notes.size)
        assertEquals(2, prepareClassNotebook(ended, lesson.copy(classroomId = "8"), "student-1", "provider", "two", 2).notes.size)
        assertEquals("one", prepareClassNotebook(ended, lesson.copy(title = "改过课名"), "student-1", "provider", "two", 2).classRun!!.noteId)
    }
    @Test fun draftBaseAndPersonalRemarksRoundTripWithoutChangingMarkdown() {
        val raw = "## 第一页\n\nDNA 复制需要模板。"
        val mark = NoteAnnotation(sourceStart = 0, sourceLength = raw.length, sourceHash = imageDigest(raw.toByteArray()), start = 0, end = 3, quote = "DNA", kind = "remark", comment = "这里要回看")
        val note = Note(title = "原文", content = raw, source = "课堂", model = "测试", annotations = listOf(mark))
        val saved = Saved(notes = listOf(note), noteDrafts = listOf(NoteDraft(note.id, "改稿", 10, raw)))
        val reloaded = savedFromJson(saved.toJson())
        assertEquals(saved.notes, reloaded.notes); assertEquals(raw, reloaded.noteDrafts.single().baseContent)
        assertTrue(mark.isAttached(raw)); assertFalse(mark.isAttached("修改过" + raw))
        assertEquals(raw, note.content)
    }
    @Test fun fullTextSearchFindsBodyBeyondPreviewAndReturnsCorrectSegment() {
        val text = "## 第 1 页\n" + "第一段".repeat(300) + "\n\n## 第 2 页\n独有关键词 END"
        val hits = searchNoteContent(text, "关键词")
        assertEquals(1, hits.size); assertEquals(1, hits.single().segment)
        assertTrue(hits.single().snippet.contains("关键词"))
        assertTrue(searchNoteContent(text, "   ").isEmpty())
        assertEquals(1, searchNoteContent(text, "end").size)
    }
    @Test fun superFocusModeIsOptionalDurableAndReachesNotePrompts() {
        assertFalse(savedFromJson(Saved().toJson().apply { remove("superFocusNotes") }).superFocusNotes)
        assertTrue(savedFromJson(Saved(superFocusNotes = true).toJson()).superFocusNotes)
        val ordinary = noteMessages("自定义角色", listOf(Message("user", "真实原文")), "第 1 页", "基础薄弱")
        val focus = noteMessages("自定义角色", listOf(Message("user", "真实原文")), "第 1 页", "基础薄弱", true)
        assertFalse(ordinary.any { it.text.contains("【超重点模式") })
        assertTrue(focus.any { it.text.contains(SUPER_FOCUS_NOTE_FORMAT) })
        assertTrue(focus.first().text.contains("自定义角色")); assertTrue(focus.first().text.contains("基础薄弱"))
        assertTrue(focus.any { it.text == "真实原文" })

    }
}
