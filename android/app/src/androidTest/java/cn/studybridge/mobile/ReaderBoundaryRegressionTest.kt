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
    private fun page(number: Int, total: Int = 2): Boolean = compose.onAllNodesWithContentDescription("当前阅读页码").fetchSemanticsNodes().any {
        it.config[SemanticsProperties.StateDescription].contains("分段 $number / $total")
    }
    private fun snapshot() = native { views -> views.filter { it.isAttachedToWindow }.map { view ->
        val location = IntArray(2); view.getLocationInWindow(location)
        "${view.block?.sourceStart}:${view.width}x${view.height}@${location[1]} focus=${view.isFocused} caret=${view.selectionStart}/${view.selectionEnd} text=${view.text.take(28)}"
    }.joinToString(";") }

    private fun fixture(rich: Boolean = false, firstParagraphs: Int = 7) = Note(title = "短划页边界", source = "两页合成资料", model = "fixture", content =
        "## 第 1 页\n\nFIRST_START。\n\n" + (1..firstParagraphs).joinToString("\n\n") { "首页段落$it。" + "先确认模板方向再解释配对关系。".repeat(2) } +
            (if (rich) "\n\n[短链接](https://example.com/${"long-source-path-".repeat(22)})" else "") + "\n\nFIRST_END。\n\n" +
            "## 第 2 页\n\nSECOND_START。\n\n" + (1..8).joinToString("\n\n") { "第二页长段落$it。" + "连续阅读应保持手指移动的方向。".repeat(4) })

    private fun withReader(note: Note, mode: String, second: Boolean, height: MutableState<Float> = mutableStateOf(1f), width: MutableState<Float> = mutableStateOf(1f), setup: (Application) -> Unit = {}, block: (Application, StudyViewModel) -> Unit) {
        assertEquals("Fixture must have exactly two source segments", 2, noteReadingSegments(note.content).size)
        val app = noteTestApplication(compose.activity.applicationContext)
        setup(app)
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
                Box(Modifier.fillMaxHeight(height.value).fillMaxWidth(width.value).padding(padding).consumeWindowInsets(padding)) { NotesScreen(ui, vm) }
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

    private fun reverse(rich: Boolean, caret: Boolean, fraction: Float, note: Note = fixture(rich), setup: (Application) -> Unit = {}) = withReader(note, "continuous", true, setup = setup) { _, _ ->
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

    @Test fun tableCellsFollowActualFontAndWindowWidth() {
        val widthFraction = mutableStateOf(1f)
        val note = fixture().let { it.copy(content = it.content.replace("FIRST_END。", "| 概念 | 对应解释 |\n| --- | --- |\n| 重点 | 表格字号与窗口应同步变化 |\n\nFIRST_END。")) }
        withReader(note, "continuous", false, width = widthFraction) { _, vm ->
            fun tableMatches(): Boolean = native { views ->
                val view = views.firstOrNull { it.isAttachedToWindow && it.text.contains("FIRST_START") } ?: return@native false
                val rows = (view.text as? android.text.Spanned)?.getSpans(0, view.text.length, io.noties.markwon.ext.tables.TableRowSpan::class.java).orEmpty()
                rows.size == 2 && rows.all { row ->
                    val cell = row.findLayoutForHorizontalOffset(0)
                    cell != null && kotlin.math.abs(cell.paint.textSize - view.textSize) < .5f && kotlin.math.abs(row.cellWidth() * 2 - view.width) <= 2
                }
            }
            compose.waitUntil(8000) { tableMatches() }
            val baseline = native { views -> views.first { it.text.contains("FIRST_START") }.textSize }
            compose.runOnIdle { vm.updateNotebookState { it.copy(noteFontSize = 24) }; widthFraction.value = .7f }
            compose.waitUntil(8000) { tableMatches() && native { views -> views.any { it.text.contains("FIRST_START") && it.textSize > baseline * 1.4f } } }
        }
    }

    @Test fun shortReverseAcrossSourceImageDoesNotJumpAsPreviewLoads() {
        val image = syntheticSlideImage()
        val id = imageDigest(android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT))
        val note = fixture().let { it.copy(content = it.content.replace("FIRST_END。", sourceImageMarkdown(id, "小幅回退原图") + "FIRST_END。")) }
        reverse(false, false, .05f, note) { app -> SourceImages(app).save(image) }
    }

    @Test fun longFormattedSourceRemainsVirtualizedAndKeepsReverseProgress() {
        val source = "## 第 1 页\n\nFIRST_START。\n\n" + (1..85).joinToString("\n\n") {
            "**要点$it**：" + "按原始资料解释知识点，保留完整的阅读顺序。".repeat(4) + " [参考](https://example.com/topic/$it)"
        } + "\n\nFIRST_END。\n\n" + fixture().content.substringAfter("## 第 2 页")
            .let { "## 第 2 页$it" }
        reverse(false, false, .05f, fixture().copy(content = source))
    }

    private fun appendCompletedClassPage(mode: String, caret: Boolean, marked: Boolean = false) {
        // Match ClassNotebooks' completed-page checkpoint, including its final
        // blank separator. Appending must not rebuild the page being read.
        val note = fixture().let { it.copy(content = it.content + "\n\n---\n\n") }
        withReader(note, mode, false) { _, vm ->
            compose.openNoteTool("下一段")
            compose.waitUntil(8000) { page(2) && ready("SECOND_START") }
            compose.onNodeWithTag("笔记正文").performTouchInput {
                if (mode == "continuous") {
                    down(Offset(center.x, height * .7f))
                    moveTo(Offset(center.x, height * .35f), 500)
                    advanceEventTime(240); up()
                } else click(Offset(width * .9f, height * .5f))
            }
            compose.waitUntil(8000) { vm.readingMemory.position(note.id)?.let { it.index == 1 && (it.textAnchor?.offset ?: 0) > 0 } == true }
            val before = native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") } }
            if (marked) {
                val annotation = native { _ ->
                    val quote = "第二页长段落4"
                    val start = before.text.indexOf(quote)
                    assertTrue(start >= 0)
                    NoteTextSelection(before, requireNotNull(before.block), start, start + quote.length, quote).annotation("remark", "完成页不重置这条备注")
                }
                compose.runOnIdle { assertTrue(vm.saveNoteAnnotation(note.id, annotation)) }
                compose.waitUntil(8000) { native { _ -> (before.text as android.text.Spanned).getSpans(0, before.text.length, NoteAnnotationSpan::class.java).any { it.annotation.id == annotation.id } } }
            }
            val body = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
            if (caret) native { _ ->
                val location = IntArray(2); before.getLocationInWindow(location)
                val layout = requireNotNull(before.layout)
                val line = (0 until layout.lineCount).first { location[1] + layout.getLineTop(it) >= body.top + 40 }
                before.requestFocus()
                Selection.setSelection(before.text as Spannable, layout.getLineStart(line))
                before.reportSelection()
            }
            compose.waitForIdle()
            val oldTop = top("SECOND_START")
            val oldText = native { before.text }
            val oldCaret = native { before.selectionStart }
            compose.runOnIdle { vm.updateNotebookState { saved -> saved.copy(notes = saved.notes.map { if (it.id == note.id) it.copy(content = it.content + "## 第 3 页\n\nTHIRD_START，刚完成的课堂笔记。\n\n---\n\n") else it }) } }
            compose.waitUntil(10000) { page(2, 3) && ready("SECOND_START") }
            compose.waitForIdle()
            val after = native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") } }
            assertSame("Completed class-page append must retain the current native page", before, after)
            assertSame("Unchanged rendered text must not be replaced on append", oldText, native { after.text })
            assertEquals("Append must retain the current paragraph", oldTop, top("SECOND_START"), 2f)
            if (caret) assertEquals("Append must retain the focused caret", oldCaret, native { after.selectionStart })
            advancedUiShot(compose.activity, "reader-class-append-$mode-$caret-$marked")
        }
    }
    @Test fun completedClassPageAppendKeepsContinuousParagraph() = appendCompletedClassPage("continuous", false)
    @Test fun completedClassPageAppendKeepsFocusedContinuousParagraph() = appendCompletedClassPage("continuous", true)
    @Test fun completedClassPageAppendKeepsPagedParagraph() = appendCompletedClassPage("buttons", false)
    @Test fun completedClassPageAppendKeepsPersonalMarkAndCaret() = appendCompletedClassPage("continuous", true, marked = true)

    @Test fun classPageAppendWaitsForAnAcceptedPaperTurnWithoutLosingItsTarget() {
        val note = fixture().copy(content = "## 第 1 页\n\nFIRST_START。\n\n---\n\n## 第 2 页\n\nSECOND_START。\n\n---\n\n")
        withReader(note, "buttons", false) { _, vm ->
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .9f, height * .5f)) }
                compose.waitUntil(10000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty()
                }
                val destination = native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") } }
                compose.runOnIdle { vm.updateNotebookState { it.copy(notes = it.notes.map { n -> if (n.id == note.id) n.copy(content = n.content + "## 第 3 页\n\nTHIRD_START。") else n }) } }
                compose.waitUntil(12000) {
                    compose.mainClock.advanceTimeByFrame()
                    page(2, 3) && ready("SECOND_START") && !ready("FIRST_START") && compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isEmpty()
                }
                assertSame("Publication must keep the accepted target's native view", destination, native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") } })
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun completedClassPageAppendDoesNotCancelAnOngoingVerticalDrag() {
        val note = fixture().let { it.copy(content = it.content + "\n\n---\n\n") }
        withReader(note, "continuous", true) { _, vm ->
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(center.x, height * .65f))
                moveTo(Offset(center.x, height * .55f), 300)
            }
            compose.waitForIdle()
            val before = top("SECOND_START")
            compose.runOnIdle { vm.updateNotebookState { it.copy(notes = it.notes.map { n -> if (n.id == note.id) n.copy(content = n.content + "## 第 3 页\n\nTHIRD_START。") else n }) } }
            compose.waitUntil(10000) { page(2, 3) && ready("SECOND_START") }
            assertEquals("Append must not move the page under a held finger", before, top("SECOND_START"), 2f)
            var requested = 0f
            compose.onNodeWithTag("笔记正文").performTouchInput {
                requested = height * .05f
                moveTo(Offset(center.x, height * .5f), 300)
                advanceEventTime(240); up()
            }
            compose.waitForIdle()
            assertTrue("The same drag must still advance after append", top("SECOND_START") <= before - requested * .5f)
        }
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

    @Test fun reopeningAnAlreadyVisibleEndKeepsTheNativePageAndNextTap() {
        val first = fixture(firstParagraphs = 4).content.substringBefore("## 第 2 页").trimEnd()
        val second = first.replace("第 1 页", "第 2 页").replace("FIRST_START", "SECOND_START").replace("FIRST_END", "SECOND_END")
        val note = fixture().copy(content = "$first\n\n$second")
        val heightFraction = mutableStateOf(1f)
        withReader(note, "buttons", true, heightFraction) { _, _ ->
            compose.waitUntil(8000) { kotlin.math.abs(top("SECOND_START") - compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow.top) < 2 }
            val body = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
            val lastBottom = native { views ->
                val view = views.first { it.text.contains("SECOND_END") }
                val position = IntArray(2); view.getLocationInWindow(position)
                position[1] + view.totalPaddingTop + requireNotNull(view.layout).height
            }
            val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
            compose.runOnIdle { heightFraction.value = 1f - (body.height - (lastBottom - body.top + 20f)) / root.height }
            compose.waitForIdle()
            compose.waitUntil(8000) { native { views -> views.count { it.isAttachedToWindow && it.text.contains("SECOND_START") } } == 1 }
            val settledBody = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
            assertTrue("The no-op fixture must have its actual last line inside the viewport", top("SECOND_START") + native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") }.layout.height } < settledBody.bottom)
            val before = native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") } }
            Log.i("ReaderBoundary", "noop before body=${compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow} tool=${compose.onNodeWithTag("笔记悬浮工具").fetchSemanticsNode().boundsInWindow} views=${snapshot()}")
            compose.openNoteTool("整本末尾")
            compose.waitForIdle()
            val after = native { views -> views.single { it.isAttachedToWindow && it.text.contains("SECOND_START") } }
            assertSame("Reopening an already visible end must not replace its native view or acquire a new navigation command", before, after)
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .1f, height * .5f)) }
            compose.waitUntil(8000) { page(1) && ready("FIRST_START") && !ready("SECOND_START") }
        }
    }

    private fun resizeDuringMotion(mode: String, released: Boolean) {
        val note = fixture().copy(content = "## 第 1 页\n\nFIRST_START。\n\n## 第 2 页\n\nSECOND_START。")
        val heightFraction = mutableStateOf(1f)
        withReader(note, mode, false, heightFraction) { _, _ ->
            for (direction in if (released) listOf(1, -1) else listOf(1)) {
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        if (mode == "buttons") click(Offset(width * (if (direction > 0) .9f else .1f), height * .5f))
                        else {
                            down(Offset(width * (if (direction > 0) .8f else .2f), height * .5f))
                            moveTo(Offset(width * (if (direction > 0) .2f else .8f), height * .5f), 160)
                            if (released) up()
                        }
                    }
                    compose.waitUntil(12000) {
                        compose.mainClock.advanceTimeByFrame()
                        compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.runOnIdle { heightFraction.value = if (direction > 0) .85f else 1f }
                    if (!released) compose.onNodeWithTag("笔记正文").performTouchInput { cancel() }
                    compose.waitUntil(12000) {
                        compose.mainClock.advanceTimeByFrame()
                        val expected = if (released && direction > 0) 2 else 1
                        page(expected) && ready(if (expected == 2) "SECOND_START" else "FIRST_START") &&
                            !ready(if (expected == 2) "FIRST_START" else "SECOND_START") &&
                            compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isEmpty()
                    }
                } finally { compose.mainClock.autoAdvance = true }
                advancedUiShot(compose.activity, "reader-resize-$mode-$released-$direction")
            }
        }
    }
    @Test fun acceptedTapKeepsItsDestinationWhenViewportHeightChanges() = resizeDuringMotion("buttons", true)
    @Test fun releasedSwipeKeepsItsDestinationWhenViewportHeightChanges() = resizeDuringMotion("swipe", true)
    @Test fun anUnreleasedSwipeStillCancelsWhenViewportHeightChanges() = resizeDuringMotion("swipe", false)
}
