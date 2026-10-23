package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotebookLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: StudyViewModel
    private val busy = mutableStateOf(false)
    private val first = Note("first", "复制主线", "当前正文必须保留。", "fixture", "手写")
    private val other = Note("other", "酶机制", "关联笔记的正文。", "fixture", "手写")

    @After fun clear() { compose.runOnIdle { store.clear() } }

    private fun render(saved: Saved) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(saved)
        compose.runOnIdle { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val current by vm.ui.collectAsState()
            val ui = current.copy(busy = current.busy || busy.value)
            var page by remember { mutableStateOf("") }
            var tag by remember { mutableStateOf<String?>(null) }
            var review by remember { mutableStateOf<String?>(null) }
            StudyTheme(false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                        NotebookTools(ui, vm, null, tag, review) { nextTag, nextReview -> tag = nextTag; review = nextReview }
                        FeedbackTextButton(onClick = { page = "details" }) { Text("编辑属性") }
                        FeedbackTextButton(onClick = { page = "history" }) { Text("查看历史") }
                        FeedbackTextButton(onClick = { page = "related" }) { Text("查看关联") }
                        libraryNotes(ui.data, "", null, tag, review).forEach { note ->
                            Text(note.title, Modifier.testTag("库笔记 ${note.id}"))
                            NotebookStatus(note)
                        }
                        if (page == "storage") NotebookStorage(ui, vm)
                        FeedbackTextButton(onClick = { page = "storage" }) { Text("文件设置") }
                    }
                    val note = ui.data.notes.find { it.id == first.id }
                    if (note != null) when (page) {
                        "details" -> NotebookNoteDetails(note, ui, vm) { page = "" }
                        "history" -> NotebookHistory(note, ui, vm) { page = "" }
                        "related" -> NotebookRelated(note, ui, { vm.notice("打开关联：${it.title}") }, { page = "" })
                    }
                }
            }
        }
        compose.waitUntil(5000) { vm.ui.value.ready }
    }

    private fun shot(tag: String, name: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage()
        advancedUiShot(compose.activity, name)
    }

    private fun clickSettledChoice(node: SemanticsNodeInteraction) {
        node.performScrollTo()
        // IME insets run on the platform clock, independently of Compose's
        // test clock. A tap at pre-resize coordinates can hit another row.
        var previous = node.getUnclippedBoundsInRoot()
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(5000) {
            val bounds = node.getUnclippedBoundsInRoot()
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != previous) { previous = bounds; stableSince = now }
            now - stableSince >= 200
        }
        node.assertIsDisplayed().performClick()
    }

    @Test fun metadataFiltersRelatedNotesAndTemplateDraftWorkWithoutChangingExistingBody() {
        render(Saved(notes = listOf(first, other)))
        compose.onNodeWithText("编辑属性").performClick()
        compose.onNodeWithText("标签，用逗号分隔").performTextReplacement("生物,考试")
        clickSettledChoice(compose.onNodeWithText("已掌握"))
        compose.onNodeWithText("已掌握").assertIsSelected()
        clickSettledChoice(compose.onNodeWithTag("关联选项 other"))
        compose.onNodeWithTag("关联选项 other").assertIsOn()
        shot("笔记详情面板", "sample-notebook-details")
        compose.onNodeWithText("保存标签与复习").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val note = vm.ui.value.data.notes.single { it.id == first.id }
            assertEquals(first.content, note.content)
            assertEquals(listOf("生物", "考试"), note.tags)
            assertEquals("mastered", note.reviewState)
            assertEquals(listOf(other.id), note.relatedNoteIds)
        }
        compose.onNodeWithText("查看关联").performClick()
        compose.onNodeWithTag("笔记关联面板").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("笔记工具").performClick()
        compose.onNodeWithText("筛选与排序").performClick()
        compose.onNodeWithText("标题").performClick()
        compose.onNodeWithText("已掌握").performScrollTo().performClick()
        compose.onNodeWithText("#生物").performScrollTo().performClick()
        shot("笔记筛选面板", "sample-notebook-tools")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("库笔记 first").assertExists()
        compose.onNodeWithTag("库笔记 other").assertDoesNotExist()
        compose.runOnIdle { assertEquals("title", vm.ui.value.data.noteSort) }
        compose.onNodeWithContentDescription("笔记工具").performClick()
        compose.onNodeWithText("从模板新建").performClick()
        compose.onNodeWithText("课堂重点").performClick()
        compose.runOnIdle {
            assertNotEquals(first.id, vm.noteReader.id)
            assertTrue(vm.noteReader.editing)
            assertEquals(NOTE_TEMPLATES.single { it.name == "课堂重点" }.content, vm.noteReader.draft)
            assertEquals(first.content, vm.ui.value.data.notes.single { it.id == first.id }.content)
            assertEquals("课堂重点", vm.ui.value.data.notes.single { it.id == vm.noteReader.id }.title)
        }
    }

    @Test fun historyCreatesACopyAndTrashRequiresConfirmationBeforePermanentRemoval() {
        val deleted = first.copy(id = "deleted", title = "误删的原页笔记", content = "删除前完整正文。")
        val history = NoteRevision("older", first.copy(content = "之前的正文。"), 100)
        render(Saved(notes = listOf(first, other), noteHistory = listOf(history, NoteRevision("deleted-old", deleted)), noteTrash = listOf(TrashedNote(deleted))))
        compose.runOnIdle { vm.noteReader.open(first); vm.noteReader.editing = true; vm.noteReader.draft = "未保存草稿保留。" }
        compose.onNodeWithText("查看历史").performClick()
        shot("笔记历史面板", "sample-notebook-history")
        compose.onNodeWithTag("恢复历史 older").performClick()
        compose.runOnIdle {
            assertEquals(first, vm.ui.value.data.notes.single { it.id == first.id })
            assertEquals(history.note.content, vm.ui.value.data.notes.first().content)
            assertEquals("未保存草稿保留。", vm.noteReader.draft)
            assertEquals(2, vm.ui.value.data.noteHistory.size)
        }
        compose.onNodeWithContentDescription("笔记工具").performClick()
        compose.onNodeWithText("回收站").performClick()
        shot("笔记回收站面板", "sample-notebook-trash")
        compose.onNodeWithTag("永久删除 deleted").performClick()
        compose.onNodeWithText("保留记录").performClick()
        compose.runOnIdle { assertEquals(deleted, vm.ui.value.data.noteTrash.single().note) }
        compose.onNodeWithTag("恢复回收 deleted").performClick()
        compose.runOnIdle { assertEquals(deleted, vm.ui.value.data.notes.single { it.id == deleted.id }); assertTrue(vm.ui.value.data.noteTrash.isEmpty()) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertTrue(vm.deleteNote(deleted.id)) }
        compose.onNodeWithContentDescription("笔记工具").performClick()
        compose.onNodeWithText("回收站").performClick()
        compose.onNodeWithText("清空回收站…").performScrollTo().performClick()
        compose.onNodeWithText("确认永久删除").performClick()
        compose.runOnIdle {
            assertTrue(vm.ui.value.data.noteTrash.isEmpty())
            assertTrue(vm.ui.value.data.noteHistory.none { it.note.id == deleted.id })
            assertEquals(first, vm.ui.value.data.notes.single { it.id == first.id })
            assertEquals("未保存草稿保留。", vm.noteReader.draft)
        }
        compose.onNodeWithText("回收站为空。").assertIsDisplayed()
    }

    @Test fun busyMetadataCannotSaveAndStorageExplainsNotebookOnlyBackup() {
        render(Saved(notes = listOf(first)))
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("编辑属性").performClick()
        compose.onNodeWithText("保存标签与复习").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("文件设置").performClick()
        compose.onNodeWithText("导出笔记备份").assertIsNotEnabled()
        compose.onNodeWithText("从备份恢复").assertIsNotEnabled()
        compose.onNodeWithText("不会包含雨课堂登录凭据或模型密钥", substring = true).assertExists()
        compose.runOnIdle { assertEquals(first, vm.ui.value.data.notes.single()) }
    }
}
