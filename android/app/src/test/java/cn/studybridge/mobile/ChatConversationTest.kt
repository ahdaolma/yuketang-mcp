package cn.studybridge.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatConversationTest {
    @Test fun onlyDefaultTitlesUseTheFirstMeaningfulUserMessage() {
        val messages = listOf(Message("assistant", "欢迎"), Message("user", "  "), Message("user", "\n 酶的  活性中心 \t机制"))
        assertEquals("酶的 活性中心 机制", Conversation(messages = messages).withAutomaticTitle().title)
        assertEquals("新对话", Conversation().withAutomaticTitle().title)
        assertEquals("新对话", Conversation(messages = listOf(Message("assistant", "欢迎"))).withAutomaticTitle().title)
        val named = Conversation(title = "保留老师给的名字", messages = emptyList())
        assertEquals(named, named.withAutomaticTitle())
        assertEquals(named.title, named.copy(messages = messages).withAutomaticTitle().title)
    }

    @Test fun attachmentSourceNamesImageOnlyExplanationAndLegacyRecordsWithoutChangingMessages() {
        val explained = Conversation(messages = listOf(Message("user", "", "image", "细胞生物学 · 第 7 页"), Message("assistant", "解释")))
        val text = Conversation(messages = listOf(Message("user", "为什么DNA只能沿一个方向合成？")))
        val custom = explained.copy(id = "custom", title = "自定义标题")
        val original = Saved(conversations = listOf(explained, text, custom), currentChat = custom.id)
        val repaired = original.withAutomaticChatTitles()
        assertEquals("细胞生物学 · 第 7 页", repaired.conversations[0].title)
        assertEquals(text.messages.first().text, repaired.conversations[1].title)
        assertEquals(custom, repaired.conversations[2])
        assertEquals(original.conversations.map { it.messages }, repaired.conversations.map { it.messages })
        assertEquals(original.currentChat, repaired.currentChat)
        assertEquals(repaired, repaired.withAutomaticChatTitles())
    }

    @Test fun fullSelectionKeepsEveryAvailablePageWhileCustomRangeKeepsItsLimit() {
        val pages = (1..51).map { Slide(it * 2, "", "原文") }.reversed()
        val selected = allNotePages(pages + pages.first())
        assertEquals((1..51).map { it * 2 }, selected.map { it.page })
        assertTrue(runCatching { selectNotePages("2", "102", pages) }.isFailure)
        assertEquals(listOf(2, 4), allNotePages(selectNotePages("2", "5", pages)).map { it.page })
        assertNotNull(batchNoteValidation(Saved(), emptyList()))
        val p = Provider(key = "key", baseUrl = "https://fixture.example/v1", model = "model")
        val ready = Saved(providers = listOf(p), selectedProvider = p.id)
        assertNull(batchNoteValidation(ready, selected))
        assertNotNull(batchNoteValidation(ready, listOf(Slide(1, "image", ""))))
    }

    @Test fun fiftyOnePageNotebookCheckpointsEachPageAndRetainsEarlierPagesOnFailure() = runBlocking {
        val pages = allNotePages((1..51).map { Slide(it * 2, "", "原文") })
        val checkpoints = mutableListOf<NotebookProgress>()
        buildNotebook("整份课件", pages, { Attachment(null, "第 ${it.page} 页", it.text) }, { "讲解" }, { checkpoints += it })
        assertEquals((1..51).toList(), checkpoints.map { it.completed })
        assertTrue(checkpoints.last().markdown.contains("## 第 102 页"))
        assertTrue(checkpoints.last().markdown.contains("已完成 51/51"))
        checkpoints.clear()
        var calls = 0
        val result = runCatching { buildNotebook("整份课件", pages, { Attachment(null, "第 ${it.page} 页", it.text) }, { if (++calls == 51) error("第 51 页中断"); "讲解" }, { checkpoints += it }) }
        assertTrue(result.isFailure)
        assertEquals(50, checkpoints.size)
        assertTrue(checkpoints.last().markdown.contains("已完成 50/51"))
        assertFalse(checkpoints.last().markdown.contains("## 第 102 页"))
    }
}
