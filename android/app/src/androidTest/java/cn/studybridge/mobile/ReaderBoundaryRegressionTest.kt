package cn.studybridge.mobile

import android.app.Application
import android.text.Selection
import android.text.Spannable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Small reverse gestures and a first source page that fits before tool padding. */
class ReaderBoundaryRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> native(block: (List<NoteSelectableTextView>) -> T): T {
        var result: T? = null
        compose.runOnIdle { result = block(descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>()) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun top(marker: String): Float = native { views ->
        val view = views.first { it.isAttachedToWindow && it.alpha >= .99f && it.text.contains(marker) }
        val location = IntArray(2); view.getLocationInWindow(location)
        location[1].toFloat()
    }
    private fun ready(marker: String): Boolean = native { views -> views.any { it.isAttachedToWindow && it.alpha >= .99f && it.layout != null && it.text.contains(marker) } }
    private fun tailBounds(): Rect = native { views ->
        val view = views.first { it.isAttachedToWindow && it.alpha >= .99f && it.text.contains("FIRST_END") }
        val position = IntArray(2); view.getLocationInWindow(position)
        val layout = requireNotNull(view.layout); val line = layout.lineCount - 1
        Rect(position[0] + view.totalPaddingLeft + layout.getLineLeft(line),
            (position[1] + view.totalPaddingTop + layout.getLineTop(line)).toFloat(),
            position[0] + view.totalPaddingLeft + layout.getLineRight(line),
            (position[1] + view.totalPaddingTop + layout.getLineBottom(line)).toFloat())
    }
    private fun page(number: Int): Boolean = compose.onAllNodesWithContentDescription("当前阅读页码").fetchSemanticsNodes().any {
        it.config[SemanticsProperties.StateDescription].contains("分段 $number / 2")
    }
    private fun snapshot() = native { views -> views.filter { it.isAttachedToWindow }.map { view ->
        val location = IntArray(2); view.getLocationInWindow(location)
        "${view.block?.sourceStart}:${view.width}x${view.height}@${location[1]} focus=${view.isFocused} caret=${view.selectionStart}/${view.selectionEnd} text=${view.text.take(28)}"
    }.joinToString(";") }

    private fun fixture(rich: Boolean = false, firstParagraphs: Int = 7) = Note(title = "短划页边界", source = "两页合成资料", model = "fixture", content =
        "## 第 1 页\n\nFIRST_START。\n\n" + (1..firstParagraphs).joinToString("\n\n") { "首页段落$it。" + "先确认模板方向再解释配对关系。".repeat(2) } +
            (if (rich) "\n\n[短链接](https://example.com/${"long-source-path-".repeat(22)})" else "") + "\n\nFIRST_END。\n\n" +
            "## 第 2 页\n\nSECOND_START。\n\n" + (1..8).joinToString("\n\n") { "第二页长段落$it。" + "连续阅读应保持手指移动的方向。".repeat(4) })

    private fun withReader(note: Note, mode: String, second: Boolean, height: MutableState<Float> = mutableStateOf(1f), block: (Application, StudyViewModel) -> Unit) {
        assertEquals("Fixture must have exactly two source segments", 2, noteReadingSegments(note.content).size)
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = mode))
        // Reading positions use their own encrypted store, not Saved's backup field.
        if (second) Vault(app, "note-reading-v1.enc").save(Saved(noteReadingPositions =
            listOf(NoteReadingPosition(note.id, 1, 0, "第 2 页", noteContentHash(note.content)))))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) { StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                Box(Modifier.fillMaxHeight(height.value).fillMaxWidth().padding(padding).consumeWindowInsets(padding)) { NotesScreen(ui, vm) }
            } }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(note.title))
            compose.onNodeWithText(note.title).performClick()
            val marker = if (second) "SECOND_START" else "FIRST_START"
            compose.waitUntil(8000) { ready(marker) && page(if (second) 2 else 1) }
            compose.waitForIdle()
            block(app, vm)
        } finally { compose.runOnUiThread { store.clear() } }
    }

    private fun reverse(rich: Boolean, caret: Boolean, fraction: Float, note: Note = fixture(rich)) = withReader(note, "continuous", true) { _, _ ->
        val bounds = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
        if (caret) native { views ->
            val view = views.first { it.text.contains("SECOND_START") }
            view.requestFocus()
            val location = IntArray(2); view.getLocationInWindow(location)
            val layout = requireNotNull(view.layout)
            val line = (0 until layout.lineCount).last { location[1] + view.totalPaddingTop + layout.getLineBottom(it) < bounds.bottom - 4 }
            Selection.setSelection(view.text as Spannable, layout.getLineStart(line))
            view.reportSelection()
        } else native { views -> views.forEach { it.finishSelection() } }
        compose.waitForIdle()
        val before = top("SECOND_START")
        var moved = 0f
        compose.onNodeWithTag("笔记正文").performTouchInput {
            moved = height * fraction
            down(Offset(center.x, height * .45f))
            moveTo(Offset(center.x, height * .45f + moved), 300)
            advanceEventTime(240); up()
        }
        compose.waitForIdle()
        repeat(8) { frame ->
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val after = top("SECOND_START")
            Log.i("ReaderBoundary", "rich=$rich caret=$caret fraction=$fraction frame=$frame before=$before after=$after requested=$moved views=${snapshot()}")
            assertTrue("Small reverse must retain real visible progress: rich=$rich caret=$caret before=$before after=$after requested=$moved", after >= before + moved * .25f)
            assertTrue("Layout must not overshoot the finger: before=$before after=$after requested=$moved", after <= before + moved * 1.25f)
        }
        advancedUiShot(compose.activity, "reader-boundary-reverse-$rich-$caret")
    }

    @Test fun shortReverseOnPlainTextDoesNotReturnToSecondPage() = reverse(false, false, .05f)
    @Test fun shortReverseAcrossFormattedPageDoesNotReturnToSecondPage() = reverse(true, false, .05f)
    @Test fun shortReverseWithVisibleBottomCaretDoesNotPullBack() = reverse(false, true, .05f)

    @Test fun shortReverseAcrossTableLayoutDoesNotReturnToSecondPage() {
        val note = fixture().let { it.copy(content = it.content.replace("FIRST_END。", "| 概念 | 对应解释 |\n| --- | --- |\n" +
            (1..5).joinToString("\n") { row -> "| 概念$row | 表格换行后仍应保持手指移动方向，不能把读者推回下一页。 |" } + "\n\nFIRST_END。")) }
        reverse(false, false, .05f, note)
    }

    @Test fun longFormattedSourceRemainsVirtualizedAndKeepsReverseProgress() {
        val source = "## 第 1 页\n\nFIRST_START。\n\n" + (1..85).joinToString("\n\n") {
            "**要点$it**：" + "按原始资料解释知识点，保留完整的阅读顺序。".repeat(4) + " [参考](https://example.com/topic/$it)"
        } + "\n\nFIRST_END。\n\n" + fixture().content.substringAfter("## 第 2 页")
            .let { "## 第 2 页$it" }
        reverse(false, false, .05f, fixture().copy(content = source))
    }

    @Test fun repeatedClicksAdvanceActualShortPageContentInBothDirections() {
        val note = Note(title = "短页点击", source = "两页", model = "fixture", content = "## 第 1 页\n\nFIRST_START。\n\n## 第 2 页\n\nSECOND_START。")
        withReader(note, "buttons", false) { _, _ ->
            repeat(8) {
                compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .9f, height * .5f)) }
                compose.waitUntil(8000) { page(2) && ready("SECOND_START") && !ready("FIRST_START") }
                compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .1f, height * .5f)) }
                compose.waitUntil(8000) { page(1) && ready("FIRST_START") && !ready("SECOND_START") }
            }
        }
    }

    private fun paddingBoundary(mode: String, obscured: Boolean) {
        val heightFraction = mutableStateOf(1f)
        val note = fixture(firstParagraphs = 4).let { if (!obscured) it else it.copy(content = it.content.replace("FIRST_END。", "FIRST_END。\n\n" + "满".repeat(22))) }
        withReader(note, mode, false, heightFraction) { _, _ ->
            val body = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
            val lastBottom = tailBounds().bottom
            val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
            val desiredBody = lastBottom - body.top + 20f
            compose.runOnIdle { heightFraction.value = (1f - (body.height - desiredBody) / root.height).coerceIn(.3f, 1f) }
            compose.waitForIdle()
            val current = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
            assertTrue("All first source lines are already inside the real viewport", lastBottom < current.bottom)
            val tool = compose.onNodeWithTag("笔记悬浮工具").fetchSemanticsNode().boundsInWindow
            assertEquals("Fixture must distinguish actually covered glyphs from empty end space", obscured, tailBounds().overlaps(tool))
            Log.i("ReaderBoundary", "padding mode=$mode obscured=$obscured body=$current tail=${tailBounds()} tool=$tool views=${snapshot()}")
            fun advance() = compose.onNodeWithTag("笔记正文").performTouchInput {
                if (mode == "buttons") click(Offset(width * .9f, height * .5f)) else swipeLeft(durationMillis = 220)
            }
            if (obscured) {
                val before = top("FIRST_START")
                advance()
                compose.waitUntil(8000) { page(1) && ready("FIRST_START") && native { views -> views.count { it.isAttachedToWindow && it.text.contains("FIRST_START") } } == 1 && top("FIRST_START") < before - 1f }
                assertFalse("The last full-width line must be clear of the tool before changing source page", tailBounds().overlaps(tool))
            }
            advance()
            compose.waitUntil(8000) { page(2) && ready("SECOND_START") && !ready("FIRST_START") }
            advancedUiShot(compose.activity, "reader-padding-$mode-$obscured")
        }
    }
    @Test fun clickDoesNotSpendAScreenOnPaddingAfterAllFirstPageLinesAreVisible() = paddingBoundary("buttons", false)
    @Test fun swipeDoesNotSpendAScreenOnPaddingAfterAllFirstPageLinesAreVisible() = paddingBoundary("swipe", false)
    @Test fun clickClearsCoveredLastLineBeforeChangingSourcePage() = paddingBoundary("buttons", true)
    @Test fun swipeClearsCoveredLastLineBeforeChangingSourcePage() = paddingBoundary("swipe", true)
}
