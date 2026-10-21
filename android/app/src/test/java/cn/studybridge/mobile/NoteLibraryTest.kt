package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NoteLibraryTest {
    private val original = Note(id = "original", title = "DNA 复制", content = "# DNA\n\n原始正文\n\n" + sourceImageMarkdown("a".repeat(64), "课件原页"), source = "遗传学", model = "fixture", createdAt = 10,
        tags = listOf("生物", "考试"), reviewState = "reviewing", relatedNoteIds = listOf("other"))
    private val other = Note(id = "other", title = "酶的作用", content = "另一份独立正文", source = "手写", model = "fixture", createdAt = 20)

    @Test fun olderSchemaLoadsDefaultMetadataAndNewSchemaRoundTripsRecoveryRecords() {
        val saved = Saved(notes = listOf(original, other), noteTrash = listOf(TrashedNote(original.copy(id = "trash"), 30)),
            noteHistory = listOf(NoteRevision("revision", original, 40)), noteModifiedTimes = mapOf(original.id to 50), noteSort = "title")
        assertEquals(saved, savedFromJson(saved.toJson()))
        val old = saved.toJson().apply {
            listOf("noteTrash", "noteHistory", "noteModifiedTimes", "noteSort").forEach { remove(it) }
            getJSONArray("notes").objects().forEach { note -> listOf("tags", "reviewState", "relatedNoteIds").forEach { note.remove(it) } }
        }
        val loaded = savedFromJson(old)
        assertEquals(original.copy(tags = emptyList(), reviewState = "unreviewed", relatedNoteIds = emptyList()), loaded.notes.first())
        assertTrue(loaded.noteHistory.isEmpty()); assertTrue(loaded.noteTrash.isEmpty())
        assertEquals("updated", loaded.noteSort)
        val unknown = original.notebookJson().put("reviewState", "future-state")
        assertEquals("unreviewed", notebookNoteFromJson(unknown).reviewState)
    }

    @Test fun manualEditsAndTrashRetainOriginalContentImagesAndPersonalMetadata() {
        val before = Saved(notes = listOf(original, other))
        val edited = recordNotebookMutation(before, before.copy(notes = listOf(original.copy(content = original.content + "\n新增讲解"), other)), 100)
        assertEquals(original, edited.noteHistory.single().note)
        assertEquals(100L, edited.noteModifiedTimes[original.id])
        val metadata = recordNotebookMutation(edited, edited.withNoteDetails(original.id, listOf("#生物", " BIO ", "bio", "考试"), "mastered", listOf(original.id, other.id, "missing", other.id)), 110)
        assertEquals(listOf("生物", "BIO", "考试"), metadata.notes.first().tags)
        assertEquals(listOf(other.id), metadata.notes.first().relatedNoteIds)
        assertEquals(1, metadata.noteHistory.size)
        val deleted = recordNotebookMutation(metadata, metadata.copy(notes = listOf(other)), 120)
        assertEquals(metadata.notes.first(), deleted.noteTrash.single().note)
        assertEquals(original, deleted.noteHistory.single().note)
        assertTrue(deleted.imageBearingNotes().any { sourceImagePattern.containsMatchIn(it.content) })
        val restored = deleted.restoreTrashedNote(original.id, 130)
        assertEquals(metadata.notes.first(), restored.notes.first())
        assertEquals(other, restored.notes.last())
        assertTrue(restored.noteTrash.isEmpty())
    }

    @Test fun historyAndIdCollisionRestoreAsCopiesWithoutOverwritingCurrentNotesOrDrafts() {
        val active = original.copy(content = "当前正文，不能被历史覆盖。")
        val draft = NoteDraft(active.id, "仍在编辑的草稿", baseContent = active.content)
        val saved = Saved(notes = listOf(active, other), noteDrafts = listOf(draft), noteTrash = listOf(TrashedNote(original)), noteHistory = listOf(NoteRevision("revision", original)))
        val history = saved.restoreNoteRevision("revision")
        assertEquals(active, history.notes.single { it.id == active.id })
        assertEquals(original.content, history.notes.first().content)
        assertNotEquals(original.id, history.notes.first().id)
        assertEquals(saved.noteDrafts, history.noteDrafts)
        assertEquals(saved.noteHistory, history.noteHistory)
        val recovered = saved.restoreTrashedNote(original.id)
        assertEquals(active, recovered.notes.single { it.id == active.id })
        assertNotEquals(active.id, recovered.notes.first().id)
        assertEquals(original.content, recovered.notes.first().content)
        assertEquals(listOf(draft), recovered.noteDrafts)
    }

    @Test fun filtersCombineMetadataAndSearchWhilePinnedNotesKeepPriority() {
        val pinned = original.copy(id = "pinned", title = "Z pinned", pinned = true, createdAt = 1)
        val fresh = original.copy(id = "fresh", title = "A recent", createdAt = 30)
        val saved = Saved(notes = listOf(original, other, pinned, fresh), noteModifiedTimes = mapOf(original.id to 100, fresh.id to 50))
        assertEquals(listOf("pinned", "original", "fresh", "other"), libraryNotes(saved, "", null, null, null).map { it.id })
        assertEquals(listOf("pinned", "fresh", "other", "original"), libraryNotes(saved.copy(noteSort = "created"), "", null, null, null).map { it.id })
        assertEquals(listOf("pinned", "fresh", "original", "other"), libraryNotes(saved.copy(noteSort = "title"), "", null, null, null).map { it.id })
        assertEquals(listOf(original), libraryNotes(saved, "DNA 复制", null, "生物", "reviewing"))
        assertTrue(libraryNotes(saved, "", null, "生物", "mastered").isEmpty())
        assertEquals(listOf(original), linkedNotes(saved.copy(notes = listOf(original, other)), other))
        val many = normalizedNoteTags((0..20).map { " #tag$it " } + listOf("#TAG0"))
        assertEquals(12, many.size)
        assertEquals("tag0", many.first())
    }

    @Test fun permanentPurgeRemovesOnlyExplicitTrashAndItsRecoveryReferences() {
        val deleted = original.copy(id = "deleted")
        val untouched = original.copy(id = "untouched")
        val active = other.copy(relatedNoteIds = listOf("deleted", "untouched"))
        val saved = Saved(notes = listOf(active), noteTrash = listOf(TrashedNote(deleted), TrashedNote(untouched)),
            noteHistory = listOf(NoteRevision("gone", deleted), NoteRevision("kept", untouched)),
            noteModifiedTimes = mapOf("deleted" to 1L, "untouched" to 2L), noteDrafts = listOf(NoteDraft("deleted", "草稿"), NoteDraft("untouched", "保留草稿")))
        val purged = saved.purgeTrashedNotes(setOf("deleted", "not-present"))
        assertEquals(listOf(untouched), purged.noteTrash.map { it.note })
        assertEquals(listOf("kept"), purged.noteHistory.map { it.id })
        assertEquals(mapOf("untouched" to 2L), purged.noteModifiedTimes)
        assertEquals(listOf("untouched"), purged.noteDrafts.map { it.noteId })
        assertEquals(active.copy(relatedNoteIds = listOf("untouched")), purged.notes.single())
        assertEquals(emptyList<TrashedNote>(), purged.purgeTrashedNotes(setOf("untouched")).noteTrash)
    }
}
