package cn.studybridge.mobile

import android.app.Application
import android.graphics.Rect
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private val markers = (1..3).flatMap { page -> (1..6).map { row -> "来源${page}段落${row}标记" } + "来源${page}页尾标记" }
    private fun fullNote() = Note(
        title = "完整阅读体验",
        content = (1..3).joinToString("\n\n") { page ->
            "## 第 $page 页\n\n" + (1..6).joinToString("\n\n") { row ->
                "来源${page}段落${row}标记。" + "先理解模板方向，再联系配对关系与具体步骤。".repeat(8)
            } + "\n\n来源${page}页尾标记。"
        }, source = "三个合成来源页", model = "fixture"
    )

    private fun withReader(note: Note, block: (Application, StudyViewModel) -> Unit) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note)))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) {
                StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
                }
            }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(note.title))
            compose.onNodeWithText(note.title).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { nativeSnapshot { views -> views.any { it.layout != null && it.text.isNotBlank() } } }
            block(app, vm)
        } finally { compose.runOnIdle { store.clear() } }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private val readingViewport = Rect()
    private fun <T> nativeSnapshot(read: (List<NoteSelectableTextView>) -> T): T {
        val bounds = compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        val stickyBottom = compose.onAllNodesWithTag("笔记吸顶标题").fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.bottom
        var result: T? = null
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            if (bounds != null) {
                val screen = IntArray(2); decor.getLocationOnScreen(screen)
                val window = IntArray(2); decor.getLocationInWindow(window)
                readingViewport.set((bounds.left + screen[0] - window[0]).toInt(), (maxOf(bounds.top, stickyBottom ?: bounds.top) + screen[1] - window[1]).toInt(), (bounds.right + screen[0] - window[0]).toInt(), (bounds.bottom + screen[1] - window[1]).toInt())
            }
            result = read(descendants(decor).filterIsInstance<NoteSelectableTextView>())
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // A whole paragraph can exist in a TextView while its lines are below the
    // viewport. Count a marker only after its actual native line was visible.
    private fun lineVisible(view: NoteSelectableTextView, offset: Int): Boolean {
        if (!view.isShown || !view.isAttachedToWindow) return false
        val layout = view.layout ?: return false
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible) || !visible.intersect(readingViewport)) return false
        val location = IntArray(2); view.getLocationOnScreen(location)
        val line = layout.getLineForOffset(offset.coerceIn(0, view.text.length))
        val top = location[1] + view.totalPaddingTop - view.scrollY + layout.getLineTop(line)
        val bottom = location[1] + view.totalPaddingTop - view.scrollY + layout.getLineBottom(line)
        return top >= visible.top && bottom <= visible.bottom
    }

    private fun visibleMarkers(wanted: List<String>) = nativeSnapshot { views ->
        wanted.filter { marker -> views.any { view ->
            val offset = view.text.toString().indexOf(marker)
            offset >= 0 && lineVisible(view, offset)
        } }.toSet()
    }

    private fun assertReaderChrome() {
        compose.onAllNodesWithContentDescription("悬浮阅读工具").assertCountEquals(1)
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
        compose.onNodeWithTag("应用底部导航").assertDoesNotExist()
        compose.onNodeWithTag("按屏翻页栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("当前阅读页码").assertIsDisplayed()
        val clock = compose.onNodeWithTag("应用时间栏").getUnclippedBoundsInRoot()
        val page = compose.onNodeWithContentDescription("当前阅读页码").getUnclippedBoundsInRoot()
        assertTrue("Page number belongs in the time row", page.top >= clock.top && page.bottom <= clock.bottom)
    }

    private fun assertSingleVerticalTools() {
        compose.ensureNoteTools()
        compose.onAllNodesWithTag("阅读操作面板").assertCountEquals(1)
        val first = compose.onNodeWithContentDescription("返回笔记列表").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithContentDescription("目录跳转").getUnclippedBoundsInRoot()
        assertTrue("Reading actions must be vertical", second.top >= first.bottom)
        listOf("返回笔记列表", "目录跳转", "阅读跳转", "阅读设置", "笔记选项", "设置").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        compose.onNodeWithContentDescription("关闭阅读工具").performClick()
        assertReaderChrome()
    }

    @Test fun continuousGesturesShowEverySourceParagraphThroughTheLastLine() = withReader(fullNote()) { _, vm ->
        assertReaderChrome()
        val seen = mutableSetOf<String>()
        for (attempt in 0 until 45) {
            seen += visibleMarkers(markers)
            if (attempt % 5 == 0) android.util.Log.i("ReaderRegression", "continuous step=$attempt seen=${seen.size} axis=" + compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
            if (seen.size == markers.size) break
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(center.x, height * .8f))
                moveTo(Offset(center.x, height * .25f), 600)
                advanceEventTime(250)
                up()
            }
            compose.waitForIdle()
        }
        advancedUiShot(compose.activity, "sample-reader-continuous-end")
        assertEquals("Scrolling must expose every source marker", markers.toSet(), seen)
        compose.waitUntil(5000) { vm.readingMemory.position(vm.noteReader.id!!)?.index == 2 }
        compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 3 / 3 · 完整笔记已保留"))
        assertReaderChrome()
        assertSingleVerticalTools()
    }

    @Test fun rightEdgeTapsShowEachScreenOfEveryLongSourcePageBeforeAdvancing() = withReader(fullNote()) { app, vm ->
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("点击翻页").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "buttons" }
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.waitUntil(5000) { visibleMarkers(listOf(markers.first())).isNotEmpty() }
        val seen = mutableSetOf<String>()
        for (attempt in 0 until 35) {
            seen += visibleMarkers(markers)
            if (seen.size == markers.size) break
            // The right reading zone is the only forward action used here.
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .92f, height * .5f)) }
            compose.waitForIdle()
        }
        assertEquals("A source transition must never skip its remaining screens", markers.toSet(), seen)
        compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 3 / 3 · 完整笔记已保留"))
        compose.waitUntil(5000) { Vault(app).load().noteReadMode == "buttons" }
        assertReaderChrome()
        advancedUiShot(compose.activity, "sample-reader-click-pages")
        assertSingleVerticalTools()
    }

    @Test fun fontControlsResizeNativeBodyAndPersistTheChosenSize() = withReader(fullNote()) { app, vm ->
        val baseline = nativeSnapshot { views -> views.first { it.text.contains(markers.first()) }.textSize }
        compose.openNoteTool("阅读设置")
        repeat(3) { compose.onNodeWithText("增大字号").performScrollTo().performClick() }
        compose.onNodeWithText("19 sp").assertIsDisplayed()
        compose.waitUntil(5000) { vm.ui.value.data.noteFontSize == 19 && Vault(app).load().noteFontSize == 19 }
        advancedUiShot(compose.activity, "sample-reader-font-settings")
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.waitUntil(5000) { nativeSnapshot { views -> views.any { it.text.contains(markers.first()) && it.textSize > baseline } } }
        val enlarged = nativeSnapshot { views -> views.first { it.text.contains(markers.first()) }.textSize }
        assertEquals("Native text must use the selected sp size", baseline * 19f / 16f, enlarged, 1f)
        compose.openNoteTool("返回笔记列表")
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText("完整阅读体验"))
        compose.onNodeWithText("完整阅读体验").performClick()
        compose.waitUntil(5000) { nativeSnapshot { views -> views.any { it.text.contains(markers.first()) && kotlin.math.abs(it.textSize - enlarged) < 1f } } }
        assertEquals(19, Vault(app).load().noteFontSize)
        assertReaderChrome()
        assertSingleVerticalTools()
    }

    @Test fun longStickyTitleUsesEllipsisAndNeverMovesTheReadingViewport() {
        val title = "遗传学课堂笔记与染色体结构的详细复习整理".repeat(4)
        withReader(fullNote().copy(title = title)) { _, vm ->
            val before = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            compose.onNodeWithTag("笔记正文").performScrollToIndex(1)
            compose.onNodeWithTag("笔记吸顶标题").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("笔记吸顶标题")))
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue("The overflowing title must visibly end with ellipsis", layouts.single().isLineEllipsized(0))
            val after = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            assertEquals("Title entrance must not push the body down", before.top.value, after.top.value, .1f)
            assertEquals(before.bottom.value, after.bottom.value, .1f)
            advancedUiShot(compose.activity, "sample-reader-sticky-title")
            compose.onNodeWithTag("笔记正文").performScrollToIndex(0)
            compose.onNodeWithTag("笔记吸顶标题").assertDoesNotExist()
            compose.onNodeWithTag("笔记原始标题").assertTextEquals(title)
            compose.openNoteTool("阅读设置")
            compose.onNodeWithText("点击翻页").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "buttons" }
            compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .92f, height * .5f)) }
            compose.onNodeWithTag("笔记吸顶标题").assertDoesNotExist()
        }
    }

    @Test fun horizontalGesturesAlsoVisitEveryScreenBeforeChangingSourcePage() = withReader(fullNote()) { _, vm ->
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("横向滑屏").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "swipe" }
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.waitUntil(5000) { visibleMarkers(listOf(markers.first())).isNotEmpty() }
        val seen = mutableSetOf<String>()
        for (attempt in 0 until 35) {
            seen += visibleMarkers(markers)
            if (seen.size == markers.size) break
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 180) }
            compose.waitForIdle()
        }
        assertEquals("Horizontal turns must not skip source content", markers.toSet(), seen)
        compose.onNodeWithTag("笔记吸顶标题").assertDoesNotExist()
        assertReaderChrome()
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("横向滑屏").assertIsSelected()
        advancedUiShot(compose.activity, "sample-reader-swipe-pages")
    }

    @Test fun searchHighlightsNativeTextAndPreviousNextRevealTheActualHitLines() {
        val query = "模板坐标"
        val hits = listOf("定位甲", "定位乙", "定位丙")
        val filler = "先看定义，再理解配对、方向和机制。\n\n".repeat(18)
        val note = Note(title = "命中定位", content = hits.mapIndexed { index, marker -> "## 第 ${index + 1} 页\n\n" + filler + "$marker：$query。\n\n" + filler }.joinToString("\n\n"), source = "合成查找页", model = "fixture")
        withReader(note) { _, _ ->
            compose.openNoteTool("阅读跳转")
            compose.onNodeWithText("笔记内查找").performScrollTo().performClick()
            compose.onNodeWithText("查找完整正文").performTextInput(query)
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 3 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("找到 3 处").assertIsDisplayed()
            compose.onNodeWithText("第 1 段").performClick()
            fun assertHit(index: Int) {
                compose.waitUntil(5000) { compose.onAllNodesWithText("$query · ${index + 1}/3").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("$query · ${index + 1}/3").assertIsDisplayed()
                compose.waitUntil(5000) { nativeSnapshot { views -> views.any { view ->
                    val text = view.text as? Spanned
                    val markerOffset = view.text.toString().indexOf(hits[index])
                    val wordOffset = if (markerOffset < 0) -1 else view.text.toString().indexOf(query, markerOffset)
                    text != null && wordOffset >= 0 && lineVisible(view, wordOffset) &&
                        text.getSpans(wordOffset, wordOffset + query.length, BackgroundColorSpan::class.java).any { span ->
                            text.getSpanStart(span) == wordOffset && text.getSpanEnd(span) == wordOffset + query.length
                        }
                } } }
                compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 ${index + 1} / 3 · 完整笔记已保留"))
            }
            assertHit(0)
            compose.onNodeWithContentDescription("上一处").assertIsNotEnabled()
            compose.onNodeWithContentDescription("下一处").performClick(); assertHit(1)
            compose.onNodeWithContentDescription("下一处").performClick(); assertHit(2)
            compose.onNodeWithContentDescription("下一处").assertIsNotEnabled()
            compose.onNodeWithContentDescription("上一处").performClick(); assertHit(1)
            advancedUiShot(compose.activity, "sample-reader-search-highlight")
            compose.onNodeWithContentDescription("结束查找").performClick()
            compose.onNodeWithTag("查找命中导航").assertDoesNotExist()
            compose.waitUntil(5000) { nativeSnapshot { views -> views.all { view ->
                (view.text as? Spanned)?.getSpans(0, view.text.length, BackgroundColorSpan::class.java).orEmpty().isEmpty()
            } } }
            assertReaderChrome()
            assertSingleVerticalTools()
        }
    }
}
