package cn.studybridge.mobile

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
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
    private fun sample() = UiState(ready = true, data = Saved(notes = listOf(note), learningMode = "native", currentChat = "responsive", conversations = listOf(Conversation(id = "responsive", title = "合成阅读测试", messages = listOf(
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
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                            when (state.value.tab) { 0 -> ChatScreen(state.value, vm); 2 -> NotesScreen(state.value, vm); else -> Text("课程导航已到达") }
                        }
                    }
                }
            } }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("学习正文").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun shot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        image.recycle()
    }
    @Test fun scrollNarrowsBarsWithoutRemovingActionsAndReverseRestoresThem() {
        val state = mutableStateOf(sample()); render(state)
        val before = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        shot("sample-chat-reading")
        compose.onNodeWithTag("学习正文").performTouchInput { swipeUp(durationMillis = 500) }
        compose.waitForIdle()
        val compact = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue("Reading gestures must reclaim height", (compact.bottom - compact.top).value > (before.bottom - before.top).value + 20f)
        compose.onNodeWithTag("应用顶部栏").assertIsDisplayed()
        compose.onNodeWithTag("应用底部导航").assertIsDisplayed()
        listOf("学习资料", "新对话", "生成笔记", "消息跳转", "设置", "课程").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertIsEnabled() }
        shot("sample-chat-reading-narrow-bars")
        compose.onNodeWithContentDescription("消息跳转").performClick()
        compose.onNodeWithText("跳转消息").performClick()
        compose.onNodeWithText("跳转到消息").assertIsDisplayed()
        compose.onNodeWithText("取消跳转").performClick()
        compose.onNodeWithTag("学习正文").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        val restored = compose.onNodeWithTag("学习正文").getUnclippedBoundsInRoot()
        assertTrue((restored.bottom - restored.top).value < (compact.bottom - compact.top).value - 20f)
        compose.onNodeWithTag("学习正文").performTouchInput { swipeUp(durationMillis = 500) }
        compose.onNodeWithContentDescription("课程").performTouchInput { click() }
        compose.onNodeWithText("课程导航已到达").assertIsDisplayed()
    }
    @Test fun narrowLargeTextKeepsToolsReachableAndReferenceCanExpandWithoutChangingStoredText() {
        val state = mutableStateOf(sample()); render(state, width = 320, scale = 1.5f, dark = true)
        listOf("学习资料", "新对话", "生成笔记", "消息跳转").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("消息跳转").performClick()
        compose.onNodeWithText("顶部").performClick()
        compose.onNodeWithText("展开原文").assertIsDisplayed()
        shot("sample-chat-reference-large-text")
        compose.onNodeWithText("展开原文").performClick()
        compose.onNodeWithText("收起原文").assertExists()
        compose.runOnIdle { assertEquals(original, state.value.data.chat().messages.first().text) }
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
            listOf("学习", "课程", "笔记", "学习资料", "新对话", "生成笔记", "消息跳转", "设置").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertIsEnabled() }
            shot("sample-chat-landscape")
            compose.onNodeWithContentDescription("消息跳转").performClick()
            compose.onNodeWithText("顶部").performClick()
            compose.onNodeWithText("展开原文").assertIsDisplayed()
            compose.onNodeWithContentDescription("课程").performTouchInput { click() }
            compose.onNodeWithText("课程导航已到达").assertIsDisplayed()
            compose.runOnIdle { vm.noteReader.open(note); state.value = state.value.copy(tab = 2) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            val noteBody = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            assertTrue((noteBody.bottom - noteBody.top).value >= (screen.bottom - screen.top).value * 0.65f)
            listOf("返回笔记列表", "目录跳转", "阅读跳转", "笔记选项", "设置").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
            shot("sample-note-landscape")
            compose.onNodeWithContentDescription("笔记选项").performClick()
            compose.onNodeWithText("编辑笔记").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }
}
