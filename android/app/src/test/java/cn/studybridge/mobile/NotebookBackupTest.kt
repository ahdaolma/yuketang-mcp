package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NotebookBackupTest {
    @Test fun legacyBackupFolderWithoutParentKeepsMembershipAndContent() {
        val legacy = Saved(noteFolders = listOf(NoteFolder("legacy", "旧分类")), notes = listOf(note("old").copy(folderId = "legacy")))
        val bytes = changed(legacy) { it.getJSONObject("data").getJSONArray("noteFolders").getJSONObject(0).remove("parentId") }
        val restored = restore(bytes).data
        assertEquals(legacy.noteFolders, restored.noteFolders)
        assertEquals(legacy.notes, restored.notes)
    }
    @Test fun notebookSectionsRoundTripAndMergeUnderTheirOwnParentsWithoutAccountBindings() {
        val folders = listOf(NoteFolder("bio", "遗传学", classroomKey = "private-binding"), NoteFolder("bio-one", "第一章", "bio"), NoteFolder("eng", "英语"), NoteFolder("eng-one", "第一章", "eng"))
        val incoming = Saved(noteFolders = folders, notes = listOf(Note("chapter-note", "章节", "完整正文", "fixture", "fixture", folderId = "eng-one")))
        val restored = restore(archive(incoming)).data
        assertEquals(folders.map { it.copy(classroomKey = "") }, restored.noteFolders)
        val local = Saved(noteFolders = listOf(NoteFolder("bio", "本机课程"), NoteFolder("local-eng", "英语"), NoteFolder("local-one", "第一章", "local-eng")))
        val merged = mergeNotebookBackup(local, restored)
        assertEquals("local-one", merged.notes.single().folderId)
        assertEquals("local-eng", merged.noteFolders.single { it.id == "local-one" }.parentId)
        assertEquals(2, merged.noteFolders.count { it.name == "第一章" })
        val importedBio = merged.noteFolders.single { it.name == "遗传学" }
        assertNotEquals("bio", importedBio.id)
        assertEquals(importedBio.id, merged.noteFolders.single { it.id == "bio-one" }.parentId)
    }
    private fun note(id: String, content: String = "正文 $id", links: List<String> = emptyList()) = Note(id, "笔记 $id", content, "手写", "手写", createdAt = 100, relatedNoteIds = links)
    private fun archive(data: Saved): ByteArray = ByteArrayOutputStream().also { writeNotebookBackup(it, data, { error("unexpected image") }, { null }) }.toByteArray()
    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { out ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; out[entry.name] = zip.readBytes() } }
    }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun changed(data: Saved = Saved(notes = listOf(note("a"))), change: (JSONObject) -> Unit): ByteArray {
        val json = JSONObject(entries(archive(data)).getValue("notebook.json").toString(Charsets.UTF_8)); change(json)
        return zip(listOf("notebook.json" to json.toString().toByteArray(Charsets.UTF_8)))
    }
    private fun rejects(bytes: ByteArray) { assertNotNull("malformed archive was accepted", runCatching { readNotebookBackup(ByteArrayInputStream(bytes)) }.exceptionOrNull()) }
    private fun restore(bytes: ByteArray) = readNotebookBackup(ByteArrayInputStream(bytes))

    @Test fun exportWhitelistsNotebookDataAndClearsAccountAndClassBindings() {
        val bound = note("a").copy(materialId = "private-material-id", classNotebook = ClassNotebookBinding("standard", "private-account-id", "secret-lesson", "secret-room"))
        val saved = Saved(notes = listOf(bound), providers = listOf(Provider("provider-secret", key = "API_SECRET", baseUrl = "https://private.example", model = "private-model")), selectedProvider = "provider-secret",
            session = "RAIN_SESSION_SECRET", username = "PERSONAL_USERNAME", conversations = listOf(Conversation(messages = listOf(Message("user", "PRIVATE_CHAT_SECRET")))),
            classRun = ClassRun(LiveLesson("secret-lesson", "secret-class", "secret-room"), noteId = "a", providerId = "provider-secret"),
            chatgptAccounts = listOf(ChatGptAccount(clientId = "private-client", subject = "private-subject", email = "private@example.com", accessToken = "ACCESS_SECRET", refreshToken = "REFRESH_SECRET")), learnerProfile = "PRIVATE_PROFILE")
        val bytes = archive(saved)
        val json = entries(bytes).getValue("notebook.json").toString(Charsets.UTF_8)
        listOf("API_SECRET", "RAIN_SESSION_SECRET", "PRIVATE_CHAT_SECRET", "ACCESS_SECRET", "REFRESH_SECRET", "PRIVATE_PROFILE", "private-account-id", "secret-lesson", "private-material-id", "PERSONAL_USERNAME", "providers", "session", "classRun", "conversations").forEach { assertFalse(it, json.contains(it)) }
        val restored = restore(bytes).data
        assertTrue(restored.providers.isEmpty()); assertTrue(restored.conversations.isEmpty()); assertTrue(restored.session.isEmpty()); assertNull(restored.classRun)
        assertEquals(bound.copy(materialId = "", classNotebook = null), restored.notes.single())
        assertNotNull(saved.notes.single().classNotebook)
    }

    @Test fun exportSanitizesLegitimateDanglingMetadataAcrossActiveTrashAndHistory() {
        val active = note("a", links = listOf("trash", "historical", "gone", "a")).copy(folderId = "deleted-folder")
        val trashed = note("trash").copy(folderId = "deleted-folder")
        val historical = note("historical").copy(folderId = "deleted-folder")
        val saved = Saved(notes = listOf(active), noteTrash = listOf(TrashedNote(trashed, 10)), noteHistory = listOf(NoteRevision("revision", historical, 9)),
            noteModifiedTimes = mapOf("a" to 20, "trash" to 10, "historical" to 9, "gone" to 1),
            noteDrafts = listOf(NoteDraft("a", "草稿", 30, null), NoteDraft("trash", "不再活动的草稿", 20)),
            noteReadingPositions = listOf(NoteReadingPosition("a", 1, 2, "页", "hash", NoteTextAnchor(20, 10, 4)), NoteReadingPosition("historical", 0, 0, "", "")))
        val restored = restore(archive(saved)).data
        assertTrue(restored.imageBearingNotes().all { it.folderId.isEmpty() })
        assertEquals(listOf("trash", "historical"), restored.notes.single().relatedNoteIds)
        assertEquals(setOf("a", "trash", "historical"), restored.noteModifiedTimes.keys)
        assertEquals(listOf("a"), restored.noteDrafts.map { it.noteId }); assertNull(restored.noteDrafts.single().baseContent)
        assertEquals(listOf("a"), restored.noteReadingPositions.map { it.noteId })
        assertEquals(NoteTextAnchor(20, 10, 4), restored.noteReadingPositions.single().textAnchor)
        assertEquals("deleted-folder", saved.notes.single().folderId)
        assertTrue("gone" in saved.noteModifiedTimes)
    }

    @Test fun readerRejectsDanglingReferencesEvenThoughWriterSanitizesLocalSnapshots() {
        rejects(changed { it.getJSONObject("data").getJSONArray("notes").getJSONObject(0).put("folderId", "missing") })
        rejects(changed { it.getJSONObject("data").getJSONArray("notes").getJSONObject(0).put("relatedNoteIds", JSONArray().put("missing")) })
        rejects(changed { it.getJSONObject("data").getJSONArray("notes").getJSONObject(0).put("relatedNoteIds", JSONArray().put("a")) })
        rejects(changed { it.getJSONObject("data").getJSONObject("noteModifiedTimes").put("missing", 1) })
        rejects(changed(Saved(notes = listOf(note("a"), note("b")))) { it.getJSONObject("data").getJSONArray("notes").getJSONObject(0).put("relatedNoteIds", JSONArray().put("b").put("b")) })
        val onlyTrash = Saved(noteTrash = listOf(TrashedNote(note("trash"), 10)))
        rejects(changed(onlyTrash) { it.getJSONObject("data").getJSONArray("noteDrafts").put(JSONObject().put("noteId", "trash").put("content", "草稿").put("updatedAt", 1).put("baseContent", JSONObject.NULL)) })
        rejects(changed(onlyTrash) { it.getJSONObject("data").getJSONArray("noteReadingPositions").put(JSONObject().put("noteId", "trash").put("index", 0).put("scroll", 0).put("anchor", "").put("contentHash", "")) })
    }

    @Test fun readerRejectsTraversalUnknownFieldsDuplicatesAndDeepJson() {
        val valid = entries(archive(Saved(notes = listOf(note("a"))))).getValue("notebook.json")
        rejects(zip(listOf("../notebook.json" to valid)))
        rejects(zip(listOf("notebook.json" to valid, "unlisted.txt" to byteArrayOf(1))))
        rejects(changed { it.getJSONObject("data").put("session", "injected-secret") })
        rejects(changed { it.getJSONObject("data").getJSONArray("notes").getJSONObject(0).put("classNotebook", JSONObject()) })
        rejects(changed { it.put("version", 2) })
        rejects(zip(listOf("notebook.json" to ("[".repeat(65) + "0" + "]".repeat(65)).toByteArray())))
        val duplicate = zip(listOf("notebook.json" to valid, "notebook.jsox" to valid))
        val from = "notebook.jsox".toByteArray(); val to = "notebook.json".toByteArray()
        for (start in 0..duplicate.size - from.size) if (from.indices.all { duplicate[start + it] == from[it] }) to.copyInto(duplicate, start)
        rejects(duplicate)
    }

    @Test fun readerRejectsImageHashMismatchMissingOrUnreferencedAssetsAndInvalidMarks() {
        val original = "fixture-image-bytes".toByteArray(); val id = imageDigest(original)
        val saved = Saved(notes = listOf(note("a", sourceImageMarkdown(id, "原页"))))
        val output = ByteArrayOutputStream(); writeNotebookBackup(output, saved, { original }, { null })
        val files = entries(output.toByteArray())
        rejects(zip(files.map { (name, value) -> name to if (name.startsWith("images/")) "tampered".toByteArray() else value }))
        rejects(zip(files.filterKeys { it == "notebook.json" }.toList()))
        rejects(zip(entries(archive(Saved())).toList() + ("images/$id.image" to original)))
        val badMark = "{\"version\":1,\"strokes\":[{\"width\":0.01,\"highlight\":false,\"points\":[[-1,0]]}]}".toByteArray()
        rejects(zip(files.toList() + ("marks/$id.json" to badMark)))
        val unknownMark = "{\"version\":1,\"strokes\":[],\"session\":\"secret\"}".toByteArray()
        rejects(zip(files.toList() + ("marks/$id.json" to unknownMark)))
    }

    @Test fun streamingReaderRejectsPerEntryAndTotalExpandedLimits() {
        fun repeatedZip(parts: Int, each: Int, manifest: Boolean = false): ByteArray {
            val output = ByteArrayOutputStream(); val block = ByteArray(8192)
            ZipOutputStream(output).use { zip -> repeat(parts) { part ->
                block.fill(part.toByte())
                val digest = MessageDigest.getInstance("SHA-256"); var remaining = each
                while (remaining > 0) { val count = minOf(block.size, remaining); digest.update(block, 0, count); remaining -= count }
                val id = digest.digest().joinToString("") { "%02x".format(it) }
                zip.putNextEntry(ZipEntry(if (manifest) "notebook.json" else "images/$id.image"))
                remaining = each
                while (remaining > 0) { val count = minOf(block.size, remaining); zip.write(block, 0, count); remaining -= count }
                zip.closeEntry()
            } }
            return output.toByteArray()
        }
        // ZipOutputStream uses unknown-size/data-descriptor entries: compressed
        // header sizes cannot bypass the reader's incremental expanded counts.
        rejects(repeatedZip(1, NOTEBOOK_BACKUP_JSON_LIMIT + 1, manifest = true))
        rejects(repeatedZip(1, NOTEBOOK_BACKUP_IMAGE_LIMIT + 1))
        rejects(repeatedZip(6, NOTEBOOK_BACKUP_IMAGE_LIMIT))
    }

    @Test fun readerRejectsRecordCountAndAssetCountLimits() {
        rejects(changed { json ->
            val notes = JSONArray(); repeat(NOTEBOOK_BACKUP_RECORD_LIMIT + 1) { notes.put(JSONObject().put("id", "note-$it")) }; json.getJSONObject("data").put("notes", notes)
        })
        val files = mutableListOf<Pair<String, ByteArray>>()
        repeat(NOTEBOOK_BACKUP_ASSET_LIMIT + 1) { index -> val bytes = index.toString().toByteArray(); files += "images/${imageDigest(bytes)}.image" to bytes }
        rejects(zip(files))
    }

    @Test fun mergeRemapsCollidingCyclicLinksAndPreservesAllLocalAccountClassAndDraftState() {
        val localA = note("a", "本地 a"); val localB = note("b", "本地 b")
        val localDrafts = listOf(NoteDraft("a", "本机还未保存的草稿", 77))
        val run = ClassRun(LiveLesson("42", "正在上的课", "7"), noteId = "a", providerId = "p", pending = listOf(LivePage("12", 3)))
        val current = Saved(notes = listOf(localA, localB), noteDrafts = localDrafts, classRun = run, session = "local-session", providers = listOf(Provider("p", key = "local-key")),
            conversations = listOf(Conversation("chat", messages = listOf(Message("user", "本机聊天")))), noteReadingPositions = listOf(NoteReadingPosition("a", 2, 3, "原页", "local")))
        val incoming = Saved(notes = listOf(note("a", "导入 a", listOf("b")), note("b", "导入 b", listOf("a")), note("independent")),
            noteDrafts = listOf(NoteDraft("a", "导入的未保存草稿", 88, "导入 a")), noteReadingPositions = listOf(NoteReadingPosition("a", 4, 5, "导入页", "incoming")),
            noteHistory = listOf(NoteRevision("rev", note("a", "导入旧版", listOf("b")), 60)))
        val merged = mergeNotebookBackup(current, incoming)
        assertEquals(localA, merged.notes.first { it.id == "a" }); assertEquals(localB, merged.notes.first { it.id == "b" })
        val a = merged.notes.single { it.content == "导入 a" }; val b = merged.notes.single { it.content == "导入 b" }
        assertNotEquals("a", a.id); assertNotEquals("b", b.id); assertEquals(listOf(b.id), a.relatedNoteIds); assertEquals(listOf(a.id), b.relatedNoteIds)
        assertEquals("independent", merged.notes.single { it.id == "independent" }.id)
        assertSame(run, merged.classRun); assertSame(current.providers, merged.providers); assertSame(current.conversations, merged.conversations); assertEquals("local-session", merged.session)
        assertSame(localDrafts, merged.noteDrafts)
        val recovered = merged.notes.single { it.content == "导入的未保存草稿" }
        assertTrue(recovered.title.endsWith("（恢复草稿）")); assertTrue(a.id in recovered.relatedNoteIds); assertNull(recovered.classNotebook)
        assertEquals(current.noteReadingPositions.first(), merged.noteReadingPositions.first())
        assertTrue(merged.noteReadingPositions.any { it.noteId == a.id && it.index == 4 })
        assertEquals(merged, mergeNotebookBackup(merged, incoming))
    }

    @Test fun reimportAfterLocalRelationshipEditPreservesEditedGraphAndCreatesOneStableCopy() {
        val incoming = Saved(notes = listOf(note("a", links = listOf("b")), note("b", links = listOf("a"))))
        val current = Saved(notes = listOf(note("a", "本机不同内容")))
        val first = mergeNotebookBackup(current, incoming)
        val importedA = first.notes.single { it.content == "正文 a" }
        val edited = first.copy(notes = first.notes.map { if (it.id == importedA.id) it.copy(relatedNoteIds = emptyList()) else it })
        val second = mergeNotebookBackup(edited, incoming)
        assertEquals(edited.notes.first { it.id == importedA.id }, second.notes.first { it.id == importedA.id })
        assertEquals(2, second.notes.count { it.content == "正文 a" })
        assertEquals(second, mergeNotebookBackup(second, incoming))
    }

    @Test fun lifecycleCollisionsDoNotOverwriteTrashOrReviveExistingDeletedCopies() {
        val current = Saved(notes = listOf(note("active")), noteTrash = listOf(TrashedNote(note("trashed"), 20)))
        val incoming = Saved(notes = listOf(note("trashed")), noteTrash = listOf(TrashedNote(note("active"), 10)))
        val merged = mergeNotebookBackup(current, incoming)
        assertEquals(current.notes.first(), merged.notes.first()); assertEquals(current.noteTrash.first(), merged.noteTrash.first())
        assertEquals(2, merged.notes.size); assertEquals(2, merged.noteTrash.size)
        assertTrue(merged.notes.none { n -> merged.noteTrash.any { it.note.id == n.id } })
        assertEquals(merged, mergeNotebookBackup(merged, incoming))
    }

    @Test fun backupLeavesCallerStreamsOpen() {
        var outputClosed = false
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        writeNotebookBackup(output, Saved(notes = listOf(note("a"))), { error("unexpected image") }, { null })
        assertFalse(outputClosed)
        var inputClosed = false
        val input = object : ByteArrayInputStream(output.toByteArray()) { override fun close() { inputClosed = true; super.close() } }
        readNotebookBackup(input); assertFalse(inputClosed)
    }
}
