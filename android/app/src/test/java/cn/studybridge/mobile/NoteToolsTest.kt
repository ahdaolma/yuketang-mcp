package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NoteToolsTest {
    @Test fun fullTextSearchCombinesFieldsAndPinsBeforeChronologyWithoutMutatingOriginals() {
        val old = Note("old", "遗传学", "保留段落。".repeat(2000) + "DNA 聚合酶", "周一课件", "fixture", 1, pinned = true)
        val recent = Note("new", "遗传学复习", "dna 聚合酶", "周二课件", "fixture", 2)
        val notes = listOf(recent, old)
        assertEquals(listOf(old, recent), findNotes(notes, "遗传学 DNA"))
        assertEquals(listOf(old), findNotes(notes, "周一 聚合酶"))
        assertTrue(findNotes(notes, "不存在").isEmpty())
        assertEquals(listOf(recent, old), notes)
    }
    @Test fun pinRoundTripAndLegacyNotesPreserveSourceImagesContentAndIdentity() {
        val note = Note("kept", "旧笔记", "原文\n![课件](study-image://abc)", "课件", "fixture", 88, "material", true)
        val json = Saved(notes = listOf(note)).toJson()
        assertEquals(note, savedFromJson(json).notes.single())
        json.getJSONArray("notes").getJSONObject(0).remove("pinned")
        assertEquals(note.copy(pinned = false), savedFromJson(json).notes.single())
    }
    @Test fun formattingUsesExactSelectionAndDoesNotTouchNeighboringLinesOrEmoji() {
        val bold = formatNoteSelection("甲😀乙", 1, 3, "bold")
        assertEquals("甲**😀**乙", bold.text)
        assertEquals("😀", bold.text.substring(bold.start, bold.end))
        val checklist = formatNoteSelection("前文\n一\n二\n后文", 3, 7, "todo")
        assertEquals("前文\n- [ ] 一\n- [ ] 二\n后文", checklist.text)
        assertEquals("**文字**", formatNoteSelection("", 0, 0, "bold").text)
        assertEquals("## 标题", formatNoteSelection("标题", 2, 2, "heading").text)
    }
}
