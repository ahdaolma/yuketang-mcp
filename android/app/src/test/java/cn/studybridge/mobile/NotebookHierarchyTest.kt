package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NotebookHierarchyTest {
    @Test fun legacyFlatFoldersWithoutParentOrAssociationKeepTheirIdsAndNotes() {
        val old = Saved(noteFolders = listOf(NoteFolder("legacy", "旧分类")), notes = listOf(Note("old", "旧笔记", "正文不能改变", "fixture", "fixture", folderId = "legacy")))
        val json = old.toJson()
        json.getJSONArray("noteFolders").getJSONObject(0).apply { remove("parentId"); remove("classroomKey") }
        val restored = savedFromJson(json)
        assertEquals(old.noteFolders, restored.noteFolders)
        assertEquals(old.notes, restored.notes)
        assertEquals(setOf("legacy"), restored.folderScope("legacy"))
    }
    private val folders = listOf(NoteFolder("bio", "遗传学"), NoteFolder("dna", "第一章", "bio"), NoteFolder("eng", "英语"), NoteFolder("words", "第一章", "eng"))
    private val notes = listOf(Note("a", "根笔记", "正文", "fixture", "fixture", folderId = "bio"), Note("b", "章节笔记", "完整正文", "fixture", "fixture", folderId = "dna"), Note("c", "词汇", "英文", "fixture", "fixture", folderId = "words"))
    @Test fun rootsContainTheirSectionsWithoutMergingSameNamedSections() {
        val saved = Saved(notes = notes, noteFolders = folders)
        assertEquals(setOf("bio", "dna"), saved.folderScope("bio"))
        assertEquals(2, saved.folderNoteCount("bio"))
        assertEquals(1, saved.folderNoteCount("dna"))
        assertEquals("遗传学 / 第一章", saved.folderPath("dna"))
        assertEquals(folders, savedFromJson(saved.toJson()).noteFolders)
        assertEquals("第二章", saved.withNoteFolder(null, "第二章", "bio").noteFolders.last().name)
        assertThrows(IllegalArgumentException::class.java) { saved.withNoteFolder(null, "第一章", "bio") }
        assertThrows(IllegalArgumentException::class.java) { saved.withNoteFolder(null, "深层", "dna") }
        assertThrows(IllegalArgumentException::class.java) { saved.withNoteFolder(null, "孤立", "missing") }
    }
    @Test fun deletionKeepsEveryNoteAndMovesSectionNotesToTheirParent() {
        val saved = Saved(notes = notes, noteFolders = folders)
        val sectionRemoved = saved.withoutNoteFolder("dna")
        assertEquals(notes.map { if (it.id == "b") it.copy(folderId = "bio") else it }, sectionRemoved.notes)
        val rootRemoved = saved.withoutNoteFolder("bio")
        assertEquals(listOf("eng", "words"), rootRemoved.noteFolders.map { it.id })
        assertEquals(notes.map { if (it.id in setOf("a", "b")) it.copy(folderId = "") else it }, rootRemoved.notes)
    }
    @Test fun batchMoveIsAtomicAndRejectsMissingIdsOrTargets() {
        val saved = Saved(notes = notes, noteFolders = folders)
        assertEquals(notes.map { if (it.id in setOf("a", "b")) it.copy(folderId = "words") else it }, saved.withMovedNotes(setOf("a", "b"), "words").notes)
        assertThrows(IllegalArgumentException::class.java) { saved.withMovedNotes(setOf("a", "gone"), "bio") }
        assertThrows(IllegalArgumentException::class.java) { saved.withMovedNotes(setOf("a"), "gone") }
        assertEquals(notes, saved.notes)
    }
    @Test fun classroomFoldersUseAuthorizedIdentityAndKeepManualOrganization() {
        val lesson = LiveLesson("10", "遗传学", "42")
        val first = prepareClassNotebook(Saved(), lesson, "account-a", "fixture", "first", 1)
        assertEquals(1, first.noteFolders.size)
        assertEquals(first.noteFolders.single().id, first.notes.single().folderId)
        val root = first.noteFolders.single()
        val ended = first.copy(classRun = first.classRun!!.copy(ended = true))
        val next = prepareClassNotebook(ended.withNoteFolder(root.id, "自定义课程名"), lesson.copy(id = "11"), "account-a", "fixture", "next", 2)
        assertEquals(1, next.noteFolders.size)
        assertEquals(root.id, next.notes.first().folderId)
        val another = prepareClassNotebook(ended, lesson.copy(id = "12"), "account-b", "fixture", "another", 3)
        assertEquals(2, another.noteFolders.size)
        assertNotEquals(root.id, another.notes.first().folderId)
        val manuallyMoved = first.copy(notes = listOf(first.notes.single().copy(folderId = "")))
        assertEquals("", prepareClassNotebook(manuallyMoved, lesson, "account-a", "fixture").notes.single().folderId)
    }
}
