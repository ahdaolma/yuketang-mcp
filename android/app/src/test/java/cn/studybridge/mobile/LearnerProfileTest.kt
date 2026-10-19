package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class LearnerProfileTest {
    private val profile = "大一，生物基础薄弱；希望先讲术语，再用例题理解机制。"
    @Test fun oldRecordsLoadWithoutChangingOtherData() {
        val old = Saved(session = "fixture-session", username = "fixture", notes = listOf(Note(title = "原笔记", content = "原内容", source = "第 1 页", model = "fixture")))
        val json = old.toJson().apply { remove("learnerProfile") }
        assertEquals(old, savedFromJson(json))
    }
    @Test fun profileRoundTripAndClearPreserveExistingNotesAndCredentials() {
        val saved = Saved(learnerProfile = profile, session = "fixture-session", notes = listOf(Note(title = "原笔记", content = "内容", source = "课件", model = "fixture")), tutorPrompt = "自设角色")
        assertEquals(saved, savedFromJson(saved.toJson()))
        assertEquals(saved.copy(learnerProfile = ""), savedFromJson(saved.copy(learnerProfile = "").toJson()))
    }
    @Test fun nativeChatAndNotesReceiveLevelWithoutChangingSourceOrHistory() {
        val input = listOf(Message("user", "DNA复性", "data:image/png;base64,fixture", "生物化学 · 第 8 页"))
        val chat = tutorMessages("自设助教", input, "应用状态", profile)
        val notes = noteMessages("自设笔记", input, "生物化学 · 第 8 页", profile)
        for (messages in listOf(chat, notes)) {
            assertEquals("system", messages.first().role)
            assertTrue(messages.first().text.contains(profile))
            assertTrue(messages.first().text.contains("不固定贴能力标签"))
            assertEquals(input.single(), messages[2])
        }
        assertTrue(chat.first().text.startsWith("自设助教"))
        assertTrue(notes.first().text.startsWith("自设笔记"))
        assertTrue(notes.last().text.contains("第 8 页"))
    }
    @Test fun emptyProfilePreservesPromptsAndOversizeProfileIsBounded() {
        assertEquals("原提示", withLearnerProfile("原提示", " \n "))
        assertEquals(tutorMessages("角色", emptyList()), tutorMessages("角色", emptyList(), learnerProfile = ""))
        val text = withLearnerProfile("角色", "a".repeat(LEARNER_PROFILE_LIMIT) + "EXCESS")
        assertFalse(text.contains("EXCESS"))
        assertEquals(LEARNER_PROFILE_LIMIT, savedFromJson(Saved().toJson().put("learnerProfile", "a".repeat(3000))).learnerProfile.length)
    }
    @Test fun webTransferIncludesRoleProfileAndAccurateApplicationLimits() {
        val prompt = learnerWebPrompt("", profile)
        assertTrue(prompt.contains(profile)); assertTrue(prompt.contains(TUTOR_PROMPT)); assertTrue(prompt.contains(APP_CONTEXT))
        assertTrue(prompt.contains("本次对话")); assertTrue(prompt.contains("不是课件事实"))
    }
}
