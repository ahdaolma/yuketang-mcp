package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatNotebookContextTest {
    private fun note(id: String, title: String, text: String) = Note(id, title, text, "测试来源", "fixture")

    @Test fun relevantOlderSavedNoteIsFoundBeyondRecentItemsAndDuplicateTitlesKeepIds() {
        val unrelated = (1..40).map { note("recent-$it", "资料", "普通日常记录") }
        val old = note("older-enzyme", "酶的催化机制", "活性中心降低活化能")
        val sameTitle = note("different-id", old.title, "不同实验资料")
        val context = chatNotebookContext(Saved(notes = unrelated + old + sameTitle), "为什么活性中心降低活化能？")
        assertEquals(old.id, context.references.first().noteId)
        assertTrue(context.text.contains(old.content))
        val selected = chatNotebookContext(Saved(notes = listOf(old, sameTitle)), "继续", sameTitle.id)
        assertEquals(sameTitle.id, selected.references.single().noteId)
    }

    @Test fun longNoteFindsActualQueryHitAndIncludesSavedRemarksWithinBounds() {
        val huge = note("huge", "长文", "甲乙丙丁".repeat(20000) + "\n线粒体氧化磷酸化发生在内膜。\n" + "戊己庚辛".repeat(20000))
        val remark = NoteAnnotation(sourceStart = 0, sourceLength = 4, sourceHash = imageDigest(huge.content.take(4).toByteArray()), start = 0, end = 4, quote = "甲乙丙丁", kind = "remark", comment = "我新保存的备注")
        val context = chatNotebookContext(Saved(notes = listOf(huge.copy(annotations = listOf(remark)))), "氧化磷酸化在哪里？")
        val ref = context.references.single()
        assertFalse(ref.complete)
        assertTrue(ref.snapshot.contains("线粒体氧化磷酸化"))
        assertTrue(ref.snapshot.contains("我新保存的备注"))
        assertTrue(ref.snapshot.contains("非全文"))
        assertTrue(ref.snapshot.length <= CHAT_NOTE_SNAPSHOT_LIMIT)
        assertTrue(context.text.length <= CHAT_NOTE_CONTEXT_LIMIT)
        val tail = chatNoteReference(huge.copy(content = "普通内容".repeat(4000) + "\n最终结论：内膜。"), "最终结论")
        assertTrue(tail.snapshot.contains("最终结论：内膜。"))
        assertFalse(tail.snapshot.contains("后文省略"))
        assertTrue(tail.snapshot.length <= CHAT_NOTE_SNAPSHOT_LIMIT)
        val longRemark = remark.copy(comment = "重复备注".repeat(1000) + "\n备注结论：内膜。")
        val remarked = chatNoteReference(huge.copy(annotations = listOf(longRemark)), "备注结论")
        assertTrue(remarked.snapshot.contains("备注结论：内膜。"))
        assertTrue(remarked.snapshot.length <= CHAT_NOTE_SNAPSHOT_LIMIT)
    }

    @Test fun nextQuestionReadsSavedEditsAndAnnotationsAgainstMostRecentVersion() {
        val old = note("original", "生物学", "旧结论：在外膜发生。")
        val first = chatNotebookContext(Saved(notes = listOf(old)), "生物学")
        val history = listOf(Message("assistant", "首次回答", noteRefs = first.references))
        val updated = old.copy(content = "新结论：在内膜发生。", annotations = listOf(NoteAnnotation(sourceStart = 0, sourceLength = 0, sourceHash = imageDigest(byteArrayOf()), start = 0, end = 0, quote = "内膜", kind = "remark", comment = "已核对课本")))
        val next = chatNotebookContext(Saved(notes = listOf(updated)), "继续", history = history)
        assertEquals(old.id, next.references.single().noteId)
        assertNotEquals(first.references.single().hash, next.references.single().hash)
        assertTrue(next.text.contains("旧：旧"))
        assertTrue(next.text.contains("在内膜发生"))
        assertTrue(next.text.contains("已核对课本"))
        val same = chatNotebookContext(Saved(notes = listOf(updated)), "继续", history = history + Message("assistant", "第二次回答", noteRefs = next.references))
        assertTrue(same.text.contains("与上次提供的已保存版本一致"))
    }

    @Test fun truncationNeverClaimsFullDiffAndTrashDraftsAreNeverCurrentData() {
        val old = note("old", "免疫学", "抗体".repeat(8000))
        val ref = chatNoteReference(old)
        val history = listOf(Message("assistant", "旧回答", noteRefs = listOf(ref)))
        val changed = chatNotebookContext(Saved(notes = listOf(old.copy(content = old.content + "已修订"))), "继续", history = history)
        assertTrue(changed.text.contains("无法据此给出全文差异"))
        val deleted = chatNotebookContext(Saved(noteTrash = listOf(TrashedNote(old)), noteDrafts = listOf(NoteDraft(old.id, "未保存秘密"))), "抗体", old.id, history)
        assertTrue(deleted.references.isEmpty())
        assertTrue(deleted.text.contains("不可用"))
        assertFalse(deleted.text.contains("未保存秘密"))
        assertFalse(deleted.text.contains(old.content.take(100)))
        assertEquals("", chatNotebookContext(Saved(), "不存在的笔记").text)
    }

    @Test fun atMostThreeRealSnapshotsFitTheTotalContextBudget() {
        val notes = (1..12).map { note("id-$it", "酶催化-$it", "酶催化的原文".repeat(2000)) }
        val context = chatNotebookContext(Saved(notes = notes), "酶催化")
        assertEquals(3, context.references.size)
        assertTrue(context.text.length <= CHAT_NOTE_CONTEXT_LIMIT)
        context.references.forEach { assertTrue(context.text.contains(it.snapshot)) }
    }

    @Test fun libraryRequestsGetAnHonestBoundedCatalogButGreetingsDoNotReadNotes() {
        val notes = (1..8).map { note("id-$it", "第 $it 份资料", "已保存正文") }
        val library = chatNotebookContext(Saved(notes = notes), "把我的笔记发出来")
        assertEquals(3, library.references.size)
        assertTrue(library.text.contains("共 8 份"))
        assertTrue(library.text.contains("部分目录"))
        assertTrue(chatNotebookContext(Saved(), "我有哪些笔记").text.contains("共 0 份"))
        assertTrue(chatNotebookContext(Saved(notes = notes), "你好").references.isEmpty())
    }

    @Test fun unprovidedNoteImagesAreLabelledAsUnreadWhileImageChangesAlterVersion() {
        val original = note("image", "图示", "![内膜结构](study-image:${"a".repeat(64)})\n附带文字")
        val ref = chatNoteReference(original)
        assertFalse(ref.snapshot.contains("study-image:"))
        assertTrue(ref.snapshot.contains("未提供图像"))
        assertNotEquals(ref.hash, chatNoteHash(original.copy(content = original.content.replace("a".repeat(64), "b".repeat(64)))))
    }

    @Test fun messageSerializationIsBackwardCompatibleAndClampsImportedMetadata() {
        val old = Saved(conversations = listOf(Conversation(messages = listOf(Message("user", "历史原文"))))).toJson()
        val record = old.getJSONArray("conversations").getJSONObject(0).getJSONArray("messages").getJSONObject(0)
        record.remove("kind"); record.remove("noteRefs")
        val restoredOld = savedFromJson(old).chat().messages.single()
        assertEquals(emptyList<ChatNoteReference>(), restoredOld.noteRefs)
        assertEquals("", restoredOld.kind)
        val ref = chatNoteReference(note("real-id", "原笔记", "真实内容"))
        val newMessage = Message("assistant", "已保存", noteRefs = listOf(ref), kind = "note_result")
        val saved = Saved(conversations = listOf(Conversation(messages = listOf(newMessage))))
        assertEquals(newMessage, savedFromJson(saved.toJson()).chat().messages.single())
        val malicious = JSONArray((1..10).map { JSONObject().put("noteId", "id-$it").put("title", "长".repeat(400)).put("hash", "h".repeat(200)).put("snapshot", "大".repeat(50000)).put("complete", true) })
        val clamped = chatNoteReferencesFromJson(malicious)
        assertEquals(3, clamped.size)
        assertTrue(clamped.all { !it.complete && it.title.length == 300 && it.hash.length == 128 && it.snapshot.length == CHAT_NOTE_SNAPSHOT_LIMIT })
    }

    @Test fun transcriptExcludesAppResultsAndEmptyAssistantWithoutAlteringOriginalMessages() {
        val input = listOf(Message("user", "原文 **格式**"), Message("assistant", "逐字回答"), Message("assistant", ""), Message("assistant", "对话原文已保存", kind = "note_result"))
        assertEquals(input.take(2), chatTranscriptMessages(input))
        assertEquals(4, input.size)
    }
    @Test fun savedOrganizationAndReviewChangesAlsoReachTheNextQuestion() {
        val original = note("organization", "课后复习", "酶的机制")
        val edited = original.copy(folderId = "chapter", reviewState = "mastered", pinned = true, tags = listOf("考试"))
        val history = listOf(Message("assistant", "原回答", noteRefs = listOf(chatNoteReference(original))))
        val saved = Saved(notes = listOf(edited), noteFolders = listOf(NoteFolder("course", "生物化学"), NoteFolder("chapter", "酶学", "course")))
        val context = chatNotebookContext(saved, "继续解释酶", history = history)
        assertNotEquals(chatNoteHash(original), chatNoteHash(edited))
        assertTrue(context.text.contains(saved.folderPath("chapter")))
        assertTrue(context.text.contains("已掌握"))
        assertTrue(context.text.contains("置顶：是"))
        assertTrue(context.text.contains("考试"))
        assertTrue(context.text.contains("已保存版本已变化"))
    }
}
