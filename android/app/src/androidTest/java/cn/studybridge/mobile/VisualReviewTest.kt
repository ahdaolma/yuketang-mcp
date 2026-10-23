package cn.studybridge.mobile

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import io.noties.markwon.ext.tables.TableRowSpan

// Synthetic display data only: no real account, lesson entry, or model request.
// Render the same frame, theme and screens shipped in release, rather than mock screenshots.
class VisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private lateinit var vm: StudyViewModel
    private val courseTitle = "医学遗传学：遗传信息的传递与疾病机制"
    private val firstNote = Note("ui-note-1", "DNA 的变性、复性与熔解温度", "# 先掌握这三个概念\n\nDNA 变性是双链间氢键等相互作用被破坏，双链解开为单链的过程。\n\n## 对照理解\n\n| 概念 | 主要变化 |\n| --- | --- |\n| 变性 | 双链解开 |\n| 复性 | 互补单链重新配对 |\n\n## 容易混淆\n\n**变性通常不涉及磷酸二酯键断裂。**\n\n- 熔解温度与序列和溶液条件有关。\n- AI 补充应与课件原文区分。", "生物化学 · 第 12–16 页", "ChatGPT 网页", 1792418400000)
    private fun sample() = UiState(ready = true, tab = 1,
        data = Saved(learnerProfile = "大一，生物基础较弱；希望先解释术语，再分步骤讲机制，配例题和自测。", session = "visual-fixture-only", username = "学习者", sessionVerified = true, notes = listOf(firstNote,
            Note("ui-note-2", "细胞膜的物质转运与静息电位", "## 主动转运\n\n区分能量来源、方向与载体，理解浓度梯度和电化学梯度。", "生理学 · 第 4–8 页", "ChatGPT 网页"),
            Note("ui-note-3", "孟德尔遗传规律：从家系图到风险判断", "## 先观察家系\n\n结合代际分布、性别比例与亲子关系，逐步判断遗传方式。", "医学遗传学 · 第 20–23 页", "ChatGPT 网页"))),
        courses = listOf(Course("1", courseTitle, "秋季学期 · 本科课程"), Course("2", "生物化学", "秋季学期 · 本科课程"), Course("3", "生理学", "秋季学期 · 本科课程")))
    private fun render(state: MutableState<UiState>, dark: MutableState<Boolean> = mutableStateOf(false), scale: Float = 1f, width: Int? = null) {
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                StudyTheme(dark.value) {
                    Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width.dp).fillMaxHeight()) {
                    ClassManagerHost(state.value, vm) {
                    StudyFrame(state.value, onTab = { state.value = state.value.copy(tab = it) }, onBack = { if (state.value.tab == 2) vm.noteReader.closeRequested = true else state.value = state.value.copy(tab = 2) }, onStop = {}, noteOpen = state.value.tab == 2 && state.value.data.notes.any { it.id == vm.noteReader.id }) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                            when (state.value.tab) { 1 -> RainScreen(state.value, vm); 2 -> NotesScreen(state.value, vm); else -> ModelScreen(state.value, vm) }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // Shared media survives AGP uninstalling the debug app at the end of the test task.
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        bitmap.recycle()
    }
    private fun verifyTableRows() {
        compose.openNoteTool("目录跳转")
        compose.onNodeWithText("2. 对照理解").performClick()
        // A plain TextView.text assignment bypasses Markwon's table invalidation hooks.
        // Check the final Android layout, not just that table words exist in a string.
        compose.onRoot().captureToImage()
        fun textViews(view: android.view.View): List<android.widget.TextView> = when (view) {
            is android.widget.TextView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
            else -> emptyList()
        }
        compose.waitUntil(timeoutMillis = 5000) {
            var laidOut = false
            compose.runOnIdle {
                laidOut = textViews(compose.activity.window.decorView).any { view ->
                    val text = view.text as? android.text.Spanned
                    val layout = view.layout
                    if (text == null || layout == null) false else {
                        val rows = text.getSpans(0, text.length, TableRowSpan::class.java)
                        val metrics = view.paint.fontMetricsInt
                        val minimum = metrics.descent - metrics.ascent + 2 * (8 * view.resources.displayMetrics.density).toInt() - 2
                        rows.size == 3 && rows.all { row ->
                            val line = layout.getLineForOffset(text.getSpanStart(row))
                            row.cellWidth() > 0 && layout.getLineBottom(line) - layout.getLineTop(line) >= minimum
                        }
                    }
                }
            }
            laidOut
        }
    }
    private fun assertReadingArea() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
        val page = compose.onNodeWithTag("笔记阅读页").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
        assertTrue("Body should occupy at least 80% of the note reader: $body / $page", (body.bottom - body.top).value >= (page.bottom - page.top).value * 0.80f)
        compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
        compose.onNodeWithTag("应用底部导航").assertDoesNotExist()
        compose.onNodeWithTag("按屏翻页栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("目录跳转").assertDoesNotExist()
        compose.onNodeWithContentDescription("阅读跳转").assertDoesNotExist()
        compose.onNodeWithTag("阅读操作面板").assertDoesNotExist()
        compose.onNodeWithText("编辑笔记").assertDoesNotExist()
        compose.onNodeWithText("导出 Markdown").assertDoesNotExist()
    }
    @Test fun narrowLargeTextReaderKeepsBodySpaceAndMenuActionsReachable() {
        val state = mutableStateOf(sample().copy(tab = 2)); render(state, scale = 1.5f, width = 320)
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(firstNote.title)); compose.onNodeWithText(firstNote.title).performClick()
        assertReadingArea()
        compose.onNodeWithTag("笔记原始标题").assertIsDisplayed()
        compose.openNoteTool("下一段")
        assertReadingArea()
        compose.onNodeWithTag("笔记吸顶标题").assertIsDisplayed()
        shot("sample-note-reading-compact")
        compose.ensureNoteTools()
        compose.onNodeWithText("编辑笔记").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("导出 Markdown").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("AI 讲解并保存新笔记").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("来源与模型").performScrollTo().performClick()
        compose.onNodeWithText("关闭").performClick()
        assertReadingArea()
    }
    @Test fun populatedScreensAndDraftSurviveTabChanges() {
        val state = mutableStateOf(sample()); render(state)
        compose.onNodeWithText(courseTitle).assertIsDisplayed(); shot("sample-courses-light")
        val initialCourseBounds = compose.onNodeWithTag("课程列表").getUnclippedBoundsInRoot()
        val initialCourseHeight = initialCourseBounds.bottom - initialCourseBounds.top
        compose.onNodeWithText("笔记").performClick()
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(firstNote.title)); compose.onNodeWithText(firstNote.title).assertIsDisplayed(); shot("sample-notes-light")
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(firstNote.title)); compose.onNodeWithText(firstNote.title).performClick()
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed(); verifyTableRows(); shot("sample-note-reading")
        compose.openNoteTool("编辑笔记")
        compose.onNode(hasSetTextAction()).performTextInput("\n新增草稿内容")
        compose.runOnIdle { state.value = state.value.copy(tab = 1) }
        compose.waitUntil(5000) {
            val bounds = compose.onNodeWithTag("课程列表").getUnclippedBoundsInRoot()
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true &&
                bounds.bottom - bounds.top >= initialCourseHeight - 2.dp
        }
        // Restore a virtualized item through its scrolling container before
        // checking the actual title, including text merged into a course card.
        compose.onNodeWithTag("课程列表").performScrollToNode(hasText(courseTitle))
        compose.onNodeWithText(courseTitle, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(tab = 2) }
        compose.onNodeWithText("保存修改").assertIsDisplayed()
        compose.runOnIdle { assertTrue(vm.noteReader.draft.contains("新增草稿内容")); assertTrue(vm.noteReader.editing) }
        compose.onNodeWithContentDescription("返回笔记列表").performClick()
        compose.onNodeWithText("保存这次修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("保存修改").assertIsDisplayed()
    }
    @Test fun largeTextClassActionsAndSettingsRemainReachable() {
        val base = sample()
        val run = ClassRun(LiveLesson("7", courseTitle, "1"), providerId = "fixture", completed = listOf("1:1", "1:2"), pending = listOf(LivePage("1", 3)))
        val state = mutableStateOf(base.copy(tab = 1, data = base.data.copy(classRun = run), classUi = ClassUi(running = true, paused = true, status = "已暂停，完成的讲解已保存，回到学习页后可继续。")))
        render(state, scale = 1.5f)
        compose.onNodeWithTag("课程列表").performScrollToNode(hasTestTag("上课控制面板"))
        compose.onNodeWithTag("上课控制面板").assertIsDisplayed()
        compose.onAllNodesWithText("继续", useUnmergedTree = true).onLast().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("结束", useUnmergedTree = true).onLast().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("上课控制面板").onLast().captureToImage()
        advancedUiShot(compose.activity, "sample-class-large-text")
        compose.runOnIdle { state.value = state.value.copy(classUi = ClassUi(running = true, paused = false, status = "正在重新连接课堂…")) }
        compose.onAllNodesWithText("连接中").onLast().performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = base.copy(tab = 3) }
        compose.onNodeWithText("模型与账号").assertIsDisplayed(); shot("sample-settings-large-text")
        compose.runOnIdle { state.value = state.value.copy(settingsPage = "learning") }
        compose.onNodeWithText("我的学习情况").performScrollTo().performClick()
        compose.onNodeWithText("当前水平与学习需求").performScrollTo().assertIsDisplayed()
        shot("sample-learner-settings")
        compose.onNodeWithText("学习助手提示词").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = base.copy(tab = 2) }
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(firstNote.title)); compose.onNodeWithText(firstNote.title).performClick()
        assertReadingArea()
        shot("sample-note-reading-large-text")
        compose.ensureNoteTools()
        compose.onNodeWithText("导出 Markdown").performScrollTo().assertIsDisplayed()
    }
    @Test fun darkScreensUseTheSameReadingAndSettingsHierarchy() {
        val state = mutableStateOf(sample().copy(tab = 2)); render(state, mutableStateOf(true))
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(firstNote.title)); compose.onNodeWithText(firstNote.title).assertIsDisplayed(); shot("sample-notes-dark")
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(firstNote.title)); compose.onNodeWithText(firstNote.title).performClick(); verifyTableRows(); shot("sample-note-reading-dark")
        compose.runOnIdle { state.value = state.value.copy(tab = 3) }
        compose.onNodeWithText("模型与账号").assertIsDisplayed(); compose.onNodeWithText("笔记生成").assertIsDisplayed(); shot("sample-settings-dark")
        compose.runOnIdle { state.value = state.value.copy(settingsPage = "learning") }
        compose.onNodeWithText("我的学习情况").performScrollTo().performClick()
        compose.onNodeWithText("当前水平与学习需求").performScrollTo().assertIsDisplayed(); shot("sample-learner-dark")
        compose.onNodeWithText("学习助手提示词").performScrollTo().assertIsDisplayed()
    }
}
