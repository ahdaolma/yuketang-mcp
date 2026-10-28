package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CourseKnowledgeTest {
    private val course = NoteFolder("course", "生理学")
    private val chapter = NoteFolder("chapter", "血液循环", course.id)
    private fun note(id: String = "n", body: String = "## 第 44 页\n\n### 电活动\n\n心电图记录电活动，不是心脏收缩曲线。") = Note(id, "心电图", body, "已保存课堂笔记", "fixture", folderId = chapter.id)
    private fun saved(vararg notes: Note) = Saved(notes = notes.toList(), noteFolders = listOf(course, chapter))

    @Test fun sourceOffsetsCoverEveryCharacterAndIgnoreCodeHeadings() {
        val original = note(body = "# 心电图\n\n## 第 44 页\n\n```text\n## 第 999 页\n```\n" + "完整文字😀".repeat(1400) + "\n## 第 45 页\nPR 间期正常范围")
        val parts = knowledgePassages(original)
        assertTrue(parts.size > 5)
        assertEquals(original.content, parts.joinToString("") { original.content.substring(it.start, it.end) })
        assertFalse(parts.any { it.label.contains("999") })
        parts.forEach {
            assertTrue(it.end - it.start <= 1800)
            assertFalse(Character.isLowSurrogate(it.text.first()))
            assertEquals(KnowledgeLocation(it.noteId, it.hash, it.start, it.end), knowledgeLocation(it.url))
            assertEquals(original.content.substring(it.start, it.end), knowledgeSourceText(saved(original), knowledgeLocation(it.url)!!)!!.second)
        }
        assertTrue(parts.last().label.contains("45"))
    }

    @Test fun sourceIsBoundToSavedBodyAndCannotUseStaleOffsetsAfterEditOrDeletion() {
        val original = note(id = "笔记 / 特殊%ID")
        val location = knowledgeLocation(knowledgePassages(original).last().url)!!
        assertNotNull(knowledgeSourceText(saved(original), location))
        assertNotNull(knowledgeSourceText(saved(original.copy(content = original.content + "\n## 第 45 页\n新增课堂笔记")), location))
        assertNull(knowledgeSourceText(saved(original.copy(content = "改过正文")), location))
        assertNull(knowledgeSourceText(saved().copy(noteTrash = listOf(TrashedNote(original))), location))
        assertNull(knowledgeLocation("study-note:n/${"a".repeat(64)}/0/9999999999"))
        assertNull(knowledgeLocation("study-note:n/${"a".repeat(64)}/0/6000"))
        assertNull(knowledgeLocation("https://example.com"))
    }

    @Test fun deepKnowledgeHeadingsKeepTheirExplanationAndNeverLoseCSharpNames() {
        val original = note(body = "# 课程\n\n## 第 44 页\n\n#### C# 与 F#\n\n这里是比较的证据。\n\n##### 使用条件\n\n只在此条件下适用。")
        val passages = knowledgePassages(original)
        assertEquals(2, passages.size)
        assertTrue(passages.first().label.contains("C# 与 F#"))
        assertTrue(passages.first().text.contains("这里是比较的证据"))
        assertTrue(passages.last().label.contains("使用条件"))
        assertEquals(original.content, passages.joinToString("") { original.content.substring(it.start, it.end) })
    }

    @Test fun overviewReadsCompleteScopeWithRemarksAndExcludesOtherCoursesAndOldOverviews() {
        val original = note()
        val remark = NoteAnnotation(sourceStart = 0, sourceLength = original.content.length, sourceHash = noteContentHash(original.content), start = 0, end = 2, quote = "心电", kind = "remark", comment = "人工核对：课件未说明此范围")
        val other = note("other", "不要读到其他课程").copy(folderId = "english")
        val oldOverview = note("overview", "旧总览不可循环整理").copy(overviewSources = mapOf(original.id to knowledgeNoteVersion(original)))
        val input = knowledgeOverviewInput(saved(original.copy(annotations = listOf(remark)), other, oldOverview), course.id)
        assertEquals(listOf(original.id), input.notes.map { it.id })
        assertTrue(input.text.contains(remark.comment))
        assertFalse(input.text.contains(other.content))
        assertFalse(input.text.contains(oldOverview.content))
        assertTrue(input.text.contains("不是当前原课件"))
        val request = knowledgeOverviewMessages(saved(original).copy(notesPrompt = "用户笔记角色：先解释条件", learnerProfile = "学习情况：基础薄弱", superFocusNotes = true), input)
        assertTrue(request.first().text.contains("用户笔记角色：先解释条件"))
        assertTrue(request.first().text.contains("基础薄弱"))
        assertTrue(request[1].text.contains("超重点模式"))
        assertEquals(input.text, request.last().text)
    }

    @Test fun longOverviewFailsExplicitlyRatherThanSilentlyDroppingMaterial() {
        val long = note(body = "知识".repeat(KNOWLEDGE_OVERVIEW_LIMIT))
        assertTrue(runCatching { knowledgeOverviewInput(saved(long), course.id) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { knowledgeOverviewInput(saved(note()), null) }.isFailure)
        assertTrue(runCatching { knowledgeOverviewInput(saved(note()), course.id, setOf("missing")) }.isFailure)
        assertTrue(runCatching { knowledgeOverviewInput(saved(note()), "deleted") }.isFailure)
    }

    @Test fun modelCannotInventNavigableSourceLinksIncludingAutolinksAndDefinitions() {
        val passage = knowledgePassages(note()).last()
        val forged = passage.url.replace("/0/", "/1/") + "9"
        val result = verifiedKnowledgeOutput("可靠 ${passage.link}\n[错]($forged)\n<$forged>\n[x]: $forged\n[外链](https://example.com)", listOf(passage))
        assertTrue(result.contains(passage.link))
        assertFalse(result.contains(forged))
        assertTrue(result.contains("来源未核实"))
        assertTrue(result.contains("https://example.com"))
    }

    @Test fun courseChatUsesLatestRelatedPassagesAndStaysWithinChosenCourse() {
        val original = note(body = "## 第 44 页\n" + "常规内容".repeat(2000) + "\n## 第 45 页\n特定知识：PR 间期的定义。").copy(title = "心电图")
        val other = note("other", "PR 间期其他课程的私人记录").copy(folderId = "english")
        val overview = original.copy(id = "old-overview", content = "PR 间期旧总览，不能取代当前原笔记", overviewSources = mapOf(original.id to "old"))
        val s = saved(original, other, overview).copy(conversations = listOf(Conversation(notebookId = course.id)))
        val result = chatNotebookContext(s, "PR 间期是什么？")
        assertEquals(listOf(original.id), result.references.map { it.noteId })
        assertTrue(result.passages.any { it.text.contains("特定知识") })
        assertTrue(result.text.contains("生理学"))
        assertFalse(result.text.contains(other.content))
        assertTrue(runCatching { chatNotebookContext(s, "继续", other.id) }.isFailure)
        assertFalse(result.text.contains(overview.content))
        val chosenOverview = chatNotebookContext(s, "继续", overview.id)
        val followUp = chatNotebookContext(s, "继续", history = listOf(Message("assistant", "已选择总览", noteRefs = chosenOverview.references)))
        assertTrue(followUp.references.any { it.noteId == overview.id })
        assertTrue(followUp.text.contains("1 份来源已有变化"))
        assertTrue(result.text.length <= CHAT_NOTE_CONTEXT_LIMIT)
        assertTrue(runCatching { chatNotebookContext(s.copy(noteFolders = emptyList()), "PR 间期") }.isFailure)
    }

    @Test fun oldConversationScopeIsNullAndNewScopeAndOverviewVersionsRoundTrip() {
        val original = note()
        val overview = note("overview").copy(overviewSources = mapOf(original.id to knowledgeNoteVersion(original)))
        val s = saved(original, overview).copy(conversations = listOf(Conversation(notebookId = chapter.id)))
        val json = s.toJson()
        assertEquals(s.chat(), savedFromJson(json).chat())
        assertEquals(overview, savedFromJson(json).notes.last())
        json.getJSONArray("conversations").getJSONObject(0).remove("notebookId")
        assertNull(savedFromJson(json).chat().notebookId)
        assertEquals(knowledgeNoteVersion(original), knowledgeNoteVersion(original.copy(folderId = "moved", pinned = true)))
        assertNotEquals(knowledgeNoteVersion(original), knowledgeNoteVersion(original.copy(content = "改过正文")))
    }

    @Test fun backupConflictRemapsOverviewLinksAndPreservesOriginalLocalNote() {
        val original = note()
        val passage = knowledgePassages(original).last()
        val overview = note("overview", passage.link).copy(overviewSources = mapOf(original.id to knowledgeNoteVersion(original)))
        val bytes = ByteArrayOutputStream().also { writeNotebookBackup(it, saved(original, overview), { error("unexpected image") }, { null }) }.toByteArray()
        val backup = readNotebookBackup(ByteArrayInputStream(bytes))
        val local = original.copy(content = "本机修改不能覆盖")
        val restored = mergeNotebookBackup(saved(local), backup.data)
        assertEquals(local, restored.notes.first { it.id == local.id })
        val imported = restored.notes.first { it.overviewSources.isNotEmpty() }
        val importedId = imported.overviewSources.keys.single()
        assertNotEquals(original.id, importedId)
        assertEquals(importedId, imported.overviewSources.keys.single())
        val link = Regex("\\((study-note:[^)]*)\\)").find(imported.content)!!.groupValues[1]
        assertEquals(importedId, knowledgeLocation(link)!!.noteId)
        assertNotNull(knowledgeSourceText(restored, knowledgeLocation(link)!!))
        assertEquals(restored, mergeNotebookBackup(restored, backup.data))
    }
}
