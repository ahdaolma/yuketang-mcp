package cn.studybridge.mobile

import android.graphics.Rect
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderBackwardStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> native(block: (List<NoteSelectableTextView>) -> T): T {
        var result: T? = null
        compose.runOnIdle { result = block(descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>()) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun markerVisible(marker: String): Boolean {
        val bounds = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
        return native { views -> views.any { view ->
            val offset = view.text.toString().indexOf(marker)
            val layout = view.layout
            if (offset < 0 || layout == null || !view.isAttachedToWindow || view.alpha < .99f) false
            else {
                val position = IntArray(2); view.getLocationInWindow(position)
                val line = layout.getLineForOffset(offset)
                val top = position[1] + view.totalPaddingTop - view.scrollY + layout.getLineTop(line)
                val bottom = position[1] + view.totalPaddingTop - view.scrollY + layout.getLineBottom(line)
                val visible = Rect()
                view.getGlobalVisibleRect(visible) && top >= bounds.top && bottom <= bounds.bottom &&
                    position[0] < bounds.right && position[0] + view.width > bounds.left
            }
        } }
    }
    private fun exercise(mode: String) {
        val image = syntheticSlideImage()
        val imageId = imageDigest(android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT))
        val note = Note(title = "第二页退回第一页-$mode", content =
            "## 第 1 页\n\n第一页回退标记。\n\n" + (1..6).joinToString("\n\n") { "首页短段落${it}。" } +
                "\n\n## 第 2 页\n\n第二页起点标记。\n\n" + sourceImageMarkdown(imageId, "回退原图") +
                (1..10).joinToString("\n\n") { "第二页长段落${it}。" + "图文读完后仍然应能回到第一页。".repeat(10) },
            source = "两页回退合成笔记", model = "fixture")
        val app = noteTestApplication(compose.activity.applicationContext)
        SourceImages(app).save(image)
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = mode))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) {
                StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { NotesScreen(ui, vm) }
                }
            }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(note.title))
            compose.onNodeWithText(note.title).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            for (trigger in listOf("plain-no-focus", "collapsed-cursor", "focus-selection", "image-close", "resume")) {
                compose.openNoteTool("整本开头")
                compose.openNoteTool("下一段")
                compose.waitUntil(5000) { markerVisible("第二页起点标记") }
                when (trigger) {
                    "plain-no-focus" -> native { views ->
                        views.forEach { it.finishSelection() }
                        assertFalse("Control case has no focused native reader", views.any { it.isFocused })
                    }
                    "collapsed-cursor" -> native { views ->
                        val view = views.first { it.text.contains("第二页起点标记") }
                        assertTrue("The cursor regression requires native focus", view.requestFocus())
                        val value = view.text as Spannable
                        val offset = value.toString().indexOf("第二页起点标记")
                        // Keep a real caret, matching a normal native text tap.
                        // Removing selection sets -1 and skips TextView's
                        // onPreDraw -> bringPointIntoView cursor path.
                        Selection.setSelection(value, offset)
                        view.reportSelection()
                        assertEquals(offset, view.selectionStart)
                        assertEquals(offset, view.selectionEnd)
                        assertNull("A collapsed caret must not own the page gesture as a selected range", view.controller?.selection)
                    }
                    "focus-selection" -> native { views ->
                        val view = views.first { it.text.contains("第二页起点标记") }
                        view.requestFocus()
                        val value = view.text as Spannable
                        val offset = value.toString().indexOf("第二页起点标记")
                        Selection.setSelection(value, offset, offset + 4)
                        view.reportSelection()
                        Selection.removeSelection(value)
                        view.reportSelection()
                    }
                    "image-close" -> {
                        compose.onNodeWithContentDescription("回退原图 缩略图").performClick()
                        compose.onNodeWithText("关闭预览").performClick()
                    }
                    "resume" -> {
                        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    }
                }
                for (attempt in 0 until 8) {
                    if (markerVisible("第一页回退标记")) break
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        when (mode) {
                            "buttons" -> click(Offset(width * .1f, height * .5f))
                            "swipe" -> swipeRight(durationMillis = 180)
                            else -> { down(Offset(width * .5f, height * .25f)); moveTo(Offset(width * .5f, height * .85f), 300); advanceEventTime(200); up() }
                        }
                    }
                    compose.waitForIdle()
                }
                assertTrue("Page 2 must return to visible page 1 in $mode after $trigger", markerVisible("第一页回退标记"))
                repeat(10) {
                    compose.mainClock.advanceTimeBy(100)
                    compose.waitForIdle()
                    assertTrue("Page 1 must not bounce back to page 2 in $mode after $trigger", markerVisible("第一页回退标记"))
                    val page = compose.onNodeWithContentDescription("当前阅读页码").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                    assertTrue("Counter must remain on the visible first source page: $page", page.contains("分段 1 / 2"))
                }
            }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun continuousReturnsFromSecondToFirstAfterNativeFocusPreviewAndResume() = exercise("continuous")
    @Test fun tapsReturnFromSecondToFirstAfterNativeFocusPreviewAndResume() = exercise("buttons")
    @Test fun swipesReturnFromSecondToFirstAfterNativeFocusPreviewAndResume() = exercise("swipe")
}
