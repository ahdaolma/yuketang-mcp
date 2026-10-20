package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class NoteOrganizationTest {
    private val note = Note("kept", "课程笔记", "原文\n![课件](study-image://abc)", "课件", "fixture", 88, "material", true)
    @Test fun folderRoundTripAndLegacyUpgradeKeepCompleteNotes() {
        val saved = Saved(notes = listOf(note.copy(folderId = "f")), noteFolders = listOf(NoteFolder("f", "遗传学")))
        assertEquals(saved, savedFromJson(saved.toJson()))
        val legacy = saved.toJson().apply { remove("noteFolders"); getJSONArray("notes").getJSONObject(0).remove("folderId") }
        val loaded = savedFromJson(legacy)
        assertEquals(note, loaded.notes.single())
        assertTrue(loaded.noteFolders.isEmpty())
        assertEquals("未分类", loaded.folderName(note))
        assertEquals("原文 [图片]", notePreview(note.content))
        assertEquals("原文 链接", notePreview("原文 [链接](https://example.org/secret)"))
    }
    @Test fun deletingFolderOnlyChangesMembershipAndNeverDeletesNotes() {
        val other = note.copy(id = "other", folderId = "other-folder")
        val saved = Saved(notes = listOf(note.copy(folderId = "f"), other), noteFolders = listOf(NoteFolder("f", "遗传学"), NoteFolder("other-folder", "英语")))
        val next = saved.withoutNoteFolder("f")
        assertEquals(listOf(note, other), next.notes)
        assertEquals(listOf(NoteFolder("other-folder", "英语")), next.noteFolders)
        assertEquals("", next.effectiveFolder(note.copy(folderId = "missing")))
        assertEquals(saved, savedFromJson(saved.toJson()))
    }
    @Test fun renameKeepsMembershipAndDuplicateOrReservedNamesCannotOverwriteFolders() {
        val saved = Saved(notes = listOf(note.copy(folderId = "f")), noteFolders = listOf(NoteFolder("f", "遗传学"), NoteFolder("e", "English")))
        val renamed = saved.withNoteFolder("f", "  生物学  ")
        assertEquals("生物学", renamed.folderName(renamed.notes.single()))
        assertEquals(saved.notes, renamed.notes)
        listOf("", "未分类", "全部", "english", "字".repeat(31)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { saved.withNoteFolder(null, name) }
        }
        assertThrows(IllegalArgumentException::class.java) { saved.withNoteFolder("missing", "新名称") }
    }
    @Test fun clockHasOnlyHoursAndMinutesAndUsesTheCurrentTimezone() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("23:59", studyClockText(86_399_999L))
            assertEquals("00:00", studyClockText(86_400_000L))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("08:00", studyClockText(0L))
        } finally { TimeZone.setDefault(previous) }
    }
}
