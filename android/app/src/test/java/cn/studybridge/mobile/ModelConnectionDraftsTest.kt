package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ModelConnectionDraftsTest {
    @Test fun separateProviderDraftsRetainEditsUntilDiscardedAndNeverModifySavedProvider() {
        val first = Provider("first", "原配置", "https://first.example/v1", "saved-key", "saved-model")
        val second = first.copy(id = "second", name = "第二配置")
        val drafts = ModelConnectionDrafts()
        drafts.observeSelection(first.id)
        val edit = drafts.draft(first)
        edit.key.value = "unsaved-key"; edit.model.value = "unsaved-model"
        drafts.observeSelection(second.id)
        assertEquals("saved-key", drafts.draft(second).key.value)
        drafts.observeSelection(first.id)
        assertSame(edit, drafts.draft(first))
        assertEquals("unsaved-key", drafts.draft(first).key.value)
        assertEquals("saved-key", first.key)
        drafts.discard(first.id)
        assertEquals("saved-key", drafts.draft(first).key.value)
    }

    @Test fun newDraftCanResumeAfterSelectingExistingConfigurationAndCancelRemovesIt() {
        val drafts = ModelConnectionDrafts()
        drafts.observeSelection("existing")
        drafts.beginNew()
        val first = requireNotNull(drafts.adding.value)
        drafts.draft(first).name.value = "未完成配置"
        drafts.adding.value = null
        drafts.beginNew()
        assertEquals(first.id, drafts.adding.value?.id)
        assertEquals("未完成配置", drafts.draft(first).name.value)
        drafts.cancelNew()
        drafts.beginNew()
        assertNotEquals(first.id, drafts.adding.value?.id)
        assertEquals("自定义模型", drafts.draft(requireNotNull(drafts.adding.value)).name.value)
    }
}
