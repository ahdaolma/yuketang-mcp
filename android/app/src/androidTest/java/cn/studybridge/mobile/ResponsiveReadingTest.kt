package cn.studybridge.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ResponsiveReadingTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private lateinit var vm: StudyViewModel
    private val note = Note("responsive-note", "DNA 的结构与复制", "# DNA 的结构与复制\n\n" + "复制以原有 DNA 链为模板，遵循碱基互补配对。\n\n".repeat(100), "合成课件 · 第 1 页", "fixture")
    private val original = "用户选择的笔记原文：\n# DNA 的结构与复制\n\n" + "原文必须完整保存，展开后仍可阅读。\n\n".repeat(120)
    private fun sample() = UiState(ready = true, data = Saved(notes = listOf(note), currentChat = "responsive", conversations = listOf(Conversation(id = "responsive", title = "合成阅读测试", messages = listOf(
        Message("user", original, source = "测试课件 · 第 1–10 页"),
        Message("assistant", "# DNA 的结构与复制\n\n### 理解主线\n\n" + "双链互补配对是理解复制的起点。先理解模板、碱基配对和方向，再串联各步骤。\n\n".repeat(100))
    )))))
    private fun render(state: MutableState<UiState>, width: Int? = null, scale: Float = 1f, dark: Boolean = false) {
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) { StudyTheme(dark) {
                Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width.dp).fillMaxHeight()) {
                    StudyFrame(state.value, { state.value = state.value.copy(tab = it) }, {}, {}, noteOpen = state.value.tab == 2 && vm.noteReader.id != null) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                            StudyOperationProgress(state.value)
                            when (state.value.tab) { 0 -> ChatScreen(state.value, vm); 2 -> NotesScreen(state.value, vm); else -> Text("课程导航已到达") }
                        }
                    }
                }
            } }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("学习正文").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun shot(name: String, system: Boolean = false) {
        val image = if (system) requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) else compose.onRoot().captureToImage().asAndroidBitmap()
        try { saveUiReviewBitmap(compose.activity, name, image) } finally { image.recycle() }
    }
    private fun assertSettledStatusBarHidden(name: String) {
        compose.waitForIdle()
        var policy = ""
        try {
            compose.waitUntil(6000) {
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == false
                else {
                    // Check the actual system window, not just the app's
                    // requested fullscreen flag or inferred compat insets.
                    val descriptor = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys window policy")
                    policy = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                    Regex("BarController\\.StatusBar\\s+mState=(\\w+)").find(policy)?.groupValues?.get(1) == "WINDOW_STATE_HIDDEN"
                }
            }
        } finally {
            if (policy.isNotBlank()) {
                val directory = java.io.File(compose.activity.getExternalFilesDir(null), "StudyUiReview").apply { check(isDirectory || mkdirs()) }
                java.io.File(directory, "$name-window-policy.txt").writeText(policy)
            }
            shot(name, system = true)
        }
    }
    private fun assertNotebookToolSheet() {
        compose.ensureNoteTools()
        val panel = compose.onNodeWithTag("阅读操作面板").getUnclippedBoundsInRoot()
        val maximum = minOf(480f, compose.activity.resources.configuration.screenHeightDp * .64f)
        assertTrue("Notebook tools must fit the bounded sheet", (panel.bottom - panel.top).value <= maximum + 2f)
        val first = compose.onNodeWithContentDescription("返回笔记列表").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithContentDescription("目录跳转").getUnclippedBoundsInRoot()
        assertTrue("Notebook actions must be a vertical list", second.top >= first.bottom)
        assertEquals(first.left.value, second.left.value, 1f)
        listOf("返回笔记列表", "目录跳转", "笔记内查找", "阅读设置", "设置").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        assertSettledStatusBarHidden("sample-note-tools-system-hidden")
        compose.onNodeWithContentDescription("阅读设置").performScrollTo().performClick()
        compose.onNodeWithContentDescription("关闭阅读方式与字号").assertIsDisplayed()
        val originalMode = vm.ui.value.data.noteReadMode
        val alternateMode = if (originalMode == "swipe") "buttons" else "swipe"
        compose.onNodeWithText(NOTE_READING_MODES.getValue(alternateMode)).performScrollTo().performClick()
        assertSettledStatusBarHidden("sample-note-preferences-mode-change-hidden")
        compose.onNodeWithText(NOTE_READING_MODES.getValue(originalMode)).performScrollTo().performClick()
        assertSettledStatusBarHidden("sample-note-preferences-system-hidden")
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.ensureNoteTools()
    }
    @Test fun scrollNarrowsBarsWithoutRemovingActionsAndReverseRestoresThem() {
        val state = mutableStateOf(sample()); render(state)
        val before = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        shot("sample-chat-reading")
        compose.onNodeWithTag("学习正文").performTouchInput { swipeUp(durationMillis = 500) }
        compose.waitForIdle()
        val compact = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue("Reading gestures must reclaim height", (compact.bottom - compact.top).value > (before.bottom - before.top).value + 60f)
        assertTrue("Floating controls must not narrow the text column", compact.right - compact.left >= before.right - before.left)
        compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
        compose.onNodeWithTag("应用时间栏").assertIsDisplayed()
        assertSettledStatusBarHidden("sample-chat-status-hidden")
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().performClick()
        compose.onNodeWithTag("应用底部导航").assertIsDisplayed()
        listOf("学习资料", "新对话", "对话笔记", "消息跳转", "设置", "课程").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertIsEnabled() }
        shot("sample-chat-reading-narrow-bars", system = true)
        compose.onNodeWithContentDescription("消息跳转").performClick()
        compose.onNodeWithText("跳转消息").performClick()
        compose.onNodeWithText("跳转到消息").assertIsDisplayed()
        compose.onNodeWithText("取消跳转").performClick()
        compose.onNodeWithContentDescription("收起悬浮工具").performClick()
        compose.onNodeWithTag("学习正文").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        val restored = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue((restored.bottom - restored.top).value < (compact.bottom - compact.top).value - 60f)
        compose.onNodeWithTag("学习正文").performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithContentDescription("课程").performTouchInput { click() }
        compose.onNodeWithText("课程导航已到达").assertIsDisplayed()
        compose.runOnIdle { vm.noteReader.open(note.copy(content = note.content.repeat(8))); state.value = state.value.copy(tab = 2, data = state.value.data.copy(notes = listOf(note.copy(content = note.content.repeat(8))))) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("目录跳转").assertDoesNotExist()
        compose.onNodeWithTag("应用底部导航").assertDoesNotExist()
        compose.onNodeWithTag("按屏翻页栏").assertDoesNotExist()
        compose.onNodeWithTag("笔记正文").performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed()
        val sourcePage = compose.onNodeWithContentDescription("当前阅读页码").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].substringAfter("阅读分段 ").substringBefore(" / ").toInt()
        assertNotebookToolSheet()
        shot("sample-note-floating-controls", system = true)
        compose.openNoteTool("下一段")
        compose.onNodeWithTag("阅读操作面板").assertDoesNotExist()
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed()
        compose.onNodeWithContentDescription("当前阅读页码").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "阅读分段 ${sourcePage + 1} / ${noteReadingSegments(note.content.repeat(8)).size} · 完整笔记已保留")
        )
    }
    @Test fun narrowLargeTextKeepsToolsReachableAndReferenceCanExpandWithoutChangingStoredText() {
        val state = mutableStateOf(sample()); render(state, width = 320, scale = 1.5f, dark = true)
        listOf("学习资料", "新对话", "对话笔记", "消息跳转").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("消息跳转").performClick()
        compose.onNodeWithText("顶部").performClick()
        compose.onNodeWithText("展开原文").assertIsDisplayed()
        shot("sample-chat-reference-large-text")
        compose.onNodeWithText("展开原文").performClick()
        compose.onNodeWithText("收起原文").assertExists()
        compose.runOnIdle { assertEquals(original, state.value.data.chat().messages.first().text) }
    }
    @Test fun shortWindowKeepsComposerCompactAfterImeTargetClosesAndGenerationLocksInput() {
        val draft = "解释复制：保留模板方向与配对关系"
        val state = mutableStateOf(sample().copy(chatDrafts = mapOf("responsive" to draft)))
        val height = mutableStateOf(112.dp)
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        compose.setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val density = LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalConfiguration provides Configuration(configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE },
                LocalDensity provides Density(density.density, 1f),
                LocalInlineReadingHeader provides true
            ) {
                StudyTheme(false) { Box(Modifier.fillMaxWidth().height(height.value)) { ChatScreen(state.value, vm) } }
            }
        }
        // A closing IME can report hidden while the window still measures only
        // 112dp. Reproduce that state directly without waiting for more space.
        compose.runOnIdle {
            androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
        compose.onNode(hasSetTextAction()).assertChatDraftEquals(draft)
        compose.onNodeWithText("提问 ·", substring = true).assertDoesNotExist()
        val shortBody = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue("A 112dp window must retain more than one reading line after IME target closes", (shortBody.bottom - shortBody.top).value > 48f)
        compose.runOnIdle { state.value = state.value.copy(busy = true, operation = "合成生成测试") }
        compose.onNodeWithText("提问 ·", substring = true).assertDoesNotExist()
        val busyBody = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue("Disabling the composer must retain the short-window reading viewport", (busyBody.bottom - busyBody.top).value > 48f)
        shot("sample-chat-short-window-ime-closed")
        compose.runOnIdle { state.value = state.value.copy(busy = false, operation = ""); height.value = 260.dp }
        compose.onNodeWithContentDescription("消息跳转").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("提问 ·", substring = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertChatDraftEquals(draft)
        val restored = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue("Actual window restoration must reclaim reading space", (restored.bottom - restored.top).value > 100f)
    }
    @Test fun actualLandscapeHasOneToolbarSideNavigationAndUsableBody() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            val state = mutableStateOf(sample()); render(state)
            compose.onNodeWithTag("横屏导航").assertIsDisplayed()
            compose.onNodeWithTag("应用底部导航").assertDoesNotExist()
            compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
            val body = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
            val screen = compose.onRoot().getUnclippedBoundsInRoot()
            assertTrue("Landscape body must keep most height", (body.bottom - body.top).value >= (screen.bottom - screen.top).value * 0.55f)
            listOf("学习", "课程", "笔记", "学习资料", "新对话", "对话笔记", "消息跳转", "设置").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertIsEnabled() }
            shot("sample-chat-landscape")
            compose.runOnIdle { state.value = state.value.copy(pending = Attachment(null, "DNA 结构课件 · 第 1 页", "合成附件"), data = state.value.data.copy(providers = listOf(Provider(chatgptAccountId = "fixture-only")))) }
            compose.onNode(hasSetTextAction()).performTextInput("解释复制")
            compose.waitUntil(5000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
            shot("sample-chat-landscape-typing", system = true)
            compose.onNodeWithContentDescription("发送给所选模型").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().performClick()
            listOf("学习", "课程", "笔记", "学习资料", "新对话", "对话笔记", "消息跳转", "设置").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertIsEnabled() }
            shot("sample-chat-floating-tools", system = true)
            compose.onNodeWithContentDescription("收起悬浮工具").performClick()
            val typingBody = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
            assertTrue("Landscape composer must leave a usable scroll viewport", (typingBody.bottom - typingBody.top).value > 48f)
            compose.onNodeWithContentDescription("附件与用量").performClick()
            listOf("DNA 结构课件 · 第 1 页", "移除课件附件", "管理 ChatGPT 用量").forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText("DNA 结构课件 · 第 1 页").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).assertChatDraftEquals("解释复制")
            compose.runOnIdle { state.value = state.value.copy(busy = true, operation = "合成生成测试") }
            compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
            if (compose.onAllNodesWithContentDescription("停止生成").fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription("悬浮阅读工具").performClick()
            compose.onNodeWithContentDescription("停止生成").assertIsDisplayed().assertIsEnabled()
            val busyBody = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
            android.util.Log.i("ReadingRegression", "landscape generation body=$busyBody ime=" + androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
            shot("sample-chat-landscape-busy", system = true)
            assertTrue("Generation must not reintroduce a stacked landscape header", (busyBody.bottom - busyBody.top).value > 48f)
            if (compose.onAllNodesWithContentDescription("收起悬浮工具").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("收起悬浮工具").performClick()
            compose.runOnIdle { state.value = state.value.copy(busy = false, operation = "") }
            val longDraft = "解释复制：" + "模板方向、配对关系和具体步骤。".repeat(50)
            // Re-enter the composer as a user would after generation unlocked it.
            compose.onNode(hasSetTextAction()).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement(longDraft)
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("悬浮阅读工具").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertChatDraftEquals(longDraft)
            compose.onNodeWithContentDescription("发送给所选模型").assertIsDisplayed().assertIsEnabled()
            val longDraftBody = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
            assertTrue("Long drafts must not consume the landscape viewport", (longDraftBody.bottom - longDraftBody.top).value > 48f)
            compose.runOnIdle {
                androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            // IME visibility is its target state, and can become false before
            // the native closing animation has restored Compose's constraints.
            compose.waitUntil(5000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false &&
                    compose.onAllNodesWithContentDescription("消息跳转").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("悬浮阅读工具").fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("消息跳转").assertIsDisplayed().assertIsEnabled()
            compose.onNode(hasSetTextAction()).assertChatDraftEquals(longDraft)
            val restoredBody = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
            assertTrue("Closing the keyboard must restore the reading viewport", (restoredBody.bottom - restoredBody.top).value >= (screen.bottom - screen.top).value * 0.5f)
            assertSettledStatusBarHidden("sample-chat-landscape-restored")
            compose.onNodeWithContentDescription("消息跳转").performClick()
            compose.onNodeWithText("顶部").performClick()
            compose.onNodeWithText("展开原文").assertIsDisplayed()
            compose.onNodeWithContentDescription("课程").performTouchInput { click() }
            compose.onNodeWithText("课程导航已到达").assertIsDisplayed()
            compose.runOnIdle { vm.noteReader.open(note); state.value = state.value.copy(tab = 2) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            val noteBody = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            assertTrue((noteBody.bottom - noteBody.top).value >= (screen.bottom - screen.top).value * 0.65f)
            compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
            compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed()
            compose.onNodeWithTag("应用底部导航").assertDoesNotExist()
            compose.onNodeWithTag("按屏翻页栏").assertDoesNotExist()
            shot("sample-note-landscape")
            assertNotebookToolSheet()
            compose.ensureNoteTools()
            compose.onNodeWithText("编辑笔记").performScrollTo().assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }
}
