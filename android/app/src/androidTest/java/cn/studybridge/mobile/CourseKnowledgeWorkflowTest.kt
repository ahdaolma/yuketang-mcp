package cn.studybridge.mobile

import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import okio.Buffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Intercepted providers and synthetic notes only; no real classroom claims. */
class CourseKnowledgeWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val folder = NoteFolder("course", "生理学")
    private val chapter = NoteFolder("chapter", "血液循环", folder.id)
    private val original = Note("original", "心电图", "## 第 44 页\n\n### 电活动\n\n心电图记录电活动，不是心脏收缩曲线。", "已保存课堂笔记", "fixture", folderId = chapter.id)
    private val other = original.copy(id = "private-other", folderId = "english", content = "其他课程绝不可带入的私人内容")
    private val provider = Provider("knowledge", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model")

    @Test fun overviewSavesSeparatelyAndScopedChatReadsTheNextSavedEditAndRemark() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val passage = knowledgePassages(original).last()
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        Vault(app).save(Saved(providers = listOf(provider), notes = listOf(original, other), noteFolders = listOf(folder, chapter)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = fixtureAiApi("电活动的知识结构。${passage.link}", inspect = { request -> val b = Buffer(); request.body!!.writeTo(b); bodies += b.readUtf8() })) }
            compose.setContent { Text("课程知识请求回归") }
            compose.waitUntil(10000) { vm.ui.value.ready }
            compose.runOnIdle { vm.organizeKnowledge(chapter.id) }
            compose.waitUntil(15000) { !vm.ui.value.busy }
            assertEquals(1, bodies.size)
            val overview = vm.ui.value.data.notes.single { it.overviewSources.isNotEmpty() }
            assertEquals(chapter.id, overview.folderId)
            assertEquals(original, vm.ui.value.data.notes.single { it.id == original.id })
            assertTrue(overview.content.contains(passage.link))
            assertTrue(overview.content.contains("不代表原课件原文"))
            assertFalse(bodies.single().contains(other.content))
            compose.runOnIdle {
                assertTrue(vm.editNote(original.id, original.content + "\n人工新结论：不要混同机械收缩。"))
                val content = vm.ui.value.data.notes.first { it.id == original.id }.content
                assertTrue(vm.saveNoteAnnotation(original.id, NoteAnnotation(sourceStart = 0, sourceLength = content.length, sourceHash = noteContentHash(content), start = 0, end = 2, quote = "心电", kind = "remark", comment = "已核对本课程的个人备注")))
                vm.startCourseChat(folder.id)
                assertTrue(vm.send("心电图和机械收缩有什么区别？"))
            }
            compose.waitUntil(15000) { !vm.ui.value.busy }
            assertEquals(2, bodies.size)
            assertTrue(bodies.last().contains("人工新结论"))
            assertTrue(bodies.last().contains("已核对本课程的个人备注"))
            assertTrue(bodies.last().contains("持续课程范围：生理学"))
            assertFalse(bodies.last().contains(other.content))
            val scoped = vm.ui.value.data.chat().id
            compose.runOnIdle { vm.leaveCourseChatScope() }
            assertNotEquals(scoped, vm.ui.value.data.chat().id)
            assertNull(vm.ui.value.data.chat().notebookId)
            assertTrue(vm.ui.value.data.chat().messages.isEmpty())
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun changingOrDeletingSourceDuringRequestCannotPublishAnOutdatedOverview() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), notes = listOf(original), noteFolders = listOf(folder, chapter)))
        val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = fixtureAiApi("已返回的旧版整理", beforeResponse = { arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)) })) }
            compose.setContent { Text("来源并发更新回归") }; compose.waitUntil(10000) { vm.ui.value.ready }
            compose.runOnIdle { vm.organizeKnowledge(chapter.id) }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { vm.updateNotebookState { it.copy(notes = listOf(original.copy(content = "生成过程中保存的新正文"))) } }
            release.countDown()
            compose.waitUntil(15000) { !vm.ui.value.busy }
            assertEquals("生成过程中保存的新正文", vm.ui.value.data.notes.single().content)
            assertTrue(vm.ui.value.notice.contains("来源已变化"))
            assertTrue(vm.ui.value.data.notes.none { it.overviewSources.isNotEmpty() })
        } finally { release.countDown(); compose.runOnIdle { store.clear() } }
    }

    @Test fun deletedCourseCannotSilentlySearchOtherCoursesAndExitStartsClean() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), notes = listOf(original, other), noteFolders = listOf(folder, chapter)))
        var calls = 0; val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = fixtureAiApi("不应请求", inspect = { calls++ })) }
            compose.setContent { Text("课程范围删除回归") }; compose.waitUntil(10000) { vm.ui.value.ready }
            compose.runOnIdle { vm.startCourseChat(folder.id); assertTrue(vm.deleteNoteFolder(folder.id)); assertFalse(vm.send("总结所有笔记")) }
            assertEquals(0, calls)
            assertTrue(vm.ui.value.notice.contains("范围已删除"))
            compose.runOnIdle { vm.leaveCourseChatScope() }
            assertNull(vm.ui.value.data.chat().notebookId)
            assertTrue(vm.ui.value.data.chat().messages.isEmpty())
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun nativeCitationOpensExactSavedPassageAndStaleSourceHasFixedCloseEntry() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(original), noteFolders = listOf(folder, chapter)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        val passage = knowledgePassages(original).last()
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store) }
            compose.setContent {
                val ui by vm.ui.collectAsState()
                StudyTheme { KnowledgeSourceHost(ui, vm) { Markdown("结论来源：${passage.link}") } }
            }
            compose.waitUntil(10000) { vm.ui.value.ready }
            fun views(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) + (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
            compose.waitUntil(10000) { views(compose.activity.window.decorView).any { view -> (view.text as? android.text.Spanned)?.getSpans(0, view.text.length, URLSpan::class.java)?.any { it.url == passage.url } == true } }
            compose.runOnIdle {
                val view = views(compose.activity.window.decorView).first { v -> (v.text as? android.text.Spanned)?.getSpans(0, v.text.length, URLSpan::class.java)?.any { it.url == passage.url } == true }
                val span = (view.text as android.text.Spanned).getSpans(0, view.text.length, URLSpan::class.java).first { it.url == passage.url }
                span.onClick(view)
            }
            compose.onNodeWithText("核对笔记来源").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭核对笔记来源").assertIsDisplayed()
            advancedUiShot(compose.activity, "course-knowledge-source.png")
            compose.runOnIdle { vm.updateNotebookState { it.copy(notes = listOf(original.copy(content = "已修改后的正文"))) } }
            compose.onNodeWithText("源笔记正文已修改。", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭核对笔记来源").assertIsDisplayed().performClick()
            compose.onNodeWithText("核对笔记来源").assertDoesNotExist()
        } finally { compose.runOnIdle { store.clear() } }
    }
}
