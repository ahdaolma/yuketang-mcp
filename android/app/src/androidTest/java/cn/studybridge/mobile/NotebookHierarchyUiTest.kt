package cn.studybridge.mobile

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotebookHierarchyUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val folders = listOf(NoteFolder("bio", "遗传学"), NoteFolder("dna", "DNA 结构", "bio"), NoteFolder("eng", "英语"), NoteFolder("words", "词汇", "eng"))
    private val notes = listOf(Note("a", "课程总览", "课程原文保留。", "fixture", "fixture", folderId = "bio"), Note("b", "复制机制", "章节原文不能丢失。", "fixture", "fixture", folderId = "dna"), Note("c", "英语练习", "另一课程原文。", "fixture", "fixture", folderId = "words"))

    private fun library(fontScale: Float = 1f, block: (Application, StudyViewModel) -> Unit) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = notes, noteFolders = folders))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) { StudyTheme(false) {
                StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
                }
            } }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.waitUntil(5000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == false
            }
            block(app, vm)
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }
    }
    private fun top() { compose.onNodeWithTag("笔记列表").performScrollToIndex(0) }
    private fun chooseFolder(id: String) {
        top()
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("当前笔记本"))
        compose.onNodeWithTag("当前笔记本").performClick()
        if (id == "dna") compose.onNodeWithContentDescription("展开笔记本 遗传学").performClick()
        if (id == "words") compose.onNodeWithContentDescription("展开笔记本 英语").performClick()
        compose.onNodeWithTag("笔记本选择列表").performScrollToNode(hasTestTag("选择分类 $id"))
        compose.onNodeWithTag("选择分类 $id").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记本面板").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun rootSectionFiltersBatchMoveAndReaderReturnPreserveOrganization() = library { app, vm ->
        chooseFolder("bio")
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 b"))
        compose.onNodeWithTag("笔记卡片 c").assertDoesNotExist()
        chooseFolder("dna")
        compose.onNodeWithTag("笔记卡片 a").assertDoesNotExist()
        top(); compose.onNodeWithContentDescription("多选笔记").performClick()
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 b"))
        compose.onNodeWithTag("笔记卡片 b").performClick().assertIsSelected()
        top(); compose.onNodeWithContentDescription("移动所选笔记").performClick()
        compose.onNodeWithContentDescription("展开笔记本 英语").performClick()
        compose.onNodeWithTag("笔记本选择列表").performScrollToNode(hasTestTag("选择分类 words"))
        compose.onNodeWithTag("选择分类 words").performClick()
        compose.waitUntil(5000) { Vault(app).load().notes.single { it.id == "b" }.folderId == "words" }
        assertEquals(notes.single { it.id == "b" }.copy(folderId = "words"), vm.ui.value.data.notes.single { it.id == "b" })
        chooseFolder("bio")
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 a"))
        compose.onNodeWithTag("笔记卡片 a").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
        compose.openNoteTool("返回笔记列表")
        compose.onNodeWithTag("笔记卡片 c").assertDoesNotExist()
        top(); compose.onNodeWithTag("当前笔记本").assertTextContains("遗传学", substring = true)
        advancedUiShot(compose.activity, "sample-notebook-hierarchy")
    }
    @Test fun deletingSectionReturnsItsNotesToNotebookAndDeletingNotebookKeepsAllBodies() = library { _, vm ->
        compose.onNodeWithContentDescription("管理笔记分类").performClick()
        compose.onNodeWithContentDescription("删除分类 DNA 结构").performScrollTo().performClick()
        compose.onNodeWithText("删除章节？").assertIsDisplayed()
        compose.onNodeWithText("只删除分类").performClick()
        compose.runOnIdle { assertEquals("bio", vm.ui.value.data.notes.single { it.id == "b" }.folderId) }
        compose.onNodeWithContentDescription("删除分类 遗传学").performScrollTo().performClick()
        compose.onNodeWithText("删除笔记本？").assertIsDisplayed()
        compose.onNodeWithText("只删除分类").performClick()
        compose.onNodeWithContentDescription("关闭笔记本管理").performClick()
        compose.runOnIdle {
            assertEquals(notes.map { it.content }, vm.ui.value.data.notes.map { it.content })
            assertTrue(vm.ui.value.data.notes.filter { it.id != "c" }.all { it.folderId.isEmpty() })
            assertEquals("words", vm.ui.value.data.notes.single { it.id == "c" }.folderId)
        }
    }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() } }
    private fun viewport(width: Int, height: Int, block: () -> Unit) {
        check(shell("getprop ro.kernel.qemu").trim() == "1") { "Viewport checks run on disposable cloud emulators only" }
        try {
            shell("wm size ${width}x$height"); shell("wm density 320")
            compose.waitUntil(10000) {
                val metrics = compose.activity.resources.displayMetrics
                kotlin.math.abs(metrics.widthPixels - width) < 16 &&
                    kotlin.math.abs(metrics.density - 2f) < .01f &&
                    compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
            }
            block()
        } finally { shell("wm size reset"); shell("wm density reset") }
    }
    @Test fun tabletLandscapeHasSeparateDirectoryAndReadableContentWidth() = viewport(2560, 1600) {
        library { _, _ ->
            compose.onNodeWithTag("平板笔记本目录").assertIsDisplayed()
            val sidebar = compose.onNodeWithTag("平板笔记本目录").getUnclippedBoundsInRoot()
            val list = compose.onNodeWithTag("笔记列表").getUnclippedBoundsInRoot()
            assertTrue(list.left >= sidebar.right)
            assertTrue((list.right - list.left).value <= 920f)
            compose.onNodeWithTag("选择分类 bio").performClick()
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 b"))
            advancedUiShot(compose.activity, "sample-notebook-tablet-landscape")
            val screen = compose.onRoot().getUnclippedBoundsInRoot()
            compose.onNodeWithContentDescription("管理笔记分类").performClick()
            val panel = compose.onNodeWithTag("笔记本面板").getUnclippedBoundsInRoot()
            val panelWidth = panel.right - panel.left
            assertTrue(panelWidth.value <= 680f && panelWidth < screen.right - screen.left)
            compose.onNodeWithContentDescription("关闭笔记本管理").assertIsDisplayed().performClick()
            compose.onNodeWithTag("笔记卡片 b").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            val reader = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            assertTrue((reader.right - reader.left).value <= 840f)
            assertEquals((screen.left.value + screen.right.value) / 2, (reader.left.value + reader.right.value) / 2, 2f)
            advancedUiShot(compose.activity, "sample-notebook-tablet-reader")
        }
    }
    @Test fun tabletPortraitAndNarrowLargeTextUseReachableSingleColumnPicker() = viewport(1600, 2560) {
        library(fontScale = 2f) { _, _ ->
            compose.onNodeWithTag("平板笔记本目录").assertDoesNotExist()
            chooseFolder("words")
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 c"))
            advancedUiShot(compose.activity, "sample-notebook-tablet-portrait-large-text")
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("当前笔记本"))
            compose.onNodeWithTag("当前笔记本").performClick()
            compose.onNodeWithContentDescription("关闭选择笔记本").assertIsDisplayed().performClick()
        }
    }
    @Test fun splitWindowKeepsFolderPickerAndCloseInsideTheWindow() = viewport(920, 1600) {
        library(fontScale = 1.5f) { _, vm ->
            compose.onNodeWithTag("平板笔记本目录").assertDoesNotExist()
            chooseFolder("dna")
            settledWindowShot("sample-notebook-split-window", 1)
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("当前笔记本"))
            compose.onNodeWithTag("当前笔记本").performTouchInput { click() }
            settledWindowShot("sample-notebook-split-picker-settled", 2)
            compose.onNodeWithContentDescription("关闭选择笔记本").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记本面板").fetchSemanticsNodes().isEmpty() }
            settledWindowShot("sample-notebook-split-closed-settled", 1)
            compose.onNodeWithTag("笔记卡片 b").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("b", vm.noteReader.id)
            settledWindowShot("sample-notebook-split-reader-settled", 1)
        }
    }
    /** Observe platform window exit animations as well as Compose semantics. */
    private fun settledWindowShot(name: String, windows: Int) {
        var previous: android.graphics.Bitmap? = null
        var identicalFrames = 0
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            repeat(80) { sample ->
                var attached = 0
                compose.runOnIdle { attached = android.view.inspector.WindowInspector.getGlobalWindowViews().count { it.isAttachedToWindow } }
                val bitmap = requireNotNull(automation.takeScreenshot())
                if (sample == 0) saveUiReviewBitmap(compose.activity, "$name-initial", bitmap)
                identicalFrames = if (attached == windows && previous?.sameAs(bitmap) == true) identicalFrames + 1 else 0
                android.util.Log.i("SettledWindow", "$name sample=$sample windows=$attached identical=$identicalFrames")
                previous?.recycle()
                previous = bitmap
                if (identicalFrames >= 3) {
                    saveUiReviewBitmap(compose.activity, name, bitmap)
                    return
                }
                android.os.SystemClock.sleep(80)
            }
            error("Window/pixels did not settle for $name (expected $windows windows)")
        } finally { previous?.recycle() }
    }
    @Test fun batchMoveCanCreateItsDestinationWithoutOpeningAnotherManager() = library { _, vm ->
        top(); compose.onNodeWithContentDescription("多选笔记").performClick()
        listOf("a", "b").forEach { id ->
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 $id"))
            compose.onNodeWithTag("笔记卡片 $id").performClick().assertIsSelected()
        }
        top(); compose.onNodeWithContentDescription("移动所选笔记").performClick()
        compose.onNodeWithText("新建笔记本").performClick()
        compose.onAllNodesWithTag("笔记本面板").assertCountEquals(1)
        compose.onNodeWithText("取消新建").performClick()
        compose.onNodeWithText("新建笔记本").performClick()
        compose.onNodeWithText("新笔记本名称").performTextInput("遗传学")
        compose.onNodeWithText("创建并移入").performClick()
        compose.onNodeWithText("未能创建", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("新笔记本名称").performScrollTo().performTextReplacement("期末专题")
        compose.onNodeWithText("创建并移入").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记本面板").fetchSemanticsNodes().isEmpty() }
        val target = vm.ui.value.data.noteFolders.single { it.name == "期末专题" }
        compose.runOnIdle { assertTrue(vm.ui.value.data.notes.filter { it.id in setOf("a", "b") }.all { it.folderId == target.id }) }
        top(); compose.onNodeWithContentDescription("多选笔记").performClick()
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasTestTag("笔记卡片 c"))
        compose.onNodeWithTag("笔记卡片 c").performClick()
        top(); compose.onNodeWithContentDescription("移动所选笔记").performClick()
        compose.onNodeWithText("新建章节").performClick()
        compose.onNodeWithText("新章节名称").performTextInput("重点词汇")
        compose.onNodeWithText("英语").performScrollTo().performClick()
        compose.onNodeWithText("创建并移入").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记本面板").fetchSemanticsNodes().isEmpty() }
        val chapter = vm.ui.value.data.noteFolders.single { it.name == "重点词汇" }
        assertEquals("eng", chapter.parentId)
        assertEquals(chapter.id, vm.ui.value.data.notes.single { it.id == "c" }.folderId)
        advancedUiShot(compose.activity, "sample-create-move-destination")
    }
}
