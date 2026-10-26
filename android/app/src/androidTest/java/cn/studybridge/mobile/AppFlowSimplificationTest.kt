package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real production screens with synthetic local storage and intercepted HTTP. */
class AppFlowSimplificationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val provider = Provider("flow-model", "流程测试服务", "https://fixture.example/v1", "fixture-key", "old-model", true)

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun globalFeedbackKeepsBottomActionsReachableAndRestoresSpaceAfterDismissal() {
        val feedback = SnackbarHostState()
        var scenario by mutableStateOf(3 to false)
        var show by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            StudyTheme(false) {
                LaunchedEffect(show) {
                    if (show) feedback.showSnackbar("操作结果仍然可见", withDismissAction = true, duration = SnackbarDuration.Indefinite)
                }
                StudyFrame(UiState(ready = true, tab = scenario.first), onTab = {}, onBack = {}, onStop = {}, snackbar = feedback, noteOpen = scenario.second) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        FeedbackButton(onClick = { clicks++ }, modifier = Modifier.align(Alignment.BottomCenter).testTag("测试底部操作")) { Text("继续操作") }
                    }
                }
            }
        }
        // Settings, bottom navigation, and the reader without a bottom bar
        // share the same invariant, including a floating reader tool target.
        for (mode in listOf(3 to false, 1 to false, 2 to true)) {
            compose.runOnIdle { scenario = mode }
            val action = compose.onNodeWithTag("测试底部操作")
            fun bottomSnapshot(): Pair<Float, Float> {
                val root = compose.onRoot().fetchSemanticsNode()
                val button = action.fetchSemanticsNode()
                var result = 0f to 0f
                compose.runOnIdle { result = root.boundsInRoot.bottom to button.boundsInRoot.bottom }
                return result
            }
            val (originalRootBottom, originalBottom) = bottomSnapshot()
            compose.runOnIdle { show = true }
            val notice = compose.onNodeWithTag("操作反馈")
            compose.waitUntil(5000) { notice.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value > 0f } }
            notice.assertIsDisplayed()
            assertTrue("Bottom action must remain above feedback in $mode", action.getUnclippedBoundsInRoot().bottom <= notice.getUnclippedBoundsInRoot().top)
            val previousClicks = clicks
            action.performTouchInput { click() }
            compose.runOnIdle { assertEquals(previousClicks + 1, clicks); feedback.currentSnackbarData!!.dismiss() }
            compose.waitUntil(5000) { notice.getUnclippedBoundsInRoot().let { (it.bottom - it.top).value == 0f } }
            val (restoredRootBottom, restoredBottom) = bottomSnapshot()
            android.util.Log.i("FeedbackLayout", "mode=$mode beforeRoot=$originalRootBottom beforeAction=$originalBottom afterRoot=$restoredRootBottom afterAction=$restoredBottom")
            // API26 can finish hiding its system bar after the first snapshot.
            // Validate the full space of the CURRENT root, not an old absolute Y.
            assertEquals("Dismissal must restore the entire body in $mode", originalRootBottom - originalBottom, restoredRootBottom - restoredBottom, .5f)
            compose.runOnIdle { show = false }
        }
    }

    @Test fun readerToolsExposeActualEditSearchAndRemarksWithoutAnotherActionMenu() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note("flow-reader", "直接操作测试", "复制起点需要核对。", "合成笔记", "手写")
        Vault(app).save(Saved(notes = listOf(note)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2); vm.noteReader.open(note) }
            compose.setContent { StudyTheme(false) { StudyApp(vm) } }
            compose.ensureNoteTools()
            compose.onAllNodesWithTag("阅读操作面板").assertCountEquals(1)
            compose.onNodeWithText("笔记选项").assertDoesNotExist()
            compose.onNodeWithText("阅读跳转").assertDoesNotExist()
            for (action in listOf("笔记内查找", "编辑笔记", "我的标记与备注", "导出 PDF", "整本末尾")) {
                compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("关闭阅读工具").assertIsDisplayed()
            }
            compose.openNoteTool("编辑笔记")
            compose.onNodeWithTag("阅读操作面板").assertDoesNotExist()
            compose.onNodeWithText("Markdown 笔记").performTextReplacement("复制起点已核对，保留用户修改。")
            compose.onNodeWithText("保存修改").performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single().content.contains("保留用户修改") }
            compose.openNoteTool("笔记内查找")
            compose.onNodeWithTag("阅读操作面板").assertDoesNotExist()
            compose.onNodeWithText("查找完整正文").performTextInput("保留用户修改")
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭").performClick()
            compose.openNoteTool("我的标记与备注")
            compose.onNodeWithText("长按正文，选中文字后添加自己的标记或备注。").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("笔记阅读页").assertIsDisplayed()
            compose.waitUntil(5000) { Vault(app).load().notes.single().content == "复制起点已核对，保留用户修改。" }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun preopenedContinuousReaderRestoresAcrossScaffoldRemeasuresAndReentry() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note("flow-restore-layout", "连续阅读布局恢复", "## 第 1 页\n\nFIRST_RESTORE。\n\n" +
            (1..18).joinToString("\n\n") { "第一页段落$it。" + "布局恢复必须保留实际阅读位置。".repeat(3) } +
            "\n\n## 第 2 页\n\nSECOND_RESTORE。\n\n" +
            (1..18).joinToString("\n\n") { "第二页段落$it。" + "重新进入后仍能继续阅读。".repeat(3) }, "合成笔记", "手写")
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = "continuous"))
        Vault(app, "note-reading-v1.enc").save(Saved(noteReadingPositions = listOf(
            NoteReadingPosition(note.id, 1, 0, "第 2 页", noteContentHash(note.content)))))
        val width = mutableStateOf(1f)
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun restoredLineVisible(): Boolean {
            val body = compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().firstOrNull()?.boundsInWindow ?: return false
            var visible = false
            compose.runOnIdle {
                visible = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>().any { view ->
                    val offset = view.text.indexOf("SECOND_RESTORE")
                    val layout = view.layout
                    if (offset < 0 || layout == null || !view.isShown) false else {
                        val location = IntArray(2); view.getLocationInWindow(location)
                        val line = layout.getLineForOffset(offset)
                        val top = location[1] + view.totalPaddingTop - view.scrollY + layout.getLineTop(line)
                        val bottom = location[1] + view.totalPaddingTop - view.scrollY + layout.getLineBottom(line)
                        top >= body.top && bottom <= body.bottom
                    }
                }
            }
            return visible
        }
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2); vm.noteReader.open(note) }
            // StudyApp's real Scaffold subcomposes during measurement. Starting
            // with an open reader covers the failing initial layout path too.
            compose.setContent { StudyTheme(false) { Box(Modifier.fillMaxWidth(width.value).fillMaxHeight()) { StudyApp(vm) } } }
            repeat(6) { cycle ->
                compose.waitUntil(8000) { restoredLineVisible() }
                compose.runOnIdle {
                    width.value = if (cycle % 2 == 0) .83f else 1f
                    vm.updateNotebookState { it.copy(noteFontSize = if (cycle % 2 == 0) 21 else 18) }
                }
                compose.waitUntil(8000) { restoredLineVisible() }
                compose.runOnIdle { vm.closeNoteReader() }
                compose.onNodeWithTag("笔记列表").assertIsDisplayed()
                compose.runOnIdle { vm.noteReader.open(note) }
            }
            compose.waitUntil(8000) { restoredLineVisible() }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun courseSystemBackRestoresDeckLessonAndCourseParentsWithoutReloading() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), selectedProvider = provider.id, session = "synthetic-session", sessionVerified = true))
        val requests = AtomicInteger()
        val rain = RainApi(OkHttpClient.Builder().addInterceptor { chain ->
            // Only course/activity catalogs define parent navigation reloads.
            // DeckPreview legitimately resolves lesson-info to fetch its cover
            // again when the lesson card returns, independently of navigation.
            if (chain.request().url.encodedPath in setOf("/v2/api/web/courses/list", "/v2/api/web/logs/learn/1")) requests.incrementAndGet()
            val data = when (chain.request().url.encodedPath) {
                "/v2/api/web/courses/list" -> """{"list":[{"classroom_id":1,"course":{"name":"系统返回课程"},"teacher":{"name":"测试老师"}}]}"""
                "/v2/api/web/logs/learn/1" -> """{"activities":[{"type":14,"courseware_id":10,"title":"系统返回课堂"}]}"""
                "/api/v3/classroom-report/student/lesson-info" -> """{"lessonName":"系统返回课堂","presentationIds":[101]}"""
                "/api/v3/classroom-report/student/ppt" -> """{"title":"空课件","slideList":[]}"""
                else -> error("Unexpected synthetic course request ${chain.request().url.encodedPath}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"code\":0,\"data\":$data}".toResponseBody()).build()
        }.build())
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, rain) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(1); vm.loadCourses() }
            compose.setContent { StudyTheme(false) { StudyApp(vm) } }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.courses.isNotEmpty() }
            compose.onNodeWithText("系统返回课程").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentations.isNotEmpty() }
            val activities = vm.ui.value.presentations
            compose.onNodeWithText("系统返回课堂").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.lesson != null }
            val decks = vm.ui.value.presentations
            compose.onNodeWithText("系统返回课堂 · 课件 1").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentationId == "101" }
            val reads = requests.get()
            back()
            compose.runOnIdle {
                assertFalse(vm.ui.value.busy); assertNotNull(vm.ui.value.course); assertNotNull(vm.ui.value.lesson)
                assertEquals("", vm.ui.value.presentationId); assertEquals(decks, vm.ui.value.presentations)
                assertEquals(reads, requests.get())
            }
            compose.onNodeWithText("系统返回课堂 · 课件 1").performScrollTo().assertIsDisplayed()
            back()
            compose.runOnIdle {
                assertFalse(vm.ui.value.busy); assertNotNull(vm.ui.value.course); assertNull(vm.ui.value.lesson)
                assertEquals(activities, vm.ui.value.presentations); assertEquals(reads, requests.get())
            }
            compose.onNodeWithText("系统返回课堂").performScrollTo().assertIsDisplayed()
            back()
            compose.runOnIdle {
                assertFalse(vm.ui.value.busy); assertNull(vm.ui.value.course); assertNull(vm.ui.value.lesson)
                assertEquals(1, vm.ui.value.tab); assertEquals(reads, requests.get())
            }
            compose.onNodeWithText("系统返回课程").performScrollTo().assertIsDisplayed()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun delayedModelCatalogCannotReopenAfterLeavingItsPageAndCanStillBeChosenAndSaved() = delayedCatalog("page")
    @Test fun delayedModelCatalogCannotReopenAfterLeavingItsTab() = delayedCatalog("tab")
    @Test fun cancelledModelPopupIntentKeepsCompletedCatalogAvailable() = delayedCatalog("cancel")

    private fun saveFailureScreenshot(name: String) {
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val resolver = compose.activity.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } finally { bitmap.recycle() }
    }

    private fun delayedCatalog(exit: String) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), selectedProvider = provider.id))
        val requested = CountDownLatch(1); val release = CountDownLatch(1)
        val ai = AiApi(OkHttpClient.Builder().addInterceptor { chain ->
            assertTrue(chain.request().url.encodedPath.endsWith("/models"))
            requested.countDown(); check(release.await(30, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"id":"new-model"},{"id":"old-model"}]}""".toResponseBody()).build()
        }.build())
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = ai) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(3); vm.settingsPage("models") }
            compose.setContent { StudyTheme(false) { StudyApp(vm) } }
            compose.onNodeWithText("API Base URL").assertDoesNotExist()
            compose.onNodeWithText("API Key").assertDoesNotExist()
            compose.onNodeWithText("old-model").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存并使用").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("读取可用模型").performScrollTo().performClick()
            compose.waitUntil(5000) { requested.count == 0L && vm.ui.value.busy }
            when (exit) {
                "page" -> {
                    compose.onNodeWithContentDescription("返回设置").performClick()
                    compose.onNodeWithText("模型与账号").performClick()
                }
                "tab" -> {
                    compose.onNodeWithContentDescription("返回设置").performClick()
                    compose.onNodeWithContentDescription("返回学习").performClick()
                    compose.runOnIdle { assertEquals(0, vm.ui.value.tab) }
                    compose.onNodeWithContentDescription("设置").performClick()
                    compose.onNodeWithText("模型与账号").performClick()
                }
                else -> compose.onNodeWithText("取消自动打开模型列表").performScrollTo().performClick()
            }
            compose.runOnIdle {
                assertTrue("Navigation must finish while the catalog request is still pending", vm.ui.value.busy)
                release.countDown()
            }
            compose.waitUntil(5000) { !vm.ui.value.busy }
            assertTrue("Catalog completion must retain actual models: ${vm.ui.value.notice}", vm.ui.value.modelList.isNotEmpty())
            compose.onNodeWithText("选择可用模型").assertDoesNotExist()
            compose.onNodeWithText("API Base URL").assertDoesNotExist()
            compose.onNodeWithText("old-model").performScrollTo().performClick()
            compose.onNodeWithText("new-model").performClick()
            // Choosing only changes the form; saving remains an explicit action.
            assertEquals("old-model", vm.ui.value.data.provider().model)
            // Model selection clears focus and hides the IME. The window can
            // still resize after Compose is idle; wait for actual geometry.
            compose.waitUntil(5000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
            }
            compose.onNodeWithText("所选模型支持图片输入").performScrollTo().assertIsDisplayed()
            val save = compose.onNodeWithText("保存并使用")
            save.performScrollTo().assertIsDisplayed().assertIsEnabled()
            var previousBounds = save.getUnclippedBoundsInRoot()
            compose.waitUntil(5000) {
                val bounds = save.getUnclippedBoundsInRoot()
                val stable = bounds == previousBounds
                previousBounds = bounds
                stable
            }
            try {
                val feedback = compose.onNodeWithTag("操作反馈")
                // A slow device may already have timed out the catalog notice.
                // Whenever it is still present, assert physical separation;
                // the indefinite-notice test above always covers this case.
                val saveBounds = save.getUnclippedBoundsInRoot()
                val feedbackBounds = feedback.getUnclippedBoundsInRoot()
                if ((feedbackBounds.bottom - feedbackBounds.top).value > 0f) {
                    feedback.assertIsDisplayed()
                    assertTrue("Save $saveBounds must be above feedback $feedbackBounds", saveBounds.bottom <= feedbackBounds.top)
                }
                if (exit == "page") advancedUiShot(compose.activity, "sample-model-save-feedback")
                // Use an actual hit-tested tap, then verify both state and disk.
                save.performTouchInput { click() }
                compose.waitUntil(5000) { vm.ui.value.data.provider().model == "new-model" }
                assertEquals("模型配置已保存。", vm.ui.value.notice)
                compose.waitUntil(5000) { Vault(app).load().provider().model == "new-model" }
                assertEquals("new-model", Vault(app).load().provider().model)
            } catch (failure: Throwable) {
                val trees = runCatching {
                    val roots = compose.onAllNodes(isRoot())
                    roots.fetchSemanticsNodes().indices.joinToString("\n") { index -> roots[index].printToString(maxDepth = 16) }
                }.getOrElse { "Semantics unavailable: $it" }
                runCatching { saveFailureScreenshot("model-save-$exit-failure") }.onFailure { failure.addSuppressed(it) }
                throw AssertionError("Model save failed ($exit): model=${vm.ui.value.data.provider().model}; busy=${vm.ui.value.busy}; notice=${vm.ui.value.notice}; semantics=$trees", failure)
            }
        } finally { release.countDown(); compose.runOnIdle { store.clear() } }
    }
}
