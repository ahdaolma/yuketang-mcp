package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class AdvancedNotesTest {
    private val first = "## 第 1 页\n\n" + "原文。\n".repeat(4000) + sourceImageMarkdown("a".repeat(64), "第一页")
    private val second = "## 第 8 页\n\n==关键结论==\n\n" + sourceImageMarkdown("b".repeat(64), "第八页")
    @Test fun deletingOnePhysicalPageIncludesItsLongReadingContinuationsAndLeavesOtherBytesUntouched() {
        val text = "# 标题\n\n" + first + second
        assertTrue(noteReadingSegments(text).size > 2)
        assertEquals(listOf("第 1 页", "第 8 页"), notePageParts(text).map { it.title })
        assertEquals("# 标题\n\n" + second, removeNotePages(text, setOf(0)))
        assertEquals("# 标题\n\n" + first, removeNotePages(text, setOf(1)))
        assertEquals("# 标题\n\n", removeNotePages(text, setOf(0, 1)))
    }
    @Test fun codePageHeadingsDoNotBecomeDeletionTargetsAndInvalidSelectionsFail() {
        val text = "## 第 1 页\n\n```md\n## 第 99 页\n```\n保留。\n\n## 第 2 页\n结束。"
        assertEquals(2, notePageParts(text).size)
        assertThrows(IllegalArgumentException::class.java) { removeNotePages(text, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { removeNotePages(text, setOf(9)) }
        assertEquals("", removeNotePages("自己的短笔记", setOf(0)))
    }
    @Test fun imageReplacementOnlyChangesTheSelectedOccurrenceAndRejectsStaleOffsets() {
        val image = sourceImageMarkdown("a".repeat(64), "原图").trimEnd()
        val text = "前文\n$image\n中间\n$image\n后文"
        val next = replaceNoteImage(text, text.indexOf(image), image, "")
        assertEquals("前文\n\n中间\n$image\n后文", next)
        assertThrows(IllegalArgumentException::class.java) { replaceNoteImage(text, 0, image, "") }
    }
    @Test fun encryptedRecordPayloadKeepsDraftsAndReadingPositionsSeparateFromOriginalNotes() {
        val note = Note("id", "标题", first, "fixture", "手写")
        val draft = NoteDraft(note.id, second, 12)
        val position = NoteReadingPosition(note.id, 2, 320, "第 8 页", "hash", NoteTextAnchor(1200, 80, 12))
        val saved = Saved(notes = listOf(note), noteDrafts = listOf(draft), noteReadingPositions = listOf(position))
        assertEquals(saved, savedFromJson(saved.toJson()))
        assertEquals(first, saved.notes.single().content)
        val old = saved.toJson().apply { remove("noteDrafts"); remove("noteReadingPositions") }
        assertTrue(savedFromJson(old).noteDrafts.isEmpty())
        assertTrue(savedFromJson(old).noteReadingPositions.isEmpty())
    }
    @Test fun oldReadingRecordsRemainReadableAndInvalidTextAnchorsAreIgnored() {
        val old = NoteReadingPosition("id", 1, 88, "第 2 页", "hash")
        val json = Saved(noteReadingPositions = listOf(old)).toJson()
        json.getJSONArray("noteReadingPositions").getJSONObject(0).remove("textAnchor")
        assertEquals(old, savedFromJson(json).noteReadingPositions.single())
        assertNull(noteTextAnchorFromJson(org.json.JSONObject().put("sourceStart", -1).put("offset", 0).put("inset", 0)))
        assertNull(noteTextAnchorFromJson(org.json.JSONObject()))
    }
    @Test fun rememberedReadingLocationMatchesItsPageAfterReorderingAndClampsAfterDeletion() {
        val position = NoteReadingPosition("id", 1, 300, "第 8 页", "old")
        assertEquals(0, restoredNoteIndex(position, "new", listOf("第 8 页", "第 1 页")))
        assertEquals(1, restoredNoteIndex(position, "old", listOf("第 1 页", "第 8 页")))
        assertEquals(0, restoredNoteIndex(position, "new", listOf("第 1 页")))
        assertNotEquals(noteContentHash(first), noteContentHash(second))
    }
    @Test fun visualMarksWrapOnlyTheSelectionAndPreviewNeverLeaksMarkup() {
        StudyMark.entries.forEach { mark ->
            val result = formatNoteSelection("前文关键后文", 2, 4, "mark:${mark.name}")
            assertEquals("前文${mark.marker}关键${mark.marker}后文", result.text)
            assertEquals("关键", result.text.substring(result.start, result.end))
            assertEquals("前文关键后文", notePreview(result.text))
        }
        assertTrue(NOTE_FORMAT.contains("每段至多一处"))
        assertTrue(NOTE_FORMAT.contains("不代表") || NOTE_FORMAT.contains("不承诺") || NOTE_FORMAT.contains("不得承诺"))
        assertEquals("自定义角色", upgradedNotesPrompt("自定义角色"))
        val table = "| 内容 | 方向 |\n| --- | --- |\n| ==模板== | ++互补++ |\n\n==正文高亮==\n\n```md\n| ==代码== |\n```"
        val normalized = markdownForStudyMarks(table)
        assertTrue(normalized.contains("| **模板** | *互补* |"))
        assertTrue(normalized.contains("==正文高亮=="))
        assertTrue(normalized.contains("| ==代码== |"))
    }
}
