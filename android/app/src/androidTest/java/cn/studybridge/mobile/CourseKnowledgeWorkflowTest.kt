package cn.studybridge.mobile

import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

    @Test fun libraryActionsAndPersistentCourseScopeAreUsableWithLargeText() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), notes = listOf(original), noteFolders = listOf(folder, chapter)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store) }
            compose.setContent {
                val ui by vm.ui.collectAsState()
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { StudyTheme(false) {
                    KnowledgeSourceHost(ui, vm) {
                        StudyFrame(ui, vm::tab, {}, vm::stop) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)) { StudyPageContent(ui, vm) }
                        }
                    }
                } }
            }
            compose.waitUntil(10000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithTag("当前笔记本").performScrollTo().performClick()
            compose.onNodeWithContentDescription("展开笔记本 生理学").performClick()
            compose.onNodeWithTag("笔记本选择列表").performScrollToNode(hasTestTag("选择分类 chapter"))
            compose.onNodeWithTag("选择分类 chapter").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记本面板").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("整理章节总览").performScrollTo().assertIsDisplayed()
            shot("course-knowledge-actions-large-font")
            compose.onNodeWithText("围绕本章提问").performScrollTo().performClick()
            compose.onNodeWithText("课程范围 · 生理学 / 血液循环").assertIsDisplayed()
            compose.onNodeWithText("围绕本章直接提问").assertIsDisplayed()
            compose.onNodeWithText("选择学习资料").assertDoesNotExist()
            assertEquals(chapter.id, vm.ui.value.data.chat().notebookId)
            shot("course-knowledge-chat-scope")
            compose.onNodeWithContentDescription("退出课程范围").performClick()
            assertNull(vm.ui.value.data.chat().notebookId)
            assertTrue(vm.ui.value.notice.contains("原对话保留"))
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun overviewSavesSeparatelyAndScopedChatReadsTheNextSavedEditAndRemark() {
        assertTrue(chatNotebookTerms("心电图与机械收缩").contains("心电"))
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
            assertEquals(vm.ui.value.notice, 2, bodies.size)
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
            compose.runOnIdle {
                vm.startCourseChat(folder.id); vm.attachNote(other)
                assertFalse(vm.send("讲解这个课程范围外的笔记"))
                vm.notes()
            }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(0, calls)
            assertEquals(listOf(original, other), vm.ui.value.data.notes)
            compose.runOnIdle { vm.clearAttachment(); assertTrue(vm.deleteNoteFolder(folder.id)); assertFalse(vm.send("总结所有笔记")) }
            assertEquals(0, calls)
            assertTrue(vm.ui.value.notice.contains("范围已删除"))
            compose.runOnIdle { vm.leaveCourseChatScope() }
            assertNull(vm.ui.value.data.chat().notebookId)
            assertTrue(vm.ui.value.data.chat().messages.isEmpty())
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun stoppingOverviewLeavesOriginalAndDoesNotSavePartialText() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), notes = listOf(original), noteFolders = listOf(folder, chapter)))
        val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = fixtureAiApi("不能保存的中断结果", beforeResponse = { arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)) })) }
            compose.setContent { Text("停止整理回归") }; compose.waitUntil(10000) { vm.ui.value.ready }
            compose.runOnIdle { vm.organizeKnowledge(chapter.id) }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { vm.stop() }; release.countDown()
            compose.waitUntil(15000) { !vm.ui.value.busy }
            assertEquals(listOf(original), vm.ui.value.data.notes)
        } finally { release.countDown(); compose.runOnIdle { store.clear() } }
    }

    @Test fun nativeCitationOpensExactSavedPassageAndStaleSourceHasFixedCloseEntry() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(original), noteFolders = listOf(folder, chapter)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        val passage = knowledgePassages(original).last()
        var showLoadedData by mutableStateOf(true)
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store) }
            compose.setContent {
                val ui by vm.ui.collectAsState()
                val visible = if (showLoadedData) ui else ui.copy(ready = false, data = ui.data.copy(notes = emptyList()))
                StudyTheme { KnowledgeSourceHost(visible, vm) { Markdown("结论来源：${passage.link}") } }
            }
            compose.waitUntil(10000) { vm.ui.value.ready }
            fun views(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) + (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
            compose.waitUntil(10000) { views(compose.activity.window.decorView).any { view -> (view.text as? android.text.Spanned)?.getSpans(0, view.text.length, URLSpan::class.java)?.any { it.url == passage.url } == true } }
            compose.runOnIdle {
                val view = views(compose.activity.window.decorView).first { v -> (v.text as? android.text.Spanned)?.getSpans(0, v.text.length, URLSpan::class.java)?.any { it.url == passage.url } == true }
                val span = (view.text as android.text.Spanned).getSpans(0, view.text.length, URLSpan::class.java).first { it.url == passage.url }
                val offset = (view.text as android.text.Spanned).getSpanStart(span) + 1
                val layout = requireNotNull(view.layout)
                val line = layout.getLineForOffset(offset)
                val x = layout.getPrimaryHorizontal(offset) + view.totalPaddingLeft - view.scrollX
                val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + view.totalPaddingTop - view.scrollY
                val now = android.os.SystemClock.uptimeMillis()
                for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to now, android.view.MotionEvent.ACTION_UP to now + 60)) {
                    val event = android.view.MotionEvent.obtain(now, time, action, x, y, 0)
                    try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            compose.waitUntil(5000) { runCatching { compose.onNodeWithContentDescription("关闭核对笔记来源").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("核对笔记来源").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭核对笔记来源").assertIsDisplayed()
            if (android.os.Build.VERSION.SDK_INT >= 28) compose.onNodeWithTag("阅读操作面板").captureToImage()
            else {
                // Compose's dialog PixelCopy helper requires API 28. Observe
                // stable on-screen geometry before the real system screenshot.
                var previous = compose.onNodeWithTag("阅读操作面板").fetchSemanticsNode().boundsInRoot
                var stableSince = android.os.SystemClock.uptimeMillis()
                compose.waitUntil(5000) {
                    val bounds = compose.onNodeWithTag("阅读操作面板").fetchSemanticsNode().boundsInRoot
                    if (bounds != previous) { previous = bounds; stableSince = android.os.SystemClock.uptimeMillis() }
                    android.os.SystemClock.uptimeMillis() - stableSince >= 200
                }
            }
            shot("course-knowledge-source")
            compose.runOnIdle { showLoadedData = false }
            compose.onNodeWithText("来源当前不可用").assertDoesNotExist()
            compose.runOnIdle { showLoadedData = true }
            compose.waitUntil(5000) { runCatching { compose.onNodeWithContentDescription("关闭核对笔记来源").assertIsDisplayed() }.isSuccess }
            compose.runOnIdle { vm.updateNotebookState { it.copy(notes = listOf(original.copy(content = "已修改后的正文"))) } }
            compose.onNodeWithText("源笔记正文已修改。", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭核对笔记来源").assertIsDisplayed().performClick()
            compose.onNodeWithText("核对笔记来源").assertDoesNotExist()
        } finally { compose.runOnIdle { store.clear() } }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        // Native screenshot capture is independent of the Compose test clock.
        // Wait for actual display frames after the sheet's draw has completed.
        val frames = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.postOnAnimation { view.postOnAnimation { frames.countDown() } }
        }
        assertTrue(frames.await(5, TimeUnit.SECONDS))
        advancedUiShot(compose.activity, name)
    }
}
